package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.AccountEntity
import com.example.data.local.DateDisplayFormat
import com.example.data.local.DeductionType
import com.example.data.local.FilingStatus
import com.example.data.local.OpenApiSettingsEntity
import com.example.data.local.ReconciliationAuditEntity
import com.example.data.local.ReviewStatus
import com.example.data.local.StatementImportBatchEntity
import com.example.data.local.TaxDeductionEntity
import com.example.data.local.TaxDocumentCategory
import com.example.data.local.TaxDocumentEntity
import com.example.data.local.TaxFilingHistoryEntity
import com.example.data.local.TaxFilingState
import com.example.data.local.TaxProfileEntity
import com.example.data.local.TaxSection
import com.example.data.local.TaxYearEntity
import com.example.data.local.TaxpayerIdentityEntity
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import com.example.data.local.WithholdingRecordEntity
import com.example.domain.MoneyFormatter
import com.example.domain.TaxCalculationReport
import com.example.tax.engine.AnnualTaxWorkspaceSummary
import com.example.tax.engine.ChecklistStatus
import com.example.tax.reconciliation.BankCsvColumnMapping
import com.example.ui.components.OverseerCard
import com.example.ui.components.SectionHeaderRow
import com.example.ui.components.StatusBadge
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeEmerald
import com.example.ui.theme.NumericDisplayStyle
import com.example.ui.theme.NumericMediumStyle
import com.example.ui.theme.SpacingTokens
import com.example.ui.theme.TransferCyan
import com.example.ui.theme.WarningAmber

enum class TaxCenterTab(val labelTh: String) {
    OVERVIEW("ภาพรวมประจำปี"),
    RECONCILIATION("รายได้ & กระทบยอด"),
    DEDUCTIONS("ค่าลดหย่อน"),
    WITHHOLDING("หัก ณ ที่จ่าย"),
    VAULT("คลังเอกสาร"),
    CALCULATOR("คำนวณภาษี"),
    CHECKLIST("ความพร้อม & ข้อมูลผู้ยื่น"),
    XML_HANDOFF("XML & e-Filing"),
    OPEN_API("RD Open API"),
    HISTORY("ประวัติยื่นแบบ")
}

@Composable
fun TaxScreen(
    selectedYearBe: Int,
    taxYears: List<TaxYearEntity>,
    accounts: List<AccountEntity>,
    transactions: List<TransactionEntity>,
    profile: TaxProfileEntity?,
    identity: TaxpayerIdentityEntity?,
    deductions: List<TaxDeductionEntity>,
    withholdingRecords: List<WithholdingRecordEntity>,
    taxDocuments: List<TaxDocumentEntity>,
    importBatches: List<StatementImportBatchEntity>,
    reconciliationAudits: List<ReconciliationAuditEntity>,
    filingHistory: List<TaxFilingHistoryEntity>,
    openApiSettings: OpenApiSettingsEntity,
    report: TaxCalculationReport?,
    workspaceSummary: AnnualTaxWorkspaceSummary?,
    taxFeedback: String?,
    dateFormat: DateDisplayFormat,
    onClearFeedback: () -> Unit,
    onSelectYear: (Int) -> Unit,
    onSaveProfile: (TaxProfileEntity) -> Unit,
    onSaveIdentity: (TaxpayerIdentityEntity) -> Unit,
    onSaveDeduction: (TaxDeductionEntity) -> Unit,
    onDeleteDeduction: (TaxDeductionEntity) -> Unit,
    onSaveWithholding: (WithholdingRecordEntity) -> Unit,
    onDeleteWithholding: (WithholdingRecordEntity) -> Unit,
    onImportCsv: (Uri, String, String, BankCsvColumnMapping) -> Unit,
    onReclassifyTx: (TransactionEntity, TransactionType, TaxSection, Boolean, ReviewStatus, String) -> Unit,
    onImportTaxDoc: (Uri, TaxDocumentCategory, String, String) -> Unit,
    onConfirmTaxDoc: (TaxDocumentEntity, Long?, DeductionType?) -> Unit,
    onRejectTaxDoc: (TaxDocumentEntity) -> Unit,
    onDeleteTaxDoc: (TaxDocumentEntity) -> Unit,
    onSaveOpenApiSettings: (OpenApiSettingsEntity) -> Unit,
    onAttemptOfficialXmlExport: (Uri?) -> Unit,
    onExportFilingPrepPdf: (Uri) -> Unit,
    onUpdateFilingState: (TaxFilingHistoryEntity, TaxFilingState, String) -> Unit,
    onExportTaxPdf: (Uri) -> Unit
) {
    val activeProfile = profile ?: TaxProfileEntity(taxYearBe = selectedYearBe)
    var currentTab by remember { mutableStateOf(TaxCenterTab.OVERVIEW) }
    var showAddDeduction by remember { mutableStateOf(false) }
    var showAddWithholding by remember { mutableStateOf(false) }

    val pdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> if (uri != null) onExportTaxPdf(uri) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 1040.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(SpacingTokens.lg),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.md)
        ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("ศูนย์ภาษีและการยื่นแบบประจำปี", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Tax Center • ภ.ง.ด.90 / ภ.ง.ด.91 / ภ.ง.ด.94 (ออฟไลน์)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { pdfLauncher.launch("overseer_tax_$selectedYearBe.pdf") }) {
                    Icon(Icons.Rounded.Download, contentDescription = "ส่งออกรายงานภาษี PDF")
                }
            }
        }

        if (!taxFeedback.isNullOrBlank()) {
            item {
                OverseerCard(containerColor = TransferCyan.copy(alpha = 0.14f)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(taxFeedback, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = onClearFeedback) { Text("ปิด") }
                    }
                }
            }
        }

        // Year Selector
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                taxYears.forEach { yr ->
                    FilterChip(
                        selected = yr.taxYearBe == selectedYearBe,
                        onClick = { onSelectYear(yr.taxYearBe) },
                        label = { Text(yr.displayName) }
                    )
                }
            }
        }

        // 10-Module Tax Center Navigation
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TaxCenterTab.entries.forEach { tab ->
                    FilterChip(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        label = { Text(tab.labelTh) },
                        modifier = Modifier.testTag("tax_tab_${tab.name.lowercase()}")
                    )
                }
            }
        }

        when (currentTab) {
            TaxCenterTab.OVERVIEW -> {
                if (report != null && workspaceSummary != null) {
                    item {
                        TaxOverviewSection(
                            selectedYearBe = selectedYearBe,
                            report = report,
                            summary = workspaceSummary,
                            deductionsCount = deductions.size,
                            withholdingCount = withholdingRecords.size,
                            onJumpTab = { currentTab = it }
                        )
                    }
                }
            }

            TaxCenterTab.RECONCILIATION -> {
                item {
                    TaxReconciliationSection(
                        selectedYearBe = selectedYearBe,
                        accounts = accounts,
                        transactions = transactions,
                        importBatches = importBatches,
                        audits = reconciliationAudits,
                        dateFormat = dateFormat,
                        onImportCsv = onImportCsv,
                        onReclassifyTx = onReclassifyTx
                    )
                }
            }

            TaxCenterTab.DEDUCTIONS -> {
                item {
                    TaxDeductionEditorCard(
                        selectedYearBe = selectedYearBe,
                        activeProfile = activeProfile,
                        deductions = deductions,
                        onSaveProfile = onSaveProfile,
                        onSaveDeduction = onSaveDeduction,
                        onDeleteDeduction = onDeleteDeduction,
                        onOpenAddDialog = { showAddDeduction = true }
                    )
                }
            }

            TaxCenterTab.WITHHOLDING -> {
                item {
                    TaxWithholdingCard(
                        selectedYearBe = selectedYearBe,
                        withholdingRecords = withholdingRecords,
                        onOpenAddDialog = { showAddWithholding = true },
                        onDeleteWithholding = onDeleteWithholding
                    )
                }
            }

            TaxCenterTab.VAULT -> {
                item {
                    TaxDocumentVaultSection(
                        selectedYearBe = selectedYearBe,
                        documents = taxDocuments,
                        onImportDocument = onImportTaxDoc,
                        onConfirmDocument = onConfirmTaxDoc,
                        onRejectDocument = onRejectTaxDoc,
                        onDeleteDocument = onDeleteTaxDoc
                    )
                }
            }

            TaxCenterTab.CALCULATOR -> {
                if (report != null) {
                    item { TaxCalculatorBreakdownCard(report = report, summary = workspaceSummary) }
                }
            }

            TaxCenterTab.CHECKLIST -> {
                item {
                    TaxChecklistAndIdentitySection(
                        selectedYearBe = selectedYearBe,
                        identity = identity,
                        summary = workspaceSummary,
                        onSaveIdentity = onSaveIdentity,
                        onJumpSection = { target ->
                            currentTab = when (target) {
                                "reconciliation" -> TaxCenterTab.RECONCILIATION
                                "vault" -> TaxCenterTab.VAULT
                                "calculator" -> TaxCenterTab.CALCULATOR
                                "xml_handoff" -> TaxCenterTab.XML_HANDOFF
                                else -> TaxCenterTab.CHECKLIST
                            }
                        }
                    )
                }
            }

            TaxCenterTab.XML_HANDOFF -> {
                item {
                    TaxXmlAndHandoffSection(
                        selectedYearBe = selectedYearBe,
                        summary = workspaceSummary,
                        latestFiling = filingHistory.firstOrNull(),
                        onAttemptOfficialXmlExport = onAttemptOfficialXmlExport,
                        onExportPrepPdf = onExportFilingPrepPdf,
                        onMarkManuallySubmitted = { entry, ref ->
                            onUpdateFilingState(entry, TaxFilingState.MANUALLY_SUBMITTED, ref)
                        }
                    )
                }
            }

            TaxCenterTab.OPEN_API -> {
                item {
                    TaxOpenApiSettingsSection(
                        settings = openApiSettings,
                        onSaveSettings = onSaveOpenApiSettings
                    )
                }
            }

            TaxCenterTab.HISTORY -> {
                item {
                    TaxFilingHistorySection(
                        history = filingHistory,
                        dateFormat = dateFormat,
                        onUpdateState = { entry, state ->
                            onUpdateFilingState(entry, state, entry.submissionRef)
                        }
                    )
                }
            }
        }
        }
    }

    if (showAddDeduction) {
        AddDeductionDialog(
            selectedYearBe = selectedYearBe,
            onDismiss = { showAddDeduction = false },
            onSave = {
                onSaveDeduction(it)
                showAddDeduction = false
            }
        )
    }

    if (showAddWithholding) {
        AddWithholdingDialog(
            selectedYearBe = selectedYearBe,
            onDismiss = { showAddWithholding = false },
            onSave = {
                onSaveWithholding(it)
                showAddWithholding = false
            }
        )
    }
}

@Composable
private fun TaxOverviewSection(
    selectedYearBe: Int,
    report: TaxCalculationReport,
    summary: AnnualTaxWorkspaceSummary,
    deductionsCount: Int,
    withholdingCount: Int,
    onJumpTab: (TaxCenterTab) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OverseerCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("สรุปการยื่นภาษีปี $selectedYearBe", style = MaterialTheme.typography.titleSmall)
                StatusBadge(
                    text = summary.eligibility.recommendedForm?.code ?: "รอระบุแบบ",
                    color = if (summary.eligibility.recommendedForm != null) TransferCyan else WarningAmber
                )
            }
            Spacer(Modifier.height(6.dp))
            if (report.netTaxPayableSatang > 0L) {
                Text("ประมาณการภาษีที่ต้องชำระเพิ่ม", style = MaterialTheme.typography.bodySmall, color = WarningAmber)
                Text(MoneyFormatter.formatThb(report.netTaxPayableSatang), style = NumericDisplayStyle, color = WarningAmber, modifier = Modifier.testTag("tax_payable_amount"))
            } else if (report.netTaxRefundSatang > 0L) {
                Text("ประมาณการภาษีที่ขอคืนได้", style = MaterialTheme.typography.bodySmall, color = IncomeEmerald)
                Text(MoneyFormatter.formatThb(report.netTaxRefundSatang), style = NumericDisplayStyle, color = IncomeEmerald, modifier = Modifier.testTag("tax_refund_amount"))
            } else {
                Text("ไม่มีภาษีต้องชำระเพิ่ม", style = MaterialTheme.typography.bodySmall, color = IncomeEmerald)
                Text("฿0.00", style = NumericDisplayStyle, color = IncomeEmerald)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "เงินได้พึงประเมินรวม: ${MoneyFormatter.formatThb(report.totalAssessableIncomeSatang)} • เงินได้สุทธิ: ${MoneyFormatter.formatThb(report.netTaxableIncomeSatang)}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "ภาษีที่คำนวณได้: ${MoneyFormatter.formatThb(report.grossTaxCalculatedSatang)} • หัก ณ ที่จ่าย ($withholdingCount): ${MoneyFormatter.formatThb(report.totalWithholdingTaxSatang)}",
                style = MaterialTheme.typography.bodySmall
            )
            if (summary.eligibility.isPnd94HalfYearRequired) {
                Spacer(Modifier.height(6.dp))
                StatusBadge("เข้าเกณฑ์ยื่นครึ่งปี ภ.ง.ด.94 (40(5)-40(8) ม.ค.-มิ.ย.)", WarningAmber)
            }
        }

        // Section 40(1)-40(8) Breakdown
        OverseerCard {
            SectionHeaderRow(
                title = "เงินได้พึงประเมินตามมาตรา 40(1)–40(8)",
                subtitle = "นับเฉพาะรายการที่ยืนยันประเภทแล้วเท่านั้น"
            )
            Spacer(Modifier.height(6.dp))
            TaxSection.entries.filter { it.isTaxableIncome }.forEach { sec ->
                val amt = report.incomeBySectionSatang[sec] ?: 0L
                if (amt > 0L) {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("มาตรา ${sec.code}", style = MaterialTheme.typography.bodyMedium)
                        Text(MoneyFormatter.formatThb(amt), style = NumericMediumStyle)
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("คัดออก (เงินโอนตนเอง/กู้/คืนเงิน)", style = MaterialTheme.typography.bodySmall, color = TransferCyan)
                Text(MoneyFormatter.formatThb(report.nonTaxableExcludedSatang), style = MaterialTheme.typography.bodySmall, color = TransferCyan)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("ค่าใช้จ่ายที่หักได้", style = MaterialTheme.typography.bodySmall)
                Text("-${MoneyFormatter.formatThb(report.standardExpenseDeductionSatang)}", style = MaterialTheme.typography.bodySmall)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("ลดหย่อนทั้งหมด (ส่วนตัว + $deductionsCount รายการ)", style = MaterialTheme.typography.bodySmall)
                Text("-${MoneyFormatter.formatThb(report.personalAndFamilyAllowanceSatang + report.userConfirmedDeductionsSatang)}", style = MaterialTheme.typography.bodySmall)
            }
        }

        // Readiness & Alerts (no fake percentages!)
        OverseerCard {
            Text("ความพร้อมยื่นแบบและสิ่งที่ต้องตรวจสอบ", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            val passed = summary.checklist.count { it.status == ChecklistStatus.PASS }
            Text("ผ่านเกณฑ์แล้ว $passed จาก ${summary.checklist.size} รายการตรวจสอบ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (summary.unclassifiedCount > 0) {
                Text("• มีรายการรอจำแนกประเภท ${summary.unclassifiedCount} รายการ", style = MaterialTheme.typography.bodySmall, color = WarningAmber)
            }
            if (summary.missingDocuments.isNotEmpty()) {
                Text("• ควรเพิ่มเอกสารหลักฐานอีก ${summary.missingDocuments.size} รายการ", style = MaterialTheme.typography.bodySmall, color = WarningAmber)
            }
            Spacer(Modifier.height(8.dp))
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { onJumpTab(TaxCenterTab.RECONCILIATION) }) { Text("ตรวจรายรับ CSV/สลิป", maxLines = 1) }
                OutlinedButton(onClick = { onJumpTab(TaxCenterTab.VAULT) }) { Text("คลังเอกสาร (${summary.missingDocuments.size})", maxLines = 1) }
                Button(onClick = { onJumpTab(TaxCenterTab.XML_HANDOFF) }) { Text("เตรียม XML / e-Filing", maxLines = 1) }
            }
        }
    }
}

@Composable
private fun TaxDeductionEditorCard(
    selectedYearBe: Int,
    activeProfile: TaxProfileEntity,
    deductions: List<TaxDeductionEntity>,
    onSaveProfile: (TaxProfileEntity) -> Unit,
    onSaveDeduction: (TaxDeductionEntity) -> Unit,
    onDeleteDeduction: (TaxDeductionEntity) -> Unit,
    onOpenAddDialog: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OverseerCard {
            Text("สถานภาพและค่าลดหย่อนครอบครัวปี $selectedYearBe", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilingStatus.entries.forEach { st ->
                    FilterChip(
                        selected = activeProfile.filingStatus == st,
                        onClick = { onSaveProfile(activeProfile.copy(filingStatus = st)) },
                        label = { Text(st.labelTh) }
                    )
                }
            }
            if (activeProfile.filingStatus != FilingStatus.SINGLE) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("คู่สมรสไม่มีเงินได้ (60,000 บาท)", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = activeProfile.spouseNoIncome, onCheckedChange = { onSaveProfile(activeProfile.copy(spouseNoIncome = it)) })
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("บุตร: ${activeProfile.childCount} คน", style = MaterialTheme.typography.bodyMedium)
                Row {
                    TextButton(onClick = { onSaveProfile(activeProfile.copy(childCount = (activeProfile.childCount - 1).coerceAtLeast(0))) }) { Text("-") }
                    TextButton(onClick = { onSaveProfile(activeProfile.copy(childCount = activeProfile.childCount + 1)) }) { Text("+") }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("บิดามารดา (สูงสุด 4): ${activeProfile.parentCareCount} คน", style = MaterialTheme.typography.bodyMedium)
                Row {
                    TextButton(onClick = { onSaveProfile(activeProfile.copy(parentCareCount = (activeProfile.parentCareCount - 1).coerceAtLeast(0))) }) { Text("-") }
                    TextButton(onClick = { onSaveProfile(activeProfile.copy(parentCareCount = (activeProfile.parentCareCount + 1).coerceAtMost(4))) }) { Text("+") }
                }
            }
        }

        OverseerCard {
            SectionHeaderRow(
                title = "ค่าลดหย่อนเพิ่มเติม (${deductions.size})",
                subtitle = "หักลดหย่อนเฉพาะรายการที่ผู้ใช้ติ๊กยืนยันสิทธิ์แล้วเท่านั้น",
                trailing = { IconButton(onClick = onOpenAddDialog) { Icon(Icons.Rounded.Add, contentDescription = "เพิ่มค่าลดหย่อน") } }
            )
            if (deductions.isEmpty()) {
                Text("ยังไม่มีรายการค่าลดหย่อนเพิ่มเติม", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                deductions.forEach { d ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = d.isUserConfirmed, onCheckedChange = { onSaveDeduction(d.copy(isUserConfirmed = it)) })
                        Column(modifier = Modifier.weight(1f)) {
                            Text(d.label, style = MaterialTheme.typography.bodyMedium)
                            Text(MoneyFormatter.formatThb(d.amountSatang), style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { onDeleteDeduction(d) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "ลบ", tint = ExpenseCoral)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaxWithholdingCard(
    selectedYearBe: Int,
    withholdingRecords: List<WithholdingRecordEntity>,
    onOpenAddDialog: () -> Unit,
    onDeleteWithholding: (WithholdingRecordEntity) -> Unit
) {
    OverseerCard {
        SectionHeaderRow(
            title = "ภาษีหัก ณ ที่จ่าย (${withholdingRecords.size})",
            subtitle = "หนังสือรับรอง 50 ทวิ ปีภาษี $selectedYearBe",
            trailing = { IconButton(onClick = onOpenAddDialog) { Icon(Icons.Rounded.Add, contentDescription = "เพิ่ม 50 ทวิ") } }
        )
        Spacer(Modifier.height(6.dp))
        if (withholdingRecords.isEmpty()) {
            Text("ยังไม่มีรายการหัก ณ ที่จ่าย", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            withholdingRecords.forEach { w ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${w.payerName} (${w.taxSection.code})", style = MaterialTheme.typography.bodyMedium)
                        Text("หักไว้ ${MoneyFormatter.formatThb(w.withheldSatang)} จากฐาน ${MoneyFormatter.formatThb(w.incomeAmountSatang)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onDeleteWithholding(w) }) { Icon(Icons.Rounded.Delete, contentDescription = "ลบ", tint = ExpenseCoral) }
                }
            }
        }
    }
}

@Composable
private fun TaxCalculatorBreakdownCard(report: TaxCalculationReport, summary: AnnualTaxWorkspaceSummary?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (summary != null) {
            OverseerCard {
                Text("ผลการประเมินแบบและเปรียบเทียบวิธีคำนวณ", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                summary.eligibility.reasonsTh.forEach { r ->
                    Text("• $r", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("วิธีอัตราก้าวหน้า (Progressive)", style = MaterialTheme.typography.bodySmall)
                    Text(MoneyFormatter.formatThb(report.progressiveTaxSatang), style = NumericMediumStyle)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("วิธีเหมา 0.5% มาตรา 48(2)", style = MaterialTheme.typography.bodySmall)
                    Text(MoneyFormatter.formatThb(report.alternativeHalfPercentTaxSatang), style = NumericMediumStyle)
                }
            }
        }
        OverseerCard {
            Text("ตารางขั้นบันไดภาษี (${report.ruleVersion})", style = MaterialTheme.typography.titleSmall)
            Text(report.sourceUrl, style = MaterialTheme.typography.labelSmall, color = TransferCyan)
            Spacer(Modifier.height(8.dp))
            report.bracketBreakdown.forEach { b ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${b.label} (${b.ratePercent}%)", style = MaterialTheme.typography.bodySmall)
                        Text("เงินได้ในขั้นนี้: ${MoneyFormatter.formatThb(b.taxableInBracketSatang)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(MoneyFormatter.formatThb(b.taxAmountSatang), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (b.taxAmountSatang > 0L) WarningAmber else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        OverseerCard(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Info, contentDescription = null, tint = TransferCyan)
                Spacer(Modifier.width(8.dp))
                Text(report.disclaimerTh, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AddDeductionDialog(selectedYearBe: Int, onDismiss: () -> Unit, onSave: (TaxDeductionEntity) -> Unit) {
    var selectedType by remember { mutableStateOf(DeductionType.SOCIAL_SECURITY) }
    var amountText by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("เพิ่มค่าลดหย่อนปี $selectedYearBe") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { showMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(selectedType.labelTh) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DeductionType.entries.filter {
                            it != DeductionType.PERSONAL && it != DeductionType.SPOUSE &&
                                it != DeductionType.CHILD && it != DeductionType.PARENT && it != DeductionType.DISABILITY
                        }.forEach { dt ->
                            DropdownMenuItem(text = { Text(dt.labelTh) }, onClick = { selectedType = dt; showMenu = false })
                        }
                    }
                }
                OutlinedTextField(value = amountText, onValueChange = { amountText = it }, label = { Text("จำนวนเงินที่จ่ายจริง (บาท)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = {
                val satang = MoneyFormatter.parseThbToSatang(amountText) ?: 0L
                if (satang > 0L) {
                    onSave(
                        TaxDeductionEntity(
                            taxYearBe = selectedYearBe,
                            deductionType = selectedType,
                            label = selectedType.labelTh,
                            amountSatang = satang,
                            maxAllowedSatang = selectedType.defaultCapSatang,
                            isUserConfirmed = true
                        )
                    )
                }
            }) { Text("ยืนยันและเพิ่ม") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    )
}

@Composable
private fun AddWithholdingDialog(selectedYearBe: Int, onDismiss: () -> Unit, onSave: (WithholdingRecordEntity) -> Unit) {
    var payer by remember { mutableStateOf("") }
    var incomeText by remember { mutableStateOf("") }
    var withheldText by remember { mutableStateOf("") }
    var ref by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("เพิ่มหนังสือรับรองหัก ณ ที่จ่าย") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = payer, onValueChange = { payer = it }, label = { Text("ผู้จ่ายเงิน / บริษัท") }, singleLine = true)
                OutlinedTextField(value = incomeText, onValueChange = { incomeText = it }, label = { Text("เงินได้ที่ได้รับ (บาท)") }, singleLine = true)
                OutlinedTextField(value = withheldText, onValueChange = { withheldText = it }, label = { Text("ภาษีที่ถูกหักไว้ (บาท)") }, singleLine = true)
                OutlinedTextField(value = ref, onValueChange = { ref = it }, label = { Text("เลขที่เอกสาร (ถ้ามี)") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = {
                val wSatang = MoneyFormatter.parseThbToSatang(withheldText) ?: 0L
                val incSatang = MoneyFormatter.parseThbToSatang(incomeText) ?: 0L
                if (payer.isNotBlank() && wSatang > 0L) {
                    onSave(
                        WithholdingRecordEntity(
                            taxYearBe = selectedYearBe,
                            payerName = payer.trim(),
                            incomeAmountSatang = incSatang,
                            withheldSatang = wSatang,
                            certificateRef = ref.trim()
                        )
                    )
                }
            }) { Text("บันทึก") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    )
}
