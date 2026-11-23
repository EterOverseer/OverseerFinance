package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.OverseerApplication
import com.example.data.local.AccountEntity
import com.example.data.local.AppDatabase
import com.example.data.local.AttachmentEntity
import com.example.data.local.BackupHistoryEntity
import com.example.data.local.BudgetEntity
import com.example.data.local.CategorizationRuleEntity
import com.example.data.local.CategoryEntity
import com.example.data.local.DateDisplayFormat
import com.example.data.local.DebtEntity
import com.example.data.local.DebtRepaymentEntity
import com.example.data.local.DebtStatus
import com.example.data.local.DocumentVerificationStatus
import com.example.data.local.DeductionType
import com.example.data.local.ImportedImageEntity
import com.example.data.local.OcrModelStatus
import com.example.data.local.OcrResultEntity
import com.example.data.local.OpenApiSettingsEntity
import com.example.data.local.PeriodFilter
import com.example.data.local.ReconciliationAuditEntity
import com.example.data.local.RecurringTransactionEntity
import com.example.data.local.ReviewStatus
import com.example.data.local.ScanSourceEntity
import com.example.data.local.StatementImportBatchEntity
import com.example.data.local.TaxDeductionEntity
import com.example.data.local.TaxDocumentCategory
import com.example.data.local.TaxDocumentEntity
import com.example.data.local.TaxFilingHistoryEntity
import com.example.data.local.TaxFilingState
import com.example.data.local.TaxFormType
import com.example.data.local.TaxProfileEntity
import com.example.data.local.TaxRuleEntity
import com.example.data.local.TaxSection
import com.example.data.local.TaxYearEntity
import com.example.data.local.TaxpayerIdentityEntity
import com.example.data.local.ThemeMode
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import com.example.data.local.WithholdingRecordEntity
import com.example.data.preferences.UserPreferences
import com.example.data.repository.FinanceRepository
import com.example.domain.BackupCenterManager
import com.example.domain.BackupPreviewResult
import com.example.domain.FinancialInsight
import com.example.domain.FinancialIntelligenceEngine
import com.example.domain.PaddleOcrEngine
import com.example.domain.ReportExporter
import com.example.domain.ReportType
import com.example.domain.TaxCalculationReport
import com.example.domain.ThaiDateFormatter
import com.example.domain.ThaiTaxEngine
import com.example.domain.ThaiTaxRulesCatalog
import com.example.notifications.ReminderScheduler
import com.example.tax.api.LocalXmlExportProvider
import com.example.tax.api.TaxSubmissionRequest
import com.example.tax.engine.AnnualTaxWorkspaceSummary
import com.example.tax.engine.ThaiTaxFilingEngine
import com.example.tax.reconciliation.BankCsvColumnMapping
import com.example.tax.reconciliation.BankStatementReconciler
import com.example.tax.vault.TaxDocumentVaultManager
import com.example.tax.xml.RdXmlPublicSerializer
import com.example.tax.xml.RdXmlSpecificationRegistry
import com.example.ui.components.MonthlyChartPoint
import com.example.worker.CleanerDeleteResult
import com.example.worker.KPlusScanWorker
import com.example.worker.KPlusScannerPipeline
import com.example.worker.ScanRunSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.YearMonth

data class DashboardSummaryState(
    val periodFilter: PeriodFilter = PeriodFilter.THIS_MONTH,
    val customStartMillis: Long? = null,
    val customEndMillis: Long? = null,
    val totalAvailableBalanceSatang: Long = 0L,
    val periodIncomeSatang: Long = 0L,
    val periodExpenseSatang: Long = 0L,
    val periodTransferSatang: Long = 0L,
    val netCashFlowSatang: Long = 0L,
    val pendingOcrCount: Int = 0,
    val sixMonthChart: List<MonthlyChartPoint> = emptyList(),
    val topExpenseCategories: List<Pair<CategoryEntity?, Long>> = emptyList(),
    val insights: List<FinancialInsight> = emptyList(),
    val monthlyBudgetLimitSatang: Long = 0L,
    val monthlyBudgetSpentSatang: Long = 0L
)

data class BackupCenterUiState(
    val isBusy: Boolean = false,
    val statusBanner: String? = null,
    val errorBanner: String? = null,
    val pendingPreview: BackupPreviewResult? = null,
    val pendingRestoreUri: Uri? = null,
    val pendingRestorePassword: String = "",
    val pendingRestoreIsSqlite: Boolean = false,
    val showReconnectKPlusBanner: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class OverseerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OverseerApplication
    private val repo = app.repository
    private val prefsRepo = app.preferencesRepository
    private val scannerPipeline = KPlusScannerPipeline(application)
    private val backupManager = BackupCenterManager(application)
    private val ocrEngine = PaddleOcrEngine(application)
    private val taxDocVaultManager = TaxDocumentVaultManager(application)
    private val localXmlProvider = LocalXmlExportProvider()

    private val _periodFilter = MutableStateFlow(PeriodFilter.THIS_MONTH)
    private val _customRange = MutableStateFlow<Pair<Long?, Long?>>(null to null)
    private val _taxFeedback = MutableStateFlow<String?>(null)
    val taxFeedback: StateFlow<String?> = _taxFeedback.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanFeedback = MutableStateFlow<String?>(null)
    val scanFeedback: StateFlow<String?> = _scanFeedback.asStateFlow()

    private val _backupUiState = MutableStateFlow(BackupCenterUiState())
    val backupUiState: StateFlow<BackupCenterUiState> = _backupUiState.asStateFlow()

    val preferences: StateFlow<UserPreferences> = prefsRepo.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())

    val accounts: StateFlow<List<AccountEntity>> = repo.accounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> = repo.categories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val transactions: StateFlow<List<TransactionEntity>> = repo.transactions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingReviewTransactions: StateFlow<List<TransactionEntity>> = repo.pendingReviewTransactions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val importedImages: StateFlow<List<ImportedImageEntity>> = repo.importedImages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val attachments: StateFlow<List<AttachmentEntity>> = repo.attachments
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val ocrResults: StateFlow<List<OcrResultEntity>> = repo.ocrResults
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val scanSource: StateFlow<ScanSourceEntity?> = repo.scanSource
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val rules: StateFlow<List<CategorizationRuleEntity>> = repo.categorizationRules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val budgets: StateFlow<List<BudgetEntity>> = repo.budgets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recurringTransactions: StateFlow<List<RecurringTransactionEntity>> = repo.recurringTransactions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val debts: StateFlow<List<DebtEntity>> = repo.debts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val debtRepayments: StateFlow<List<DebtRepaymentEntity>> = repo.debtRepayments
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val taxYears: StateFlow<List<TaxYearEntity>> = repo.taxYears
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val backupHistory: StateFlow<List<BackupHistoryEntity>> = repo.backupHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val ocrModelStatus: OcrModelStatus get() = ocrEngine.inspectModelAvailability()

    val taxProfile: StateFlow<TaxProfileEntity?> = preferences
        .flatMapLatest { repo.observeTaxProfile(it.selectedTaxYearBe) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val taxRule: StateFlow<TaxRuleEntity?> = preferences
        .flatMapLatest { repo.observeTaxRule(it.selectedTaxYearBe) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val taxDeductions: StateFlow<List<TaxDeductionEntity>> = preferences
        .flatMapLatest { repo.observeTaxDeductions(it.selectedTaxYearBe) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val withholdingRecords: StateFlow<List<WithholdingRecordEntity>> = preferences
        .flatMapLatest { repo.observeWithholding(it.selectedTaxYearBe) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val taxpayerIdentity: StateFlow<TaxpayerIdentityEntity?> = preferences
        .flatMapLatest { repo.observeTaxpayerIdentity(it.selectedTaxYearBe) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val reconciliationAudits: StateFlow<List<ReconciliationAuditEntity>> = preferences
        .flatMapLatest { repo.observeReconciliationAudits(it.selectedTaxYearBe) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val statementImportBatches: StateFlow<List<StatementImportBatchEntity>> = repo.statementImportBatches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val taxDocuments: StateFlow<List<TaxDocumentEntity>> = preferences
        .flatMapLatest { repo.observeTaxDocuments(it.selectedTaxYearBe) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val taxFilingHistory: StateFlow<List<TaxFilingHistoryEntity>> = preferences
        .flatMapLatest { repo.observeTaxFilingHistory(it.selectedTaxYearBe) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val openApiSettings: StateFlow<OpenApiSettingsEntity> = repo.openApiSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OpenApiSettingsEntity())
        .let { flow ->
            combine(flow) { arr -> arr[0] ?: OpenApiSettingsEntity() }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OpenApiSettingsEntity())
        }

    val taxReport: StateFlow<TaxCalculationReport?> = combine(
        preferences,
        taxProfile,
        taxRule,
        transactions,
        taxDeductions,
        withholdingRecords
    ) { arr ->
        val pref = arr[0] as UserPreferences
        val profile = (arr[1] as? TaxProfileEntity) ?: TaxProfileEntity(pref.selectedTaxYearBe)
        val rule = (arr[2] as? TaxRuleEntity) ?: ThaiTaxRulesCatalog.createRuleEntityForYear(pref.selectedTaxYearBe)
        @Suppress("UNCHECKED_CAST")
        val txs = arr[3] as List<TransactionEntity>
        @Suppress("UNCHECKED_CAST")
        val deductions = arr[4] as List<TaxDeductionEntity>
        @Suppress("UNCHECKED_CAST")
        val withheld = arr[5] as List<WithholdingRecordEntity>

        val bizExpense408 = txs.filter {
            it.isBusiness && it.type == TransactionType.EXPENSE &&
                ThaiDateFormatter.toBangkokDate(it.timestampEpochMillis).year == pref.selectedTaxYearBe - 543
        }.sumOf { it.amountSatang + it.businessCostSatang + it.shippingCostSatang + it.adSpendSatang }

        ThaiTaxEngine.calculate(
            taxYearBe = pref.selectedTaxYearBe,
            rule = rule,
            profile = profile,
            transactions = txs,
            confirmedDeductions = deductions,
            withholdingRecords = withheld,
            additional408BusinessExpenseSatang = bizExpense408
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val taxWorkspaceSummary: StateFlow<AnnualTaxWorkspaceSummary?> = combine(
        preferences,
        taxRule,
        taxProfile,
        taxpayerIdentity,
        transactions,
        taxDeductions,
        withholdingRecords,
        taxDocuments,
        taxReport
    ) { arr ->
        val pref = arr[0] as UserPreferences
        val rule = (arr[1] as? TaxRuleEntity) ?: ThaiTaxRulesCatalog.createRuleEntityForYear(pref.selectedTaxYearBe)
        val profile = (arr[2] as? TaxProfileEntity) ?: TaxProfileEntity(pref.selectedTaxYearBe)
        val identity = arr[3] as? TaxpayerIdentityEntity
        @Suppress("UNCHECKED_CAST")
        val txs = arr[4] as List<TransactionEntity>
        @Suppress("UNCHECKED_CAST")
        val deductions = arr[5] as List<TaxDeductionEntity>
        @Suppress("UNCHECKED_CAST")
        val withheld = arr[6] as List<WithholdingRecordEntity>
        @Suppress("UNCHECKED_CAST")
        val docs = arr[7] as List<TaxDocumentEntity>
        val report = arr[8] as? TaxCalculationReport ?: return@combine null

        val recForm = ThaiTaxFilingEngine.determineFormEligibility(
            taxYearBe = pref.selectedTaxYearBe,
            profile = profile,
            transactions = txs,
            withholdingRecords = withheld,
            report = report
        ).recommendedForm
        val xmlSpecVerified = recForm != null &&
            RdXmlSpecificationRegistry.getSpecFor(recForm, pref.selectedTaxYearBe).isOfficiallyVerified

        ThaiTaxFilingEngine.buildWorkspaceSummary(
            taxYearBe = pref.selectedTaxYearBe,
            rule = rule,
            profile = profile,
            identity = identity,
            transactions = txs,
            deductions = deductions,
            withholdingRecords = withheld,
            documents = docs,
            report = report,
            officialXmlSpecVerified = xmlSpecVerified
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val dashboardState: StateFlow<DashboardSummaryState> = combine(
        _periodFilter,
        _customRange,
        accounts,
        categories,
        transactions,
        budgets,
        recurringTransactions
    ) { arr ->
        val period = arr[0] as PeriodFilter
        @Suppress("UNCHECKED_CAST")
        val custom = arr[1] as Pair<Long?, Long?>
        @Suppress("UNCHECKED_CAST")
        val accs = arr[2] as List<AccountEntity>
        @Suppress("UNCHECKED_CAST")
        val cats = arr[3] as List<CategoryEntity>
        @Suppress("UNCHECKED_CAST")
        val txs = arr[4] as List<TransactionEntity>
        @Suppress("UNCHECKED_CAST")
        val buds = arr[5] as List<BudgetEntity>
        @Suppress("UNCHECKED_CAST")
        val recs = arr[6] as List<RecurringTransactionEntity>

        val totalBalance = accs.filter { it.isActive }.sumOf { acc ->
            FinanceRepository.computeAccountBalanceSatang(acc, txs)
        }

        val (startMs, endMs) = ThaiDateFormatter.periodBoundsMillis(
            filter = period,
            customStartMillis = custom.first,
            customEndMillis = custom.second
        )
        val periodTx = txs.filter { it.timestampEpochMillis in startMs..endMs }
        val periodIncome = periodTx.filter { it.type == TransactionType.INCOME }.sumOf { it.amountSatang }
        val periodExpense = periodTx.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountSatang }
        val periodTransfer = periodTx.filter { it.type == TransactionType.INTERNAL_TRANSFER }.sumOf { it.amountSatang }

        val catMap = cats.associateBy { it.id }
        val topCategories = periodTx
            .filter { it.type == TransactionType.EXPENSE }
            .groupBy { it.categoryId }
            .map { (catId, list) -> catMap[catId] to list.sumOf { it.amountSatang } }
            .sortedByDescending { it.second }
            .take(5)

        val currentYm = YearMonth.parse(ThaiDateFormatter.currentYearMonth())
        val sixMonths = (5 downTo 0).map { offset ->
            val ym = currentYm.minusMonths(offset.toLong())
            val monthTxs = txs.filter { tx ->
                val d = ThaiDateFormatter.toBangkokDate(tx.timestampEpochMillis)
                d.year == ym.year && d.monthValue == ym.monthValue
            }
            MonthlyChartPoint(
                label = "${ym.monthValue}/${(ym.year + 543) % 100}",
                incomeSatang = monthTxs.filter { it.type == TransactionType.INCOME }.sumOf { it.amountSatang },
                expenseSatang = monthTxs.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountSatang }
            )
        }

        val currentMonthStr = currentYm.toString()
        val monthBudgets = buds.filter { it.isActive && it.yearMonth == currentMonthStr }
        val overallLimit = monthBudgets.firstOrNull { it.categoryId == null }?.limitSatang
            ?: monthBudgets.sumOf { it.limitSatang }
        val currentMonthExpense = txs.filter {
            it.type == TransactionType.EXPENSE &&
                ThaiDateFormatter.toBangkokDate(it.timestampEpochMillis).let { d ->
                    d.year == currentYm.year && d.monthValue == currentYm.monthValue
                }
        }.sumOf { it.amountSatang }

        val insights = FinancialIntelligenceEngine.analyze(
            transactions = txs,
            categories = cats,
            budgets = buds,
            recurring = recs,
            totalAvailableBalanceSatang = totalBalance,
            referenceYearMonth = currentMonthStr
        )

        DashboardSummaryState(
            periodFilter = period,
            customStartMillis = custom.first,
            customEndMillis = custom.second,
            totalAvailableBalanceSatang = totalBalance,
            periodIncomeSatang = periodIncome,
            periodExpenseSatang = periodExpense,
            periodTransferSatang = periodTransfer,
            netCashFlowSatang = periodIncome - periodExpense,
            pendingOcrCount = txs.count { it.reviewStatus == ReviewStatus.NEEDS_REVIEW },
            sixMonthChart = sixMonths,
            topExpenseCategories = topCategories,
            insights = insights,
            monthlyBudgetLimitSatang = overallLimit,
            monthlyBudgetSpentSatang = currentMonthExpense
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardSummaryState())

    fun setPeriodFilter(filter: PeriodFilter, customStart: Long? = null, customEnd: Long? = null) {
        _periodFilter.value = filter
        if (filter == PeriodFilter.CUSTOM) {
            _customRange.value = customStart to customEnd
        }
    }

    // --- Accounts & Categories ---
    fun saveAccount(account: AccountEntity) = viewModelScope.launch { repo.saveAccount(account) }
    fun deleteAccount(account: AccountEntity) = viewModelScope.launch {
        runCatching { repo.deleteAccount(account) }
            .onFailure { _scanFeedback.value = "ไม่สามารถลบบัญชีที่มีรายการเชื่อมอยู่ได้ กรุณาปิดใช้งานแทน" }
    }

    fun saveCategory(category: CategoryEntity) = viewModelScope.launch { repo.saveCategory(category) }
    fun deleteCategory(category: CategoryEntity) = viewModelScope.launch { repo.deleteCategory(category) }

    fun saveRule(rule: CategorizationRuleEntity) = viewModelScope.launch { repo.saveRule(rule) }
    fun deleteRule(rule: CategorizationRuleEntity) = viewModelScope.launch { repo.deleteRule(rule) }

    // --- Transactions ---
    fun saveTransaction(tx: TransactionEntity, attachedPhotoUri: Uri? = null) = viewModelScope.launch {
        repo.saveTransaction(tx)
        if (attachedPhotoUri != null) {
            withContext(Dispatchers.IO) {
                copyManualAttachmentToVault(tx.id, attachedPhotoUri)
            }
        }
    }

    private suspend fun copyManualAttachmentToVault(txId: String, uri: Uri) {
        runCatching {
            val resolver = getApplication<Application>().contentResolver
            val vaultDir = scannerPipeline.vaultImagesDir()
            val fileName = "attach_${System.currentTimeMillis()}.jpg"
            val destFile = File(vaultDir, "${txId}_$fileName")
            resolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            if (destFile.exists() && destFile.length() > 0L) {
                val sha = com.example.domain.ImageHashing.sha256(destFile)
                repo.addAttachment(
                    AttachmentEntity(
                        transactionId = txId,
                        localFilePath = destFile.absolutePath,
                        originalFileName = fileName,
                        mimeType = resolver.getType(uri) ?: "image/jpeg",
                        fileSizeBytes = destFile.length(),
                        sha256 = sha
                    )
                )
            }
        }
    }

    fun deleteTransaction(tx: TransactionEntity) = viewModelScope.launch { repo.deleteTransaction(tx) }

    // --- K PLUS Scanner & Storage Cleaner ---
    fun onKPlusFolderSelected(treeUri: Uri) = viewModelScope.launch {
        _isScanning.value = true
        val source = scannerPipeline.registerFolderUri(treeUri)
        _backupUiState.value = _backupUiState.value.copy(showReconnectKPlusBanner = false)
        _scanFeedback.value = "เชื่อมต่อโฟลเดอร์ ${source.displayName} แล้ว กำลังสแกน..."
        val summary = scannerPipeline.scanConfiguredFolder(preferences.value.pHashSimilarityThreshold)
        _scanFeedback.value = summary.statusMessage
        _isScanning.value = false
    }

    fun triggerManualScan() = viewModelScope.launch {
        if (_isScanning.value) return@launch
        _isScanning.value = true
        val summary: ScanRunSummary = scannerPipeline.scanConfiguredFolder(preferences.value.pHashSimilarityThreshold)
        _scanFeedback.value = summary.statusMessage
        _isScanning.value = false
    }

    fun importSinglePickedSlip(uri: Uri) = viewModelScope.launch {
        _isScanning.value = true
        val state = scannerPipeline.importSingleUri(
            sourceUri = uri,
            pHashThreshold = preferences.value.pHashSimilarityThreshold
        )
        _scanFeedback.value = "ผลนำเข้าสลิป: ${state.labelTh}"
        _isScanning.value = false
    }

    fun deleteKPlusOriginals(images: List<ImportedImageEntity>) = viewModelScope.launch {
        var deleted = 0
        var lastError: String? = null
        for (img in images) {
            when (val res = scannerPipeline.deleteOriginalSafImageSafely(img)) {
                is CleanerDeleteResult.Deleted -> deleted++
                is CleanerDeleteResult.RejectedSafety -> lastError = res.reason
                is CleanerDeleteResult.ProviderDenied -> lastError = res.reason
            }
        }
        _scanFeedback.value = if (lastError != null) {
            "ลบต้นฉบับสำเร็จ $deleted รายการ • $lastError"
        } else {
            "ลบภาพต้นฉบับในโฟลเดอร์ K PLUS สำเร็จ $deleted รายการ (สำเนาใน OverseerFinance ยังปลอดภัย)"
        }
    }

    fun clearScanFeedback() {
        _scanFeedback.value = null
    }

    // --- Budgets, Recurring & Debts ---
    fun saveBudget(budget: BudgetEntity) = viewModelScope.launch { repo.saveBudget(budget) }
    fun deleteBudget(budget: BudgetEntity) = viewModelScope.launch { repo.deleteBudget(budget) }

    fun saveRecurring(item: RecurringTransactionEntity) = viewModelScope.launch {
        repo.saveRecurring(item)
        if (item.isActive && preferences.value.notificationsEnabled) {
            val triggerAt = item.nextDueEpochMillis - item.reminderDaysBefore * 86_400_000L
            ReminderScheduler.scheduleReminder(
                context = getApplication(),
                requestCode = item.id.hashCode(),
                triggerAtMillis = triggerAt,
                title = "ใกล้ถึงกำหนดชำระ: ${item.title}",
                message = "ยอด ${com.example.domain.MoneyFormatter.formatThb(item.amountSatang)}"
            )
        }
    }

    fun deleteRecurring(item: RecurringTransactionEntity) = viewModelScope.launch { repo.deleteRecurring(item) }

    fun saveDebt(debt: DebtEntity) = viewModelScope.launch {
        repo.saveDebt(debt)
        if (debt.reminderEnabled && debt.dueEpochMillis != null && debt.status != DebtStatus.PAID_OFF && preferences.value.notificationsEnabled) {
            ReminderScheduler.scheduleReminder(
                context = getApplication(),
                requestCode = debt.id.hashCode(),
                triggerAtMillis = debt.dueEpochMillis - 86_400_000L,
                title = "เตือนครบกำหนดหนี้: ${debt.title}",
                message = "${debt.counterpartyName} • คงเหลือ ${com.example.domain.MoneyFormatter.formatThb(debt.remainingSatang)}"
            )
        }
    }

    fun deleteDebt(debt: DebtEntity) = viewModelScope.launch { repo.deleteDebt(debt) }

    fun recordDebtRepayment(
        debt: DebtEntity,
        amountSatang: Long,
        accountId: String,
        notes: String
    ) = viewModelScope.launch {
        repo.recordDebtRepayment(debt, amountSatang, accountId, notes)
    }

    // --- Tax ---
    fun saveTaxProfile(profile: TaxProfileEntity) = viewModelScope.launch { repo.saveTaxProfile(profile) }
    fun saveTaxDeduction(deduction: TaxDeductionEntity) = viewModelScope.launch { repo.saveTaxDeduction(deduction) }
    fun deleteTaxDeduction(deduction: TaxDeductionEntity) = viewModelScope.launch { repo.deleteTaxDeduction(deduction) }
    fun saveWithholding(record: WithholdingRecordEntity) = viewModelScope.launch { repo.saveWithholding(record) }
    fun deleteWithholding(record: WithholdingRecordEntity) = viewModelScope.launch { repo.deleteWithholding(record) }
    fun saveTaxpayerIdentity(identity: TaxpayerIdentityEntity) = viewModelScope.launch {
        repo.saveTaxpayerIdentity(identity)
        _taxFeedback.value = "บันทึกข้อมูลผู้เสียภาษีปี ${identity.taxYearBe} แล้ว"
    }

    fun reclassifyTransactionWithAudit(
        tx: TransactionEntity,
        newType: TransactionType,
        newTaxSection: TaxSection,
        newIsBusiness: Boolean,
        newReviewStatus: ReviewStatus = ReviewStatus.VERIFIED,
        reason: String = "ผู้ใช้จัดประเภทภาษีจากหน้า Tax Center"
    ) = viewModelScope.launch {
        repo.reclassifyTransactionWithAudit(tx, newType, newTaxSection, newIsBusiness, newReviewStatus, reason)
        _taxFeedback.value = "อัปเดตการจัดประเภทภาษีและบันทึก Audit Log แล้ว"
    }

    fun importBankStatementCsv(
        uri: Uri,
        fileName: String,
        accountId: String,
        mapping: BankCsvColumnMapping
    ) = viewModelScope.launch(Dispatchers.IO) {
        val outcome = runCatching {
            val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("ไม่สามารถเปิดอ่านไฟล์ CSV ได้")
            BankStatementReconciler.importCsvIntoDatabase(
                db = AppDatabase.getInstance(getApplication()),
                rawBytes = bytes,
                fileName = fileName,
                accountId = accountId,
                mapping = mapping
            )
        }
        withContext(Dispatchers.Main) {
            _taxFeedback.value = outcome.fold(
                onSuccess = { it.messageTh },
                onFailure = { "นำเข้า CSV ไม่สำเร็จ: ${it.message}" }
            )
        }
    }

    fun importTaxDocument(
        uri: Uri,
        category: TaxDocumentCategory,
        title: String,
        notes: String,
        linkedTransactionId: String? = null,
        linkedDeductionId: String? = null
    ) = viewModelScope.launch(Dispatchers.IO) {
        val yearBe = preferences.value.selectedTaxYearBe
        val result = taxDocVaultManager.importFromUri(
            sourceUri = uri,
            taxYearBe = yearBe,
            category = category,
            customTitle = title,
            notes = notes,
            linkedTransactionId = linkedTransactionId,
            linkedDeductionId = linkedDeductionId
        )
        result.onSuccess { doc ->
            repo.saveTaxDocument(doc)
        }
        withContext(Dispatchers.Main) {
            _taxFeedback.value = result.fold(
                onSuccess = { "นำเข้า '${it.title}' ลงคลังเอกสารแล้ว (รอผู้ใช้ยืนยันสิทธิ)" },
                onFailure = { "นำเข้าเอกสารไม่สำเร็จ: ${it.message}" }
            )
        }
    }

    fun confirmTaxDocument(
        doc: TaxDocumentEntity,
        deductionAmountSatang: Long? = null,
        deductionType: DeductionType? = null
    ) = viewModelScope.launch {
        var linkedDedId = doc.linkedDeductionId
        if (deductionAmountSatang != null && deductionAmountSatang > 0L && deductionType != null) {
            val createdDed = taxDocVaultManager.buildConfirmedDeductionFromDocument(
                doc = doc,
                amountSatang = deductionAmountSatang,
                deductionType = deductionType
            )
            repo.saveTaxDeduction(createdDed)
            linkedDedId = createdDed.id
        }
        repo.saveTaxDocument(
            doc.copy(
                verificationStatus = DocumentVerificationStatus.USER_CONFIRMED,
                linkedDeductionId = linkedDedId
            )
        )
        _taxFeedback.value = "ยืนยันเอกสาร '${doc.title}' แล้ว"
    }

    fun rejectTaxDocument(doc: TaxDocumentEntity) = viewModelScope.launch {
        repo.saveTaxDocument(doc.copy(verificationStatus = DocumentVerificationStatus.REJECTED))
        _taxFeedback.value = "ระบุไม่ใช้สิทธิสำหรับ '${doc.title}' แล้ว"
    }

    fun deleteTaxDocument(doc: TaxDocumentEntity) = viewModelScope.launch(Dispatchers.IO) {
        taxDocVaultManager.deleteLocalFileSafely(doc)
        repo.deleteTaxDocument(doc)
        withContext(Dispatchers.Main) {
            _taxFeedback.value = "ลบเอกสาร '${doc.title}' ออกจากคลังแล้ว"
        }
    }

    fun saveOpenApiSettings(settings: OpenApiSettingsEntity) = viewModelScope.launch {
        repo.saveOpenApiSettings(settings)
        _taxFeedback.value = "บันทึกการตั้งค่า RD Open API แล้ว"
    }

    fun attemptOfficialXmlExport(uri: Uri?) = viewModelScope.launch {
        val yearBe = preferences.value.selectedTaxYearBe
        val summary = taxWorkspaceSummary.value ?: return@launch
        val formType = summary.eligibility.recommendedForm
        if (formType == null) {
            _taxFeedback.value = "ยังไม่สามารถระบุแบบ ภ.ง.ด. ที่เหมาะสมได้ กรุณาตรวจสอบรายรับก่อน"
            return@launch
        }
        val profile = taxProfile.value ?: TaxProfileEntity(taxYearBe = yearBe)
        val identity = taxpayerIdentity.value ?: TaxpayerIdentityEntity(taxYearBe = yearBe)
        val nextVersion = repo.nextFilingVersion(yearBe, formType)
        val canonical = RdXmlPublicSerializer.buildCanonicalReturn(
            taxYearBe = yearBe,
            formType = formType,
            filingVersion = nextVersion,
            profile = profile,
            identity = identity,
            report = summary.report,
            confirmedDeductions = taxDeductions.value
        )
        val response = localXmlProvider.submit(
            TaxSubmissionRequest(
                canonicalReturn = canonical,
                idempotencyKey = "${yearBe}_${formType.name}_v$nextVersion"
            )
        )
        if (!response.accepted) {
            _taxFeedback.value = response.messageTh
            return@launch
        }
        if (uri != null) {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(response.xmlContent.toByteArray(Charsets.UTF_8))
                    } ?: error("ไม่สามารถเปิดไฟล์ XML ปลายทางได้")
                }
            }
            if (written.isFailure) {
                _taxFeedback.value = "บันทึกไฟล์ XML ล้มเหลว: ${written.exceptionOrNull()?.message}"
                return@launch
            }
        }
        val docIdsJson = kotlinx.serialization.json.Json.encodeToString(
            taxDocuments.value.map { it.id }
        )
        repo.saveFilingHistory(
            TaxFilingHistoryEntity(
                taxYearBe = yearBe,
                formType = formType,
                filingVersion = nextVersion,
                xmlFileName = "overseer_${formType.name.lowercase()}_$yearBe.xml",
                xmlSha256 = response.xmlSha256,
                totalAssessableSatang = canonical.totalAssessableIncomeSatang,
                totalDeductionSatang = canonical.expenseDeductionSatang + canonical.personalAndFamilyAllowanceSatang + canonical.userConfirmedDeductionsSatang,
                netTaxableSatang = canonical.netTaxableIncomeSatang,
                calculatedTaxSatang = canonical.calculatedTaxSatang,
                withholdingSatang = canonical.withholdingTaxSatang,
                payableSatang = canonical.payableTaxSatang,
                refundSatang = canonical.refundTaxSatang,
                rulesetVersion = canonical.rulesetVersion,
                filingState = response.resultingState,
                submissionRef = response.submissionRef,
                associatedDocumentIdsJson = docIdsJson
            )
        )
        _taxFeedback.value = response.messageTh
    }

    fun exportFilingPreparationPdf(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val yearBe = preferences.value.selectedTaxYearBe
        val summary = taxWorkspaceSummary.value ?: return@launch
        val formType = summary.eligibility.recommendedForm ?: TaxFormType.PND90
        val profile = taxProfile.value ?: TaxProfileEntity(taxYearBe = yearBe)
        val identity = taxpayerIdentity.value ?: TaxpayerIdentityEntity(taxYearBe = yearBe)
        val nextVersion = repo.nextFilingVersion(yearBe, formType)
        val canonical = RdXmlPublicSerializer.buildCanonicalReturn(
            taxYearBe = yearBe,
            formType = formType,
            filingVersion = nextVersion,
            profile = profile,
            identity = identity,
            report = summary.report,
            confirmedDeductions = taxDeductions.value
        )
        val writeRes = runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                RdXmlPublicSerializer.generatePreparationSummaryPdf(out, canonical)
            } ?: error("ไม่สามารถเปิดไฟล์ PDF ปลายทางได้")
        }
        if (writeRes.isSuccess) {
            val docIdsJson = kotlinx.serialization.json.Json.encodeToString(
                taxDocuments.value.map { it.id }
            )
            repo.saveFilingHistory(
                TaxFilingHistoryEntity(
                    taxYearBe = yearBe,
                    formType = formType,
                    filingVersion = nextVersion,
                    xmlFileName = "summary_${formType.name.lowercase()}_${yearBe}_v$nextVersion.pdf",
                    totalAssessableSatang = canonical.totalAssessableIncomeSatang,
                    totalDeductionSatang = canonical.expenseDeductionSatang + canonical.personalAndFamilyAllowanceSatang + canonical.userConfirmedDeductionsSatang,
                    netTaxableSatang = canonical.netTaxableIncomeSatang,
                    calculatedTaxSatang = canonical.calculatedTaxSatang,
                    withholdingSatang = canonical.withholdingTaxSatang,
                    payableSatang = canonical.payableTaxSatang,
                    refundSatang = canonical.refundTaxSatang,
                    rulesetVersion = canonical.rulesetVersion,
                    filingState = if (summary.readyForManualSummaryPreparation) TaxFilingState.READY else TaxFilingState.DRAFT,
                    associatedDocumentIdsJson = docIdsJson,
                    notes = "สร้างเอกสารสรุปเตรียมกรอก e-Filing (ออฟไลน์)"
                )
            )
        }
        withContext(Dispatchers.Main) {
            _taxFeedback.value = writeRes.fold(
                onSuccess = { "บันทึก PDF เตรียมยื่นแบบ v$nextVersion สำเร็จและบันทึกลงประวัติแล้ว" },
                onFailure = { "ส่งออก PDF ล้มเหลว: ${it.message}" }
            )
        }
    }

    fun updateFilingHistoryState(
        entry: TaxFilingHistoryEntity,
        newState: TaxFilingState,
        submissionRef: String = entry.submissionRef,
        confirmationRef: String = entry.confirmationRef,
        notes: String = entry.notes
    ) = viewModelScope.launch {
        repo.updateFilingState(entry, newState, submissionRef, confirmationRef, notes)
        _taxFeedback.value = "อัปเดตสถานะการยื่นแบบเป็น: ${newState.labelTh}"
    }

    fun clearTaxFeedback() {
        _taxFeedback.value = null
    }

    // --- Preferences ---
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { prefsRepo.setThemeMode(mode) }
    fun setDateFormat(format: DateDisplayFormat) = viewModelScope.launch { prefsRepo.setDateFormat(format) }
    fun setLanguage(lang: String) = viewModelScope.launch { prefsRepo.setLanguage(lang) }
    fun setTaxYearBe(yearBe: Int) = viewModelScope.launch { prefsRepo.setTaxYearBe(yearBe) }
    fun setNotificationsEnabled(enabled: Boolean) = viewModelScope.launch { prefsRepo.setNotificationsEnabled(enabled) }
    fun setAutoScanConfig(enabled: Boolean, intervalMinutes: Int) = viewModelScope.launch {
        prefsRepo.setAutoScanEnabled(enabled)
        prefsRepo.setScanIntervalMinutes(intervalMinutes)
        if (enabled) KPlusScanWorker.schedulePeriodic(getApplication(), intervalMinutes)
        else KPlusScanWorker.cancelPeriodic(getApplication())
    }

    // --- Reports Export ---
    fun exportCsvToUri(uri: Uri, txList: List<TransactionEntity>, onDone: (String) -> Unit) =
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    ReportExporter.writeTransactionsCsv(
                        outputStream = out,
                        transactions = txList,
                        accounts = accounts.value.associateBy { it.id },
                        categories = categories.value.associateBy { it.id },
                        dateFormat = preferences.value.dateFormat
                    )
                } ?: error("ไม่สามารถเปิดไฟล์ปลายทางได้")
            }
            withContext(Dispatchers.Main) {
                onDone(res.fold({ "ส่งออกไฟล์ CSV สำเร็จ" }, { "ส่งออกล้มเหลว: ${it.message}" }))
            }
        }

    fun exportPdfToUri(
        uri: Uri,
        reportType: ReportType,
        periodLabel: String,
        txList: List<TransactionEntity>,
        onDone: (String) -> Unit
    ) = viewModelScope.launch(Dispatchers.IO) {
        val res = runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                ReportExporter.writePdfReport(
                    context = getApplication(),
                    outputStream = out,
                    reportType = reportType,
                    periodLabel = periodLabel,
                    transactions = txList,
                    debts = debts.value,
                    taxReport = taxReport.value,
                    accounts = accounts.value.associateBy { it.id },
                    categories = categories.value.associateBy { it.id },
                    dateFormat = preferences.value.dateFormat
                )
            } ?: error("ไม่สามารถเปิดไฟล์ปลายทางได้")
        }
        withContext(Dispatchers.Main) {
            onDone(res.fold({ "ส่งออกรายงาน PDF สำเร็จ" }, { "ส่งออกล้มเหลว: ${it.message}" }))
        }
    }

    // --- Backup Center ---
    fun exportEfBackup(uri: Uri, fileName: String, password: String) = viewModelScope.launch {
        _backupUiState.value = _backupUiState.value.copy(isBusy = true, statusBanner = null, errorBanner = null)
        val result = withContext(Dispatchers.IO) {
            val out = getApplication<Application>().contentResolver.openOutputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("ไม่สามารถเปิดไฟล์ปลายทางเพื่อบันทึกได้"))
            out.use { backupManager.exportEncryptedEfBackup(it, fileName, password) }
        }
        _backupUiState.value = result.fold(
            onSuccess = {
                _backupUiState.value.copy(
                    isBusy = false,
                    statusBanner = "สำรองข้อมูลเข้ารหัส .efbackup สำเร็จ (${it.transactionCount} รายการ, ${it.imageCount} ภาพ)"
                )
            },
            onFailure = {
                _backupUiState.value.copy(
                    isBusy = false,
                    errorBanner = "สำรองข้อมูลล้มเหลว: ${it.message}"
                )
            }
        )
    }

    fun exportSqliteBackup(uri: Uri, fileName: String) = viewModelScope.launch {
        _backupUiState.value = _backupUiState.value.copy(isBusy = true, statusBanner = null, errorBanner = null)
        val result = withContext(Dispatchers.IO) {
            val out = getApplication<Application>().contentResolver.openOutputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("ไม่สามารถเปิดไฟล์ปลายทางเพื่อบันทึกได้"))
            out.use { backupManager.exportPortableSqlite(it, fileName) }
        }
        _backupUiState.value = result.fold(
            onSuccess = {
                _backupUiState.value.copy(
                    isBusy = false,
                    statusBanner = "ส่งออก SQLite พกพาสำเร็จ (${it.transactionCount} รายการ, ${it.imageCount} ภาพ BLOB)"
                )
            },
            onFailure = {
                _backupUiState.value.copy(
                    isBusy = false,
                    errorBanner = "ส่งออก SQLite ล้มเหลว: ${it.message}"
                )
            }
        )
    }

    fun inspectBackupForRestoreOrVerify(uri: Uri, password: String, isSqlite: Boolean, verifyOnly: Boolean = false) =
        viewModelScope.launch {
            _backupUiState.value = _backupUiState.value.copy(isBusy = true, statusBanner = null, errorBanner = null)
            val result = withContext(Dispatchers.IO) {
                val input = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?: return@withContext Result.failure(IllegalStateException("ไม่สามารถอ่านไฟล์ที่เลือกได้"))
                input.use {
                    if (isSqlite) backupManager.verifyAndPreviewSqlite(it)
                    else backupManager.verifyAndPreviewEfBackup(it, password)
                }
            }
            _backupUiState.value = result.fold(
                onSuccess = { preview ->
                    if (verifyOnly) {
                        _backupUiState.value.copy(
                            isBusy = false,
                            statusBanner = "ตรวจสอบความสมบูรณ์ไฟล์ผ่านครบถ้วน: ${preview.transactionCount} รายการ • ${preview.imageCount} ภาพ",
                            pendingPreview = preview,
                            pendingRestoreUri = null
                        )
                    } else {
                        _backupUiState.value.copy(
                            isBusy = false,
                            pendingPreview = preview,
                            pendingRestoreUri = uri,
                            pendingRestorePassword = password,
                            pendingRestoreIsSqlite = isSqlite
                        )
                    }
                },
                onFailure = { err ->
                    _backupUiState.value.copy(
                        isBusy = false,
                        pendingPreview = null,
                        errorBanner = err.message ?: "ตรวจสอบไฟล์สำรองข้อมูลไม่ผ่าน"
                    )
                }
            )
        }

    fun confirmFullRestore() = viewModelScope.launch {
        val state = _backupUiState.value
        val uri = state.pendingRestoreUri ?: return@launch
        _backupUiState.value = state.copy(isBusy = true, statusBanner = null, errorBanner = null)

        val result = withContext(Dispatchers.IO) {
            val input = getApplication<Application>().contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("ไม่สามารถเปิดอ่านไฟล์สำรองข้อมูลได้"))
            input.use {
                if (state.pendingRestoreIsSqlite) backupManager.restoreSqliteFullReplace(it)
                else backupManager.restoreEfBackupFullReplace(it, state.pendingRestorePassword)
            }
        }

        _backupUiState.value = result.fold(
            onSuccess = { preview ->
                _backupUiState.value.copy(
                    isBusy = false,
                    pendingPreview = null,
                    pendingRestoreUri = null,
                    pendingRestorePassword = "",
                    showReconnectKPlusBanner = true,
                    statusBanner = "กู้คืนข้อมูลแบบ Full Restore สำเร็จ (${preview.transactionCount} รายการ, ${preview.imageCount} ภาพ)"
                )
            },
            onFailure = { err ->
                _backupUiState.value.copy(
                    isBusy = false,
                    errorBanner = err.message ?: "การกู้คืนข้อมูลล้มเหลว"
                )
            }
        )
    }

    fun dismissBackupPreview() {
        _backupUiState.value = _backupUiState.value.copy(
            pendingPreview = null,
            pendingRestoreUri = null,
            pendingRestorePassword = ""
        )
    }

    fun clearBackupMessages() {
        _backupUiState.value = _backupUiState.value.copy(statusBanner = null, errorBanner = null)
    }

    fun totalVaultStorageBytes(): Long {
        val imgBytes = scannerPipeline.vaultImagesDir().takeIf { it.exists() }
            ?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        val taxDocBytes = taxDocVaultManager.vaultDocsDir().takeIf { it.exists() }
            ?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        return imgBytes + taxDocBytes
    }

    fun databaseSizeBytes(): Long {
        val dbFile = getApplication<Application>().getDatabasePath(AppDatabase.DB_NAME)
        return if (dbFile.exists()) dbFile.length() else 0L
    }
}
