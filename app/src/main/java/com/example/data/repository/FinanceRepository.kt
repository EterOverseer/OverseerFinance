package com.example.data.repository

import android.content.Context
import com.example.data.local.AccountEntity
import com.example.data.local.AppDatabase
import com.example.data.local.AttachmentEntity
import com.example.data.local.BackupHistoryEntity
import com.example.data.local.BudgetEntity
import com.example.data.local.CategorizationRuleEntity
import com.example.data.local.CategoryEntity
import com.example.data.local.DatabaseSeedCatalog
import com.example.data.local.DebtEntity
import com.example.data.local.DebtRepaymentEntity
import com.example.data.local.ImportedImageEntity
import com.example.data.local.OcrResultEntity
import com.example.data.local.RecurringTransactionEntity
import com.example.data.local.ReviewStatus
import com.example.data.local.ScanSourceEntity
import com.example.data.local.TaxDeductionEntity
import com.example.data.local.TaxProfileEntity
import com.example.data.local.TaxRuleEntity
import com.example.data.local.TaxSection
import com.example.data.local.TaxYearEntity
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionSource
import com.example.data.local.TransactionType
import com.example.data.local.WithholdingRecordEntity
import com.example.domain.ThaiDateFormatter
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Single source of truth Repository backed by Room SQLite.
 * Ensures structural defaults (categories, empty initial accounts, official Revenue Department tax rules)
 * are initialized on first run without fabricating fake transactions.
 */
class FinanceRepository(private val context: Context) {

    private val db: AppDatabase
        get() = AppDatabase.getInstance(context)

    val accounts: Flow<List<AccountEntity>> get() = db.accountDao().observeAll()
    val categories: Flow<List<CategoryEntity>> get() = db.categoryDao().observeAll()
    val transactions: Flow<List<TransactionEntity>> get() = db.transactionDao().observeAll()
    val pendingReviewTransactions: Flow<List<TransactionEntity>>
        get() = db.transactionDao().observeByReviewStatus(ReviewStatus.NEEDS_REVIEW)
    val importedImages: Flow<List<ImportedImageEntity>> get() = db.importedImageDao().observeAll()
    val attachments: Flow<List<AttachmentEntity>> get() = db.attachmentDao().observeAll()
    val ocrResults: Flow<List<OcrResultEntity>> get() = db.ocrResultDao().observeAll()
    val scanSource: Flow<ScanSourceEntity?> get() = db.scanSourceDao().observePrimary()
    val categorizationRules: Flow<List<CategorizationRuleEntity>> get() = db.categorizationRuleDao().observeAll()
    val budgets: Flow<List<BudgetEntity>> get() = db.budgetDao().observeAll()
    val recurringTransactions: Flow<List<RecurringTransactionEntity>> get() = db.recurringDao().observeAll()
    val debts: Flow<List<DebtEntity>> get() = db.debtDao().observeAll()
    val debtRepayments: Flow<List<DebtRepaymentEntity>> get() = db.debtDao().observeAllRepayments()
    val taxYears: Flow<List<TaxYearEntity>> get() = db.taxDao().observeTaxYears()
    val backupHistory: Flow<List<BackupHistoryEntity>> get() = db.backupHistoryDao().observeAll()

    fun observeTaxProfile(yearBe: Int): Flow<TaxProfileEntity?> = db.taxDao().observeProfile(yearBe)
    fun observeTaxRule(yearBe: Int): Flow<TaxRuleEntity?> = db.taxDao().observeRule(yearBe)
    fun observeTaxDeductions(yearBe: Int): Flow<List<TaxDeductionEntity>> = db.taxDao().observeDeductions(yearBe)
    fun observeWithholding(yearBe: Int): Flow<List<WithholdingRecordEntity>> = db.taxDao().observeWithholding(yearBe)

    suspend fun ensureInitialized() {
        val currentAccounts = db.accountDao().getActiveOnce()
        if (currentAccounts.isEmpty()) {
            DatabaseSeedCatalog.defaultAccounts().forEach { db.accountDao().upsert(it) }
        }
        val currentCategories = db.categoryDao().getAllOnce()
        if (currentCategories.isEmpty()) {
            db.categoryDao().insertAllIgnore(DatabaseSeedCatalog.defaultCategories())
        }
        db.taxDao().insertYearsIgnore(DatabaseSeedCatalog.defaultTaxYears())
        db.taxDao().insertRulesIgnore(DatabaseSeedCatalog.defaultTaxRules())
        val currentYearBe = ThaiDateFormatter.currentYearBe()
        if (db.taxDao().getRuleOnce(currentYearBe) == null) {
            db.taxDao().insertYearsIgnore(
                listOf(TaxYearEntity(currentYearBe, currentYearBe - 543, "ปีภาษี $currentYearBe (${currentYearBe - 543})", true))
            )
            db.taxDao().insertRulesIgnore(
                listOf(com.example.domain.ThaiTaxRulesCatalog.createRuleEntityForYear(currentYearBe))
            )
        }
    }

    suspend fun saveAccount(account: AccountEntity) = db.accountDao().upsert(account)
    suspend fun deleteAccount(account: AccountEntity) = db.accountDao().delete(account)

    suspend fun saveCategory(category: CategoryEntity) = db.categoryDao().upsert(category)
    suspend fun deleteCategory(category: CategoryEntity) = db.categoryDao().delete(category)

    suspend fun saveTransaction(tx: TransactionEntity) {
        db.transactionDao().upsert(tx.copy(updatedAtEpochMillis = System.currentTimeMillis()))
    }

    suspend fun deleteTransaction(tx: TransactionEntity) = db.transactionDao().delete(tx)

    suspend fun addAttachment(attachment: AttachmentEntity) = db.attachmentDao().upsert(attachment)

    suspend fun saveRule(rule: CategorizationRuleEntity) = db.categorizationRuleDao().upsert(rule)
    suspend fun deleteRule(rule: CategorizationRuleEntity) = db.categorizationRuleDao().delete(rule)

    suspend fun saveBudget(budget: BudgetEntity) = db.budgetDao().upsert(budget)
    suspend fun deleteBudget(budget: BudgetEntity) = db.budgetDao().delete(budget)

    suspend fun saveRecurring(item: RecurringTransactionEntity) = db.recurringDao().upsert(item)
    suspend fun deleteRecurring(item: RecurringTransactionEntity) = db.recurringDao().delete(item)

    suspend fun saveDebt(debt: DebtEntity) = db.debtDao().upsertDebt(debt)
    suspend fun deleteDebt(debt: DebtEntity) = db.debtDao().deleteDebt(debt)

    suspend fun recordDebtRepayment(
        debt: DebtEntity,
        amountSatang: Long,
        accountId: String,
        notes: String,
        attachmentPath: String = ""
    ) {
        val now = System.currentTimeMillis()
        val txType = if (debt.direction == com.example.data.local.DebtDirection.LENT) {
            TransactionType.DEBT_COLLECTION
        } else {
            TransactionType.LOAN_REPAYMENT
        }
        val txId = UUID.randomUUID().toString()
        val linkedTx = TransactionEntity(
            id = txId,
            amountSatang = amountSatang,
            type = txType,
            timestampEpochMillis = now,
            description = "${if (txType == TransactionType.DEBT_COLLECTION) "รับคืนหนี้" else "ชำระคืนหนี้"}: ${debt.title}",
            accountId = accountId,
            recipientName = debt.counterpartyName,
            notes = notes,
            source = TransactionSource.DEBT_REPAYMENT,
            taxSection = TaxSection.NON_TAXABLE,
            reviewStatus = ReviewStatus.VERIFIED
        )
        db.transactionDao().upsert(linkedTx)
        val repayment = DebtRepaymentEntity(
            debtId = debt.id,
            transactionId = txId,
            amountSatang = amountSatang,
            paidAtEpochMillis = now,
            notes = notes,
            attachmentPath = attachmentPath
        )
        db.debtDao().recordRepayment(debt, repayment)
    }

    suspend fun saveTaxProfile(profile: TaxProfileEntity) = db.taxDao().upsertProfile(profile)
    suspend fun saveTaxDeduction(deduction: TaxDeductionEntity) = db.taxDao().upsertDeduction(deduction)
    suspend fun deleteTaxDeduction(deduction: TaxDeductionEntity) = db.taxDao().deleteDeduction(deduction)
    suspend fun saveWithholding(record: WithholdingRecordEntity) = db.taxDao().upsertWithholding(record)
    suspend fun deleteWithholding(record: WithholdingRecordEntity) = db.taxDao().deleteWithholding(record)

    companion object {
        /**
         * Pure precision account balance calculation using Satang integer math.
         * Transfers move balances between own accounts without altering total net worth or income/expense.
         */
        fun computeAccountBalanceSatang(
            account: AccountEntity,
            transactions: List<TransactionEntity>
        ): Long {
            var balance = account.openingBalanceSatang
            for (tx in transactions) {
                if (tx.accountId == account.id) {
                    balance += when (tx.type) {
                        TransactionType.INCOME,
                        TransactionType.REFUND,
                        TransactionType.LOAN_RECEIVED,
                        TransactionType.DEBT_COLLECTION -> tx.amountSatang
                        TransactionType.EXPENSE,
                        TransactionType.INTERNAL_TRANSFER,
                        TransactionType.LOAN_REPAYMENT,
                        TransactionType.OTHER -> -tx.amountSatang
                    }
                }
                if (tx.type == TransactionType.INTERNAL_TRANSFER && tx.toAccountId == account.id) {
                    balance += tx.amountSatang
                }
            }
            return balance
        }
    }
}
