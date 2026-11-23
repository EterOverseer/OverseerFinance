package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.example.data.local.DeductionType
import com.example.data.local.FilingStatus
import com.example.data.local.TaxDeductionEntity
import com.example.data.local.TaxProfileEntity
import com.example.data.local.TaxSection
import com.example.data.local.TaxYearEntity
import com.example.data.local.WithholdingRecordEntity
import com.example.domain.MoneyFormatter
import com.example.domain.TaxCalculationReport
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

@Composable
fun TaxScreen(
    selectedYearBe: Int,
    taxYears: List<TaxYearEntity>,
    profile: TaxProfileEntity?,
    deductions: List<TaxDeductionEntity>,
    withholdingRecords: List<WithholdingRecordEntity>,
    report: TaxCalculationReport?,
    onSelectYear: (Int) -> Unit,
    onSaveProfile: (TaxProfileEntity) -> Unit,
    onSaveDeduction: (TaxDeductionEntity) -> Unit,
    onDeleteDeduction: (TaxDeductionEntity) -> Unit,
    onSaveWithholding: (WithholdingRecordEntity) -> Unit,
    onDeleteWithholding: (WithholdingRecordEntity) -> Unit,
    onExportTaxPdf: (Uri) -> Unit
) {
    val activeProfile = profile ?: TaxProfileEntity(taxYearBe = selectedYearBe)
    var showAddDeduction by remember { mutableStateOf(false) }
    var showAddWithholding by remember { mutableStateOf(false) }

    val pdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> if (uri != null) onExportTaxPdf(uri) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
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
                    Text("คำนวณภาษีเงินได้บุคคลธรรมดา", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "อ้างอิงขั้นบันไดกรมสรรพากร (แยกกฎตามปีภาษี)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { pdfLauncher.launch("overseer_tax_$selectedYearBe.pdf") }) {
                    Icon(Icons.Rounded.Download, contentDescription = "ส่งออกรายงานภาษี PDF")
                }
            }
        }

        // Year Selector
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
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

        // Tax Estimate Hero Summary
        if (report != null) {
            item {
                OverseerCard {
                    Text("สรุปประมาณการภาษีปี ${report.taxYearBe}", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    if (report.netTaxPayableSatang > 0L) {
                        Text("ประมาณการภาษีที่ต้องชำระเพิ่ม", style = MaterialTheme.typography.bodySmall, color = WarningAmber)
                        Text(
                            MoneyFormatter.formatThb(report.netTaxPayableSatang),
                            style = NumericDisplayStyle,
                            color = WarningAmber,
                            modifier = Modifier.testTag("tax_payable_amount")
                        )
                    } else if (report.netTaxRefundSatang > 0L) {
                        Text("ประมาณการภาษีที่ขอคืนได้", style = MaterialTheme.typography.bodySmall, color = IncomeEmerald)
                        Text(
                            MoneyFormatter.formatThb(report.netTaxRefundSatang),
                            style = NumericDisplayStyle,
                            color = IncomeEmerald,
                            modifier = Modifier.testTag("tax_refund_amount")
                        )
                    } else {
                        Text("ไม่มีภาษีต้องชำระเพิ่ม", style = MaterialTheme.typography.bodySmall, color = IncomeEmerald)
                        Text("฿0.00", style = NumericDisplayStyle, color = IncomeEmerald)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "เงินได้สุทธิ (Net Taxable): ${MoneyFormatter.formatThb(report.netTaxableIncomeSatang)} • ภาษีรวมก่อนหัก ณ ที่จ่าย: ${MoneyFormatter.formatThb(report.grossTaxCalculatedSatang)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (report.usedAlternativeMethod) {
                        Spacer(Modifier.height(4.dp))
                        StatusBadge("เข้าเกณฑ์คำนวณ 0.5% มาตรา 48(2)", WarningAmber)
                    }
                    Text(
                        "กฎภาษี: ${report.ruleVersion} (${report.sourceUrl})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Section 40(1)-40(8) Income Summary + Excluded Non-Taxable
            item {
                OverseerCard {
                    SectionHeaderRow(
                        title = "เงินได้พึงประเมินตามมาตรา 40",
                        subtitle = "เฉพาะรายรับที่จัดประเภทภาษีแล้วเท่านั้น (ไม่เหมารวมเงินโอนเข้าทุกยอด)"
                    )
                    Spacer(Modifier.height(8.dp))
                    TaxSection.entries.filter { it.isTaxableIncome }.forEach { sec ->
                        val amt = report.incomeBySectionSatang[sec] ?: 0L
                        if (amt > 0L) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("มาตรา ${sec.code}", style = MaterialTheme.typography.bodyMedium)
                                Text(MoneyFormatter.formatThb(amt), style = NumericMediumStyle)
                            }
                        }
                    }
                    if (report.totalAssessableIncomeSatang == 0L) {
                        Text(
                            "ยังไม่มีรายรับที่จัดเข้าประเภทมาตรา 40(1)-40(8) ในปี $selectedYearBe",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("รวมเงินได้พึงประเมิน", style = MaterialTheme.typography.titleSmall)
                        Text(MoneyFormatter.formatThb(report.totalAssessableIncomeSatang), style = NumericMediumStyle, color = IncomeEmerald)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("คัดออก (โอนบัญชีตนเอง/กู้/คืนเงิน)", style = MaterialTheme.typography.bodySmall, color = TransferCyan)
                        Text(MoneyFormatter.formatThb(report.nonTaxableExcludedSatang), style = MaterialTheme.typography.bodySmall, color = TransferCyan)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("หักค่าใช้จ่ายตามกฎหมาย", style = MaterialTheme.typography.bodySmall)
                        Text("-${MoneyFormatter.formatThb(report.standardExpenseDeductionSatang)}", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("ลดหย่อนส่วนตัวและครอบครัว", style = MaterialTheme.typography.bodySmall)
                        Text("-${MoneyFormatter.formatThb(report.personalAndFamilyAllowanceSatang)}", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("ลดหย่อนเพิ่มเติมที่ยืนยันแล้ว", style = MaterialTheme.typography.bodySmall)
                        Text("-${MoneyFormatter.formatThb(report.userConfirmedDeductionsSatang)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Filing Status & Family Allowance Editor
        item {
            OverseerCard {
                Text("สถานภาพและค่าลดหย่อนครอบครัว (ยืนยันสิทธิด้วยตนเอง)", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilingStatus.entries.forEach { st ->
                        FilterChip(
                            selected = activeProfile.filingStatus == st,
                            onClick = { onSaveProfile(activeProfile.copy(filingStatus = st)) },
                            label = { Text(st.labelTh) }
                        )
                    }
                }
                if (activeProfile.filingStatus != FilingStatus.SINGLE) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("คู่สมรสไม่มีเงินได้ (ลดหย่อน 60,000 บาท)", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = activeProfile.spouseNoIncome,
                            onCheckedChange = { onSaveProfile(activeProfile.copy(spouseNoIncome = it)) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("จำนวนบุตรที่เข้าเกณฑ์: ${activeProfile.childCount} คน", style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton(onClick = {
                            onSaveProfile(activeProfile.copy(childCount = (activeProfile.childCount - 1).coerceAtLeast(0)))
                        }) { Text("-") }
                        TextButton(onClick = {
                            onSaveProfile(activeProfile.copy(childCount = activeProfile.childCount + 1))
                        }) { Text("+") }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("เลี้ยงดูบิดามารดา (สูงสุด 4 คน): ${activeProfile.parentCareCount} คน", style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton(onClick = {
                            onSaveProfile(activeProfile.copy(parentCareCount = (activeProfile.parentCareCount - 1).coerceAtLeast(0)))
                        }) { Text("-") }
                        TextButton(onClick = {
                            onSaveProfile(activeProfile.copy(parentCareCount = (activeProfile.parentCareCount + 1).coerceAtMost(4)))
                        }) { Text("+") }
                    }
                }
            }
        }

        // User-confirmed Deductions
        item {
            OverseerCard {
                SectionHeaderRow(
                    title = "ค่าลดหย่อนเพิ่มเติม (${deductions.size})",
                    subtitle = "ระบบจะนำมาหักเฉพาะรายการที่คุณติ๊กยืนยันสิทธิ์แล้วเท่านั้น",
                    trailing = {
                        IconButton(onClick = { showAddDeduction = true }) {
                            Icon(Icons.Rounded.Add, contentDescription = "เพิ่มค่าลดหย่อน")
                        }
                    }
                )
                Spacer(Modifier.height(6.dp))
                if (deductions.isEmpty()) {
                    Text(
                        "ยังไม่มีค่าลดหย่อนเพิ่มเติม (เช่น ประกันสังคม ประกันชีวิต RMF/SSF/Thai ESG เงินบริจาค)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    deductions.forEach { d ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = d.isUserConfirmed,
                                onCheckedChange = { onSaveDeduction(d.copy(isUserConfirmed = it)) }
                            )
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

        // Withholding Tax Records
        item {
            OverseerCard {
                SectionHeaderRow(
                    title = "ภาษีหัก ณ ที่จ่าย (${withholdingRecords.size})",
                    subtitle = "บันทึกจากหนังสือรับรอง 50 ทวิ เพื่อคำนวณยอดชำระเพิ่ม/ขอคืน",
                    trailing = {
                        IconButton(onClick = { showAddWithholding = true }) {
                            Icon(Icons.Rounded.Add, contentDescription = "เพิ่มภาษีหัก ณ ที่จ่าย")
                        }
                    }
                )
                Spacer(Modifier.height(6.dp))
                if (withholdingRecords.isEmpty()) {
                    Text(
                        "ยังไม่มีรายการหัก ณ ที่จ่ายในปี $selectedYearBe",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    withholdingRecords.forEach { w ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${w.payerName} (${w.taxSection.code})", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "หักไว้ ${MoneyFormatter.formatThb(w.withheldSatang)} จากฐาน ${MoneyFormatter.formatThb(w.incomeAmountSatang)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onDeleteWithholding(w) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "ลบ", tint = ExpenseCoral)
                            }
                        }
                    }
                }
            }
        }

        // Progressive Bracket Breakdown
        if (report != null) {
            item {
                OverseerCard {
                    Text("ตารางคำนวณภาษีขั้นบันไดแบบส่วนเพิ่ม (Marginal Rates)", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    report.bracketBreakdown.forEach { b ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${b.label} (${b.ratePercent}%)", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "เงินได้ในขั้นนี้: ${MoneyFormatter.formatThb(b.taxableInBracketSatang)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                MoneyFormatter.formatThb(b.taxAmountSatang),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (b.taxAmountSatang > 0L) WarningAmber else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Mandatory Disclaimer
            item {
                OverseerCard(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Info, contentDescription = null, tint = TransferCyan)
                        Spacer(Modifier.width(8.dp))
                        Text(report.disclaimerTh, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (showAddDeduction) {
        var selectedType by remember { mutableStateOf(DeductionType.SOCIAL_SECURITY) }
        var amountText by remember { mutableStateOf("") }
        var showMenu by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAddDeduction = false },
            title = { Text("เพิ่มค่าลดหย่อนปี $selectedYearBe") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        OutlinedButton(onClick = { showMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(selectedType.labelTh)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DeductionType.entries.filter {
                                it != DeductionType.PERSONAL && it != DeductionType.SPOUSE &&
                                    it != DeductionType.CHILD && it != DeductionType.PARENT && it != DeductionType.DISABILITY
                            }.forEach { dt ->
                                DropdownMenuItem(
                                    text = { Text(dt.labelTh) },
                                    onClick = { selectedType = dt; showMenu = false }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("จำนวนเงินที่จ่ายจริง (บาท)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (selectedType.defaultCapSatang > 0L) {
                        Text(
                            "เพดานอ้างอิง: ${MoneyFormatter.formatThb(selectedType.defaultCapSatang)}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val satang = MoneyFormatter.parseThbToSatang(amountText) ?: 0L
                    if (satang > 0L) {
                        onSaveDeduction(
                            TaxDeductionEntity(
                                taxYearBe = selectedYearBe,
                                deductionType = selectedType,
                                label = selectedType.labelTh,
                                amountSatang = satang,
                                maxAllowedSatang = selectedType.defaultCapSatang,
                                isUserConfirmed = true
                            )
                        )
                        showAddDeduction = false
                    }
                }) { Text("ยืนยันและเพิ่ม") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDeduction = false }) { Text("ยกเลิก") }
            }
        )
    }

    if (showAddWithholding) {
        var payer by remember { mutableStateOf("") }
        var incomeText by remember { mutableStateOf("") }
        var withheldText by remember { mutableStateOf("") }
        var ref by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddWithholding = false },
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
                        onSaveWithholding(
                            WithholdingRecordEntity(
                                taxYearBe = selectedYearBe,
                                payerName = payer.trim(),
                                incomeAmountSatang = incSatang,
                                withheldSatang = wSatang,
                                certificateRef = ref.trim()
                            )
                        )
                        showAddWithholding = false
                    }
                }) { Text("บันทึก") }
            },
            dismissButton = {
                TextButton(onClick = { showAddWithholding = false }) { Text("ยกเลิก") }
            }
        )
    }
}
