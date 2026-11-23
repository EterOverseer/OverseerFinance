package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY isActive DESC, createdAtEpochMillis ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE isActive = 1 ORDER BY createdAtEpochMillis ASC")
    suspend fun getActiveOnce(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: String): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AccountEntity)

    @Delete
    suspend fun delete(account: AccountEntity)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE isActive = 1 ORDER BY type ASC, isSystem DESC, nameTh ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE isActive = 1")
    suspend fun getAllOnce(): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(categories: List<CategoryEntity>)

    @Delete
    suspend fun delete(category: CategoryEntity)
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY name ASC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY timestampEpochMillis DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE reviewStatus = :status ORDER BY timestampEpochMillis DESC")
    fun observeByReviewStatus(status: ReviewStatus): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY timestampEpochMillis DESC")
    suspend fun getAllOnce(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: String): TransactionEntity?

    @Query("SELECT COUNT(*) FROM transactions")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(transaction: TransactionEntity)

    @Delete
    suspend fun delete(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments ORDER BY createdAtEpochMillis DESC")
    fun observeAll(): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE transactionId = :transactionId")
    fun observeForTransaction(transactionId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE transactionId = :transactionId")
    suspend fun getForTransactionOnce(transactionId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE importedImageId = :imageId LIMIT 1")
    suspend fun getByImportedImageId(imageId: String): AttachmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(attachment: AttachmentEntity)
}

@Dao
interface ImportedImageDao {
    @Query("SELECT * FROM imported_images ORDER BY importedAtEpochMillis DESC")
    fun observeAll(): Flow<List<ImportedImageEntity>>

    @Query("SELECT * FROM imported_images WHERE importState = :state ORDER BY importedAtEpochMillis DESC")
    fun observeByState(state: ImageImportState): Flow<List<ImportedImageEntity>>

    @Query("SELECT * FROM imported_images WHERE sha256 = :sha256 LIMIT 1")
    suspend fun findBySha256(sha256: String): ImportedImageEntity?

    @Query("SELECT * FROM imported_images WHERE importState = 'IMPORTED_VERIFIED'")
    suspend fun getVerifiedOnce(): List<ImportedImageEntity>

    @Query("SELECT * FROM imported_images WHERE id = :id")
    suspend fun getById(id: String): ImportedImageEntity?

    @Query("SELECT * FROM imported_images")
    suspend fun getAllOnce(): List<ImportedImageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(image: ImportedImageEntity)

    @Query("UPDATE imported_images SET sourceDeletedAtEpochMillis = :deletedAt WHERE id = :id")
    suspend fun markSourceDeleted(id: String, deletedAt: Long)
}

@Dao
interface ScanSourceDao {
    @Query("SELECT * FROM scan_sources WHERE id = 'kplus_primary' LIMIT 1")
    fun observePrimary(): Flow<ScanSourceEntity?>

    @Query("SELECT * FROM scan_sources WHERE id = 'kplus_primary' LIMIT 1")
    suspend fun getPrimaryOnce(): ScanSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: ScanSourceEntity)
}

@Dao
interface OcrResultDao {
    @Query("SELECT * FROM ocr_results ORDER BY createdAtEpochMillis DESC")
    fun observeAll(): Flow<List<OcrResultEntity>>

    @Query("SELECT * FROM ocr_results WHERE importedImageId = :imageId LIMIT 1")
    suspend fun getByImageId(imageId: String): OcrResultEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(result: OcrResultEntity)
}

@Dao
interface CategorizationRuleDao {
    @Query("SELECT * FROM categorization_rules ORDER BY priority ASC")
    fun observeAll(): Flow<List<CategorizationRuleEntity>>

    @Query("SELECT * FROM categorization_rules WHERE isEnabled = 1 ORDER BY priority ASC")
    suspend fun getEnabledOnce(): List<CategorizationRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: CategorizationRuleEntity)

    @Delete
    suspend fun delete(rule: CategorizationRuleEntity)
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budgets ORDER BY yearMonth DESC, name ASC")
    fun observeAll(): Flow<List<BudgetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(budget: BudgetEntity)

    @Delete
    suspend fun delete(budget: BudgetEntity)
}

@Dao
interface RecurringTransactionDao {
    @Query("SELECT * FROM recurring_transactions ORDER BY nextDueEpochMillis ASC")
    fun observeAll(): Flow<List<RecurringTransactionEntity>>

    @Query("SELECT * FROM recurring_transactions WHERE isActive = 1")
    suspend fun getActiveOnce(): List<RecurringTransactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RecurringTransactionEntity)

    @Delete
    suspend fun delete(item: RecurringTransactionEntity)
}

@Dao
interface DebtDao {
    @Query("SELECT * FROM debts ORDER BY status ASC, dueEpochMillis ASC")
    fun observeAll(): Flow<List<DebtEntity>>

    @Query("SELECT * FROM debt_repayments ORDER BY paidAtEpochMillis DESC")
    fun observeAllRepayments(): Flow<List<DebtRepaymentEntity>>

    @Query("SELECT * FROM debts WHERE id = :id")
    suspend fun getDebtById(id: String): DebtEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDebt(debt: DebtEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRepayment(repayment: DebtRepaymentEntity)

    @Delete
    suspend fun deleteDebt(debt: DebtEntity)

    @Transaction
    suspend fun recordRepayment(
        debt: DebtEntity,
        repayment: DebtRepaymentEntity
    ) {
        insertRepayment(repayment)
        val newRemaining = (debt.remainingSatang - repayment.amountSatang).coerceAtLeast(0L)
        val newStatus = if (newRemaining == 0L) DebtStatus.PAID_OFF else debt.status
        upsertDebt(debt.copy(remainingSatang = newRemaining, status = newStatus))
    }
}

@Dao
interface TaxDao {
    @Query("SELECT * FROM tax_years ORDER BY taxYearBe DESC")
    fun observeTaxYears(): Flow<List<TaxYearEntity>>

    @Query("SELECT * FROM tax_profiles WHERE taxYearBe = :yearBe LIMIT 1")
    fun observeProfile(yearBe: Int): Flow<TaxProfileEntity?>

    @Query("SELECT * FROM tax_rules WHERE taxYearBe = :yearBe LIMIT 1")
    fun observeRule(yearBe: Int): Flow<TaxRuleEntity?>

    @Query("SELECT * FROM tax_rules WHERE taxYearBe = :yearBe LIMIT 1")
    suspend fun getRuleOnce(yearBe: Int): TaxRuleEntity?

    @Query("SELECT * FROM tax_deductions WHERE taxYearBe = :yearBe ORDER BY deductionType ASC")
    fun observeDeductions(yearBe: Int): Flow<List<TaxDeductionEntity>>

    @Query("SELECT * FROM withholding_records WHERE taxYearBe = :yearBe ORDER BY dateEpochMillis DESC")
    fun observeWithholding(yearBe: Int): Flow<List<WithholdingRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertYearsIgnore(years: List<TaxYearEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRulesIgnore(rules: List<TaxRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: TaxProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDeduction(deduction: TaxDeductionEntity)

    @Delete
    suspend fun deleteDeduction(deduction: TaxDeductionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWithholding(record: WithholdingRecordEntity)

    @Delete
    suspend fun deleteWithholding(record: WithholdingRecordEntity)
}

@Dao
interface AppSettingDao {
    @Query("SELECT * FROM app_settings")
    fun observeAll(): Flow<List<AppSettingEntity>>

    @Query("SELECT * FROM app_settings WHERE key = :key LIMIT 1")
    suspend fun getValue(key: String): AppSettingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(setting: AppSettingEntity)
}

@Dao
interface BackupHistoryDao {
    @Query("SELECT * FROM backup_history ORDER BY createdAtEpochMillis DESC")
    fun observeAll(): Flow<List<BackupHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: BackupHistoryEntity)
}
