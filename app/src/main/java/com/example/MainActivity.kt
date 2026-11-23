package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.domain.ReportType
import com.example.ui.screens.AccountsCategoriesScreen
import com.example.ui.screens.BackupCenterScreen
import com.example.ui.screens.BudgetsRecurringScreen
import com.example.ui.screens.BusinessAccountingScreen
import com.example.ui.screens.DebtsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.MoreHubScreen
import com.example.ui.screens.MoreSubScreen
import com.example.ui.screens.ReportsScreen
import com.example.ui.screens.ScanCenterScreen
import com.example.ui.screens.SettingsDiagnosticsScreen
import com.example.ui.screens.StorageCleanerScreen
import com.example.ui.screens.TaxScreen
import com.example.ui.screens.TransactionsScreen
import com.example.ui.theme.OverseerFinanceTheme
import com.example.ui.viewmodel.OverseerViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable object HomeRoute
@Serializable object TransactionsRoute
@Serializable object ScanRoute
@Serializable object TaxRoute
@Serializable object MoreRoute

private data class BottomTabItem(
    val route: Any,
    val label: String,
    val icon: ImageVector,
    val testTag: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OverseerAppRoot()
        }
    }
}

@Composable
fun OverseerAppRoot(viewModel: OverseerViewModel = viewModel()) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val dashboard by viewModel.dashboardState.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val pendingReview by viewModel.pendingReviewTransactions.collectAsStateWithLifecycle()
    val importedImages by viewModel.importedImages.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val ocrResults by viewModel.ocrResults.collectAsStateWithLifecycle()
    val scanSource by viewModel.scanSource.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val scanFeedback by viewModel.scanFeedback.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val budgets by viewModel.budgets.collectAsStateWithLifecycle()
    val recurring by viewModel.recurringTransactions.collectAsStateWithLifecycle()
    val debts by viewModel.debts.collectAsStateWithLifecycle()
    val debtRepayments by viewModel.debtRepayments.collectAsStateWithLifecycle()
    val taxYears by viewModel.taxYears.collectAsStateWithLifecycle()
    val taxProfile by viewModel.taxProfile.collectAsStateWithLifecycle()
    val taxpayerIdentity by viewModel.taxpayerIdentity.collectAsStateWithLifecycle()
    val taxDeductions by viewModel.taxDeductions.collectAsStateWithLifecycle()
    val withholding by viewModel.withholdingRecords.collectAsStateWithLifecycle()
    val taxDocuments by viewModel.taxDocuments.collectAsStateWithLifecycle()
    val statementImportBatches by viewModel.statementImportBatches.collectAsStateWithLifecycle()
    val reconciliationAudits by viewModel.reconciliationAudits.collectAsStateWithLifecycle()
    val taxFilingHistory by viewModel.taxFilingHistory.collectAsStateWithLifecycle()
    val openApiSettings by viewModel.openApiSettings.collectAsStateWithLifecycle()
    val taxReport by viewModel.taxReport.collectAsStateWithLifecycle()
    val taxWorkspaceSummary by viewModel.taxWorkspaceSummary.collectAsStateWithLifecycle()
    val taxFeedback by viewModel.taxFeedback.collectAsStateWithLifecycle()
    val backupUiState by viewModel.backupUiState.collectAsStateWithLifecycle()
    val backupHistory by viewModel.backupHistory.collectAsStateWithLifecycle()

    // Trigger K PLUS folder scan when returning to foreground if auto-scan enabled
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, prefs.autoScanEnabled, scanSource?.treeUri) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && prefs.autoScanEnabled && scanSource != null) {
                viewModel.triggerManualScan()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    OverseerFinanceTheme(themeMode = prefs.themeMode) {
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        var openTxEditorFlag by remember { mutableStateOf(false) }
        var showStorageCleaner by remember { mutableStateOf(false) }
        var moreSubScreen by remember { mutableStateOf(MoreSubScreen.MENU) }

        LaunchedEffect(scanFeedback) {
            if (!scanFeedback.isNullOrBlank()) {
                snackbarHostState.showSnackbar(scanFeedback!!)
                viewModel.clearScanFeedback()
            }
        }

        val tabs = remember {
            listOf(
                BottomTabItem(HomeRoute, "หน้าหลัก", Icons.Rounded.Home, "nav_home"),
                BottomTabItem(TransactionsRoute, "รายการ", Icons.Rounded.ReceiptLong, "nav_transactions"),
                BottomTabItem(ScanRoute, "สแกน", Icons.Rounded.DocumentScanner, "nav_scan"),
                BottomTabItem(TaxRoute, "ภาษี", Icons.Rounded.Calculate, "nav_tax"),
                BottomTabItem(MoreRoute, "เพิ่มเติม", Icons.Rounded.GridView, "nav_more")
            )
        }

        fun navigateToTab(route: Any) {
            showStorageCleaner = false
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 600.dp

            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (!useRail) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            tabs.forEach { tab ->
                                val selected = currentDestination?.hasRoute(tab.route::class) == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateToTab(tab.route) },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label, maxLines = 1) },
                                    modifier = Modifier.testTag(tab.testTag)
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useRail) {
                        NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                            tabs.forEach { tab ->
                                val selected = currentDestination?.hasRoute(tab.route::class) == true
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { navigateToTab(tab.route) },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) },
                                    modifier = Modifier.testTag(tab.testTag)
                                )
                            }
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = HomeRoute,
                        modifier = Modifier.weight(1f)
                    ) {
                        composable<HomeRoute> {
                            HomeScreen(
                                dashboard = dashboard,
                                accounts = accounts,
                                categories = categories,
                                transactions = transactions,
                                recurring = recurring,
                                debts = debts,
                                scanSource = scanSource,
                                taxReport = taxReport,
                                dateFormat = prefs.dateFormat,
                                onSelectPeriod = viewModel::setPeriodFilter,
                                onOpenTransactions = { navigateToTab(TransactionsRoute) },
                                onOpenAddTransaction = {
                                    openTxEditorFlag = true
                                    navigateToTab(TransactionsRoute)
                                },
                                onOpenScanCenter = { navigateToTab(ScanRoute) },
                                onOpenTaxTab = { navigateToTab(TaxRoute) }
                            )
                        }

                        composable<TransactionsRoute> {
                            TransactionsScreen(
                                transactions = transactions,
                                accounts = accounts,
                                categories = categories,
                                attachments = attachments,
                                dateFormat = prefs.dateFormat,
                                openEditorImmediately = openTxEditorFlag,
                                onConsumeOpenEditorFlag = { openTxEditorFlag = false },
                                onSaveTransaction = viewModel::saveTransaction,
                                onDeleteTransaction = viewModel::deleteTransaction,
                                onExportCsv = { uri, list ->
                                    viewModel.exportCsvToUri(uri, list) { msg ->
                                        scope.launch { snackbarHostState.showSnackbar(msg) }
                                    }
                                }
                            )
                        }

                        composable<ScanRoute> {
                            if (showStorageCleaner) {
                                BackHandler { showStorageCleaner = false }
                                StorageCleanerScreen(
                                    importedImages = importedImages,
                                    transactions = transactions,
                                    attachments = attachments,
                                    dateFormat = prefs.dateFormat,
                                    feedbackMessage = scanFeedback,
                                    onBack = { showStorageCleaner = false },
                                    onDeleteOriginals = viewModel::deleteKPlusOriginals
                                )
                            } else {
                                ScanCenterScreen(
                                    scanSource = scanSource,
                                    isScanning = isScanning,
                                    scanFeedback = scanFeedback,
                                    ocrModelStatus = viewModel.ocrModelStatus,
                                    pendingTransactions = pendingReview,
                                    importedImages = importedImages,
                                    ocrResults = ocrResults,
                                    attachments = attachments,
                                    accounts = accounts,
                                    categories = categories,
                                    dateFormat = prefs.dateFormat,
                                    onSelectFolderUri = viewModel::onKPlusFolderSelected,
                                    onScanNow = viewModel::triggerManualScan,
                                    onPickSingleSlip = viewModel::importSinglePickedSlip,
                                    onConfirmReviewTx = { tx -> viewModel.saveTransaction(tx, null) },
                                    onDeleteReviewTx = viewModel::deleteTransaction,
                                    onOpenStorageCleaner = { showStorageCleaner = true }
                                )
                            }
                        }

                        composable<TaxRoute> {
                            TaxScreen(
                                selectedYearBe = prefs.selectedTaxYearBe,
                                taxYears = taxYears,
                                accounts = accounts,
                                transactions = transactions,
                                profile = taxProfile,
                                identity = taxpayerIdentity,
                                deductions = taxDeductions,
                                withholdingRecords = withholding,
                                taxDocuments = taxDocuments,
                                importBatches = statementImportBatches,
                                reconciliationAudits = reconciliationAudits,
                                filingHistory = taxFilingHistory,
                                openApiSettings = openApiSettings,
                                report = taxReport,
                                workspaceSummary = taxWorkspaceSummary,
                                taxFeedback = taxFeedback,
                                dateFormat = prefs.dateFormat,
                                onClearFeedback = viewModel::clearTaxFeedback,
                                onSelectYear = viewModel::setTaxYearBe,
                                onSaveProfile = viewModel::saveTaxProfile,
                                onSaveIdentity = viewModel::saveTaxpayerIdentity,
                                onSaveDeduction = viewModel::saveTaxDeduction,
                                onDeleteDeduction = viewModel::deleteTaxDeduction,
                                onSaveWithholding = viewModel::saveWithholding,
                                onDeleteWithholding = viewModel::deleteWithholding,
                                onImportCsv = viewModel::importBankStatementCsv,
                                onReclassifyTx = viewModel::reclassifyTransactionWithAudit,
                                onImportTaxDoc = { uri, cat, title, notes ->
                                    viewModel.importTaxDocument(uri, cat, title, notes)
                                },
                                onConfirmTaxDoc = viewModel::confirmTaxDocument,
                                onRejectTaxDoc = viewModel::rejectTaxDocument,
                                onDeleteTaxDoc = viewModel::deleteTaxDocument,
                                onSaveOpenApiSettings = viewModel::saveOpenApiSettings,
                                onAttemptOfficialXmlExport = viewModel::attemptOfficialXmlExport,
                                onExportFilingPrepPdf = viewModel::exportFilingPreparationPdf,
                                onUpdateFilingState = { entry, newState, ref ->
                                    viewModel.updateFilingHistoryState(entry, newState, submissionRef = ref)
                                },
                                onExportTaxPdf = { uri ->
                                    viewModel.exportPdfToUri(
                                        uri = uri,
                                        reportType = ReportType.TAX,
                                        periodLabel = "ปีภาษี ${prefs.selectedTaxYearBe}",
                                        txList = transactions
                                    ) { msg ->
                                        scope.launch { snackbarHostState.showSnackbar(msg) }
                                    }
                                }
                            )
                        }

                        composable<MoreRoute> {
                            MoreHubScreen(
                                currentSubScreen = moreSubScreen,
                                onNavigateSub = { moreSubScreen = it },
                                accountsContent = {
                                    AccountsCategoriesScreen(
                                        accounts = accounts,
                                        categories = categories,
                                        rules = rules,
                                        transactions = transactions,
                                        onBack = { moreSubScreen = MoreSubScreen.MENU },
                                        onSaveAccount = viewModel::saveAccount,
                                        onDeleteAccount = viewModel::deleteAccount,
                                        onSaveCategory = viewModel::saveCategory,
                                        onSaveRule = viewModel::saveRule,
                                        onDeleteRule = viewModel::deleteRule
                                    )
                                },
                                debtsContent = {
                                    DebtsScreen(
                                        debts = debts,
                                        repayments = debtRepayments,
                                        accounts = accounts,
                                        dateFormat = prefs.dateFormat,
                                        onBack = { moreSubScreen = MoreSubScreen.MENU },
                                        onSaveDebt = viewModel::saveDebt,
                                        onRecordRepayment = viewModel::recordDebtRepayment,
                                        onDeleteDebt = viewModel::deleteDebt
                                    )
                                },
                                budgetsContent = {
                                    BudgetsRecurringScreen(
                                        budgets = budgets,
                                        recurring = recurring,
                                        categories = categories,
                                        accounts = accounts,
                                        transactions = transactions,
                                        dateFormat = prefs.dateFormat,
                                        onBack = { moreSubScreen = MoreSubScreen.MENU },
                                        onSaveBudget = viewModel::saveBudget,
                                        onDeleteBudget = viewModel::deleteBudget,
                                        onSaveRecurring = viewModel::saveRecurring,
                                        onDeleteRecurring = viewModel::deleteRecurring
                                    )
                                },
                                businessContent = {
                                    BusinessAccountingScreen(
                                        transactions = transactions,
                                        dateFormat = prefs.dateFormat,
                                        onBack = { moreSubScreen = MoreSubScreen.MENU },
                                        onToggleCustomerPaid = { tx -> viewModel.saveTransaction(tx, null) }
                                    )
                                },
                                reportsContent = {
                                    ReportsScreen(
                                        transactions = transactions,
                                        onBack = { moreSubScreen = MoreSubScreen.MENU },
                                        onExportCsv = { uri, list ->
                                            viewModel.exportCsvToUri(uri, list) { msg ->
                                                scope.launch { snackbarHostState.showSnackbar(msg) }
                                            }
                                        },
                                        onExportPdf = { uri, type, label, list ->
                                            viewModel.exportPdfToUri(uri, type, label, list) { msg ->
                                                scope.launch { snackbarHostState.showSnackbar(msg) }
                                            }
                                        }
                                    )
                                },
                                backupContent = {
                                    BackupCenterScreen(
                                        uiState = backupUiState,
                                        backupHistory = backupHistory,
                                        dateFormat = prefs.dateFormat,
                                        onBack = { moreSubScreen = MoreSubScreen.MENU },
                                        onExportEfBackup = viewModel::exportEfBackup,
                                        onExportSqlite = viewModel::exportSqliteBackup,
                                        onInspectFile = viewModel::inspectBackupForRestoreOrVerify,
                                        onConfirmFullRestore = viewModel::confirmFullRestore,
                                        onDismissPreview = viewModel::dismissBackupPreview,
                                        onReconnectKPlus = {
                                            moreSubScreen = MoreSubScreen.MENU
                                            navigateToTab(ScanRoute)
                                        }
                                    )
                                },
                                settingsContent = {
                                    SettingsDiagnosticsScreen(
                                        prefs = prefs,
                                        scanSource = scanSource,
                                        transactionCount = transactions.size,
                                        storedImagesCount = importedImages.count { it.privateFilePath.isNotBlank() },
                                        vaultBytes = viewModel.totalVaultStorageBytes(),
                                        dbBytes = viewModel.databaseSizeBytes(),
                                        ocrStatus = viewModel.ocrModelStatus,
                                        lastBackupTimeMillis = backupHistory.firstOrNull()?.createdAtEpochMillis,
                                        onBack = { moreSubScreen = MoreSubScreen.MENU },
                                        onSetTheme = viewModel::setThemeMode,
                                        onSetDateFormat = viewModel::setDateFormat,
                                        onSetLanguage = viewModel::setLanguage,
                                        onSetAutoScan = viewModel::setAutoScanConfig,
                                        onSetNotifications = viewModel::setNotificationsEnabled,
                                        onOpenBackupCenter = { moreSubScreen = MoreSubScreen.BACKUP_CENTER }
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
