package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PendingActions
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.AccountEntity
import com.example.data.local.DateDisplayFormat
import com.example.data.local.DeductionType
import com.example.data.local.DocumentVerificationStatus
import com.example.data.local.OpenApiSettingsEntity
import com.example.data.local.RdOpenApiProviderModel
import com.example.data.local.ReconciliationAuditEntity
import com.example.data.local.ReviewStatus
import com.example.data.local.StatementImportBatchEntity
import com.example.data.local.TaxDocumentCategory
import com.example.data.local.TaxDocumentEntity
import com.example.data.local.TaxFilingHistoryEntity
import com.example.data.local.TaxFilingState
import com.example.data.local.TaxSection
import com.example.data.local.TaxpayerIdentityEntity
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import com.example.domain.MoneyFormatter
import com.example.domain.ThaiDateFormatter
import com.example.tax.api.FutureRdOpenApiProvider
import com.example.tax.engine.AnnualTaxWorkspaceSummary
import com.example.tax.engine.ChecklistStatus
import com.example.tax.engine.ThaiTaxFilingEngine
import com.example.tax.reconciliation.BankCsvColumnMapping
import com.example.tax.reconciliation.BankMappingPreset
import com.example.tax.reconciliation.BankStatementReconciler
import com.example.tax.reconciliation.CsvCharsetOption
import com.example.tax.reconciliation.ReconciliationBucket
import com.example.tax.xml.RdXmlSpecificationRegistry
import com.example.ui.components.OverseerCard
import com.example.ui.components.SectionHeaderRow
import com.example.ui.components.StatusBadge
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeEmerald
import com.example.ui.theme.NumericMediumStyle
import com.example.ui.theme.TransferCyan
import com.example.ui.theme.WarningAmber

@Composable
fun TaxReconciliationSection(
    selectedYearBe: Int,
    accounts: List<AccountEntity>,
    transactions: List<TransactionEntity>,
    importBatches: List<StatementImportBatchEntity>,
    audits: List<ReconciliationAuditEntity>,
    dateFormat: DateDisplayFormat,
    onImportCsv: (Uri, String, String, BankCsvColumnMapping) -> Unit,
    onReclassifyTx: (TransactionEntity, TransactionType, TaxSection, Boolean, ReviewStatus, String) -> Unit
) {
    val context = LocalContext.current
    var selectedPreset by remember { mutableStateOf(BankMappingPreset.KBANK_CSV) }
    var selectedCharset by remember { mutableStateOf(CsvCharsetOption.UTF_8) }
    var selectedAccountId by remember(accounts) {
        mutableStateOf(accounts.firstOrNull()?.id ?: "acc_kbank_main")
    }
    var customDateCol by remember { mutableStateOf("0") }
    var customDescCol by remember { mutableStateOf("1") }
    var customDebitCol by remember { mutableStateOf("2") }
    var customCreditCol by remember { mutableStateOf("3") }
    var customRefCol by remember { mutableStateOf("4") }
    var editingTx by remember { mutableStateOf<TransactionEntity?>(null) }

    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val fileName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "statement_${System.currentTimeMillis()}.csv"
            val baseMapping = BankCsvColumnMapping.forPreset(selectedPreset)
            val mapping = if (selectedPreset == BankMappingPreset.CUSTOM) {
                baseMapping.copy(
                    dateColumn = customDateCol.toIntOrNull() ?: 0,
                    descriptionColumn = customDescCol.toIntOrNull() ?: 1,
                    debitColumn = customDebitCol.toIntOrNull(),
                    creditColumn = customCreditCol.toIntOrNull(),
                    referenceColumn = customRefCol.toIntOrNull(),
                    charset = selectedCharset
                )
            } else {
                baseMapping.copy(charset = selectedCharset)
            }
            onImportCsv(uri, fileName, selectedAccountId, mapping)
        }
    }

    val yearCe = selectedYearBe - 543
    val yearTxs = remember(transactions, yearCe) {
        transactions.filter { ThaiDateFormatter.toBangkokDate(it.timestampEpochMillis).year == yearCe }
    }
    val bucketSummary = remember(transactions, selectedYearBe) {
        BankStatementReconciler.summarizeYearBuckets(transactions, selectedYearBe)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OverseerCard {
            SectionHeaderRow(
                title = "นำเข้าและกระทบยอดรายการเดินบัญชี (CSV)",
                subtitle = "ยอดเงินฝากธนาคารจะยังไม่ถูกนับเป็นรายรับเสียภาษี จนกว่าผู้ใช้จะตรวจสอบและยืนยัน"
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BankMappingPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = selectedPreset == preset,
                        onClick = { selectedPreset = preset },
                        label = { Text(preset.labelTh) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CsvCharsetOption.entries.forEach { cs ->
                    FilterChip(
                        selected = selectedCharset == cs,
                        onClick = { selectedCharset = cs },
                        label = { Text(cs.labelTh) }
                    )
                }
            }
            if (accounts.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    accounts.filter { it.isActive }.forEach { acc ->
                        FilterChip(
                            selected = selectedAccountId == acc.id,
                            onClick = { selectedAccountId = acc.id },
                            label = { Text(acc.name) }
                        )
                    }
                }
            }
            if (selectedPreset == BankMappingPreset.CUSTOM) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = customDateCol, onValueChange = { customDateCol = it }, label = { Text("วันที่ (Col)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = customDescCol, onValueChange = { customDescCol = it }, label = { Text("รายละเอียด") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = customDebitCol, onValueChange = { customDebitCol = it }, label = { Text("ถอน (Col)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = customCreditCol, onValueChange = { customCreditCol = it }, label = { Text("ฝาก (Col)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = customRefCol, onValueChange = { customRefCol = it }, label = { Text("อ้างอิง") }, modifier = Modifier.weight(1f), singleLine = true)
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { csvLauncher.launch(arrayOf("text/*", "text/csv", "application/csv")) },
                modifier = Modifier.fillMaxWidth().testTag("import_csv_button")
            ) {
                Icon(Icons.Rounded.UploadFile, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("เลือกไฟล์ Bank Statement (.csv)")
            }
        }

        // Bucket Summary
        OverseerCard {
            Text("สรุปการจัดกลุ่มรายการปี $selectedYearBe", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            ReconciliationBucket.entries.forEach { bucket ->
                val (cnt, sum) = bucketSummary[bucket] ?: (0 to 0L)
                if (cnt > 0 || bucket == ReconciliationBucket.REQUIRES_REVIEW) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${bucket.labelTh} ($cnt)", style = MaterialTheme.typography.bodySmall)
                        Text(MoneyFormatter.formatThb(sum), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Transactions awaiting or eligible for classification
        OverseerCard {
            SectionHeaderRow(
                title = "รายการในปี $selectedYearBe (${yearTxs.size})",
                subtitle = "แตะรายการเพื่อยืนยันประเภทภาษี (มาตรา 40 / เงินโอน / เงินกู้ / ค่าใช้จ่าย)"
            )
            Spacer(Modifier.height(6.dp))
            if (yearTxs.isEmpty()) {
                Text("ยังไม่มีธุรกรรมในปีภาษี $selectedYearBe", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                yearTxs.take(40).forEach { tx ->
                    val needsReview = tx.reviewStatus != ReviewStatus.VERIFIED || tx.type == TransactionType.OTHER
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tx.description.ifBlank { tx.type.labelTh }, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${ThaiDateFormatter.formatDate(tx.timestampEpochMillis, dateFormat)} • ${tx.type.labelTh} • ${tx.taxSection.code} • ${tx.source.labelTh}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (needsReview) WarningAmber else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(MoneyFormatter.formatThb(tx.amountSatang), style = NumericMediumStyle)
                            TextButton(onClick = { editingTx = tx }) {
                                Text(if (needsReview) "ตรวจสอบ" else "แก้ไข")
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }

        if (importBatches.isNotEmpty()) {
            OverseerCard {
                Text("ประวัติการนำเข้าไฟล์ CSV (${importBatches.size})", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                importBatches.take(10).forEach { b ->
                    Text(
                        "${b.bankName} • ${b.fileName}: นำเข้า ${b.importedCount}/${b.rowCount} (ข้ามซ้ำ ${b.duplicateCount})",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (audits.isNotEmpty()) {
            OverseerCard {
                Text("บันทึก Audit การแก้ไขประเภทภาษี (${audits.size})", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                audits.take(10).forEach { a ->
                    Text(
                        "${ThaiDateFormatter.formatDateTime(a.adjustedAtEpochMillis, dateFormat)}: ${a.previousType.labelTh}(${a.previousTaxSection.code}) → ${a.newType.labelTh}(${a.newTaxSection.code})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    editingTx?.let { tx ->
        var selType by remember(tx.id) { mutableStateOf(tx.type) }
        var selSec by remember(tx.id) { mutableStateOf(tx.taxSection) }
        var isBiz by remember(tx.id) { mutableStateOf(tx.isBusiness) }
        var reason by remember(tx.id) { mutableStateOf("ยืนยันการจัดประเภทภาษี") }

        AlertDialog(
            onDismissRequest = { editingTx = null },
            title = { Text("จัดประเภทภาษี: ${MoneyFormatter.formatThb(tx.amountSatang)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tx.description, style = MaterialTheme.typography.bodySmall)
                    Text("ประเภทธุรกรรม", style = MaterialTheme.typography.labelMedium)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            TransactionType.INCOME,
                            TransactionType.INTERNAL_TRANSFER,
                            TransactionType.LOAN_RECEIVED,
                            TransactionType.REFUND,
                            TransactionType.DEBT_COLLECTION,
                            TransactionType.EXPENSE
                        ).forEach { t ->
                            FilterChip(
                                selected = selType == t,
                                onClick = {
                                    selType = t
                                    if (t != TransactionType.INCOME) selSec = TaxSection.NON_TAXABLE
                                },
                                label = { Text(t.labelTh) }
                            )
                        }
                    }
                    if (selType == TransactionType.INCOME) {
                        Text("มาตราเงินได้พึงประเมิน", style = MaterialTheme.typography.labelMedium)
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TaxSection.entries.filter { it != TaxSection.NONE }.forEach { sec ->
                                FilterChip(
                                    selected = selSec == sec,
                                    onClick = { selSec = sec },
                                    label = { Text(sec.code) }
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("รายการธุรกิจ (Business)", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = isBiz, onCheckedChange = { isBiz = it })
                    }
                    OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text("หมายเหตุ Audit") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    onReclassifyTx(tx, selType, selSec, isBiz, ReviewStatus.VERIFIED, reason)
                    editingTx = null
                }) { Text("ยืนยันและบันทึก") }
            },
            dismissButton = {
                TextButton(onClick = { editingTx = null }) { Text("ยกเลิก") }
            }
        )
    }
}

@Composable
fun TaxDocumentVaultSection(
    selectedYearBe: Int,
    documents: List<TaxDocumentEntity>,
    onImportDocument: (Uri, TaxDocumentCategory, String, String) -> Unit,
    onConfirmDocument: (TaxDocumentEntity, Long?, DeductionType?) -> Unit,
    onRejectDocument: (TaxDocumentEntity) -> Unit,
    onDeleteDocument: (TaxDocumentEntity) -> Unit
) {
    val context = LocalContext.current
    var selectedCategory by remember { mutableStateOf(TaxDocumentCategory.WITHHOLDING_50_TAVI) }
    var docTitle by remember { mutableStateOf("") }
    var docNotes by remember { mutableStateOf("") }
    var confirmingDoc by remember { mutableStateOf<TaxDocumentEntity?>(null) }

    val docPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val pickedName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "doc_${System.currentTimeMillis()}"
            onImportDocument(uri, selectedCategory, docTitle.ifBlank { pickedName }, docNotes)
            docTitle = ""
            docNotes = ""
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OverseerCard {
            SectionHeaderRow(
                title = "คลังเอกสารภาษีส่วนตัว (PDF / รูปภาพ)",
                subtitle = "เก็บเข้ารหัสในเครื่องและรวมอยู่ในไฟล์ Backup • ระบบจะไม่หักลดหย่อนอัตโนมัติจนกว่าคุณจะกดยืนยัน"
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TaxDocumentCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = { Text(cat.labelTh) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = docTitle, onValueChange = { docTitle = it }, label = { Text("ชื่อเอกสาร (ไม่บังคับ)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = docNotes, onValueChange = { docNotes = it }, label = { Text("หมายเหตุเพิ่มเติม") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { docPicker.launch(arrayOf("application/pdf", "image/*")) },
                modifier = Modifier.fillMaxWidth().testTag("upload_tax_doc_button")
            ) {
                Icon(Icons.Rounded.Description, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("นำเข้าไฟล์ PDF หรือรูปภาพเอกสารปี $selectedYearBe")
            }
        }

        OverseerCard {
            Text("เอกสารในคลังปี $selectedYearBe (${documents.size} ฉบับ)", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            if (documents.isEmpty()) {
                Text("ยังไม่มีเอกสารภาษีในปีนี้", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                documents.forEach { doc ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(doc.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(doc.category.labelTh, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            StatusBadge(
                                text = doc.verificationStatus.labelTh,
                                color = when (doc.verificationStatus) {
                                    DocumentVerificationStatus.USER_CONFIRMED -> IncomeEmerald
                                    DocumentVerificationStatus.PENDING_REVIEW -> WarningAmber
                                    DocumentVerificationStatus.REJECTED -> ExpenseCoral
                                }
                            )
                        }
                        if (doc.ocrExtractedText.isNotBlank()) {
                            Text(
                                "OCR/สถานะ: ${doc.ocrExtractedText.take(120)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TransferCyan
                            )
                        }
                        Text(
                            "SHA-256: ${doc.sha256.take(16)}… (${doc.fileSizeBytes / 1024} KB)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (doc.verificationStatus != DocumentVerificationStatus.USER_CONFIRMED) {
                                TextButton(onClick = { confirmingDoc = doc }) { Text("ยืนยันใช้สิทธิ") }
                            }
                            if (doc.verificationStatus != DocumentVerificationStatus.REJECTED) {
                                TextButton(onClick = { onRejectDocument(doc) }) { Text("ไม่ใช้สิทธิ") }
                            }
                            IconButton(onClick = { onDeleteDocument(doc) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "ลบเอกสาร", tint = ExpenseCoral)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    confirmingDoc?.let { doc ->
        val defaultDedType = when (doc.category) {
            TaxDocumentCategory.SOCIAL_SECURITY -> DeductionType.SOCIAL_SECURITY
            TaxDocumentCategory.LIFE_INSURANCE -> DeductionType.LIFE_INSURANCE
            TaxDocumentCategory.HEALTH_INSURANCE -> DeductionType.HEALTH_INSURANCE
            TaxDocumentCategory.RETIREMENT_FUND -> DeductionType.RMF
            TaxDocumentCategory.INVESTMENT -> DeductionType.THAI_ESG
            TaxDocumentCategory.DONATION -> DeductionType.DONATION_GENERAL
            TaxDocumentCategory.MORTGAGE_INTEREST -> DeductionType.HOME_LOAN_INTEREST
            else -> null
        }
        var dedType by remember(doc.id) { mutableStateOf(defaultDedType) }
        var amountStr by remember(doc.id) {
            mutableStateOf(doc.extractedAmountSatang?.let { MoneyFormatter.satangToInputString(it) } ?: "")
        }

        AlertDialog(
            onDismissRequest = { confirmingDoc = null },
            title = { Text("ยืนยันเอกสาร: ${doc.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ยืนยันความถูกต้องของเอกสาร หรือสร้างรายการค่าลดหย่อนที่เชื่อมกับเอกสารนี้โดยตรง:", style = MaterialTheme.typography.bodySmall)
                    if (dedType != null) {
                        Text("ประเภทลดหย่อน: ${dedType!!.labelTh}", style = MaterialTheme.typography.labelMedium)
                        OutlinedTextField(
                            value = amountStr,
                            onValueChange = { amountStr = it },
                            label = { Text("ยอดลดหย่อนตามเอกสาร (บาท — เว้นว่างหากยืนยันเฉพาะเอกสาร)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val satang = MoneyFormatter.parseThbToSatang(amountStr)
                    onConfirmDocument(doc, satang, if (satang != null && satang > 0L) dedType else null)
                    confirmingDoc = null
                }) { Text("ยืนยันเอกสาร") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDoc = null }) { Text("ยกเลิก") }
            }
        )
    }
}

@Composable
fun TaxChecklistAndIdentitySection(
    selectedYearBe: Int,
    identity: TaxpayerIdentityEntity?,
    summary: AnnualTaxWorkspaceSummary?,
    onSaveIdentity: (TaxpayerIdentityEntity) -> Unit,
    onJumpSection: (String) -> Unit
) {
    val active = identity ?: TaxpayerIdentityEntity(taxYearBe = selectedYearBe)
    var tin13 by remember(selectedYearBe, active.tin13) { mutableStateOf(active.tin13) }
    var titleTh by remember(selectedYearBe, active.titleTh) { mutableStateOf(active.titleTh) }
    var firstName by remember(selectedYearBe, active.firstNameTh) { mutableStateOf(active.firstNameTh) }
    var lastName by remember(selectedYearBe, active.lastNameTh) { mutableStateOf(active.lastNameTh) }
    var address by remember(selectedYearBe, active.addressLine) { mutableStateOf(active.addressLine) }
    var province by remember(selectedYearBe, active.province) { mutableStateOf(active.province) }
    var postalCode by remember(selectedYearBe, active.postalCode) { mutableStateOf(active.postalCode) }

    val tinValid = tin13.isNotBlank() && ThaiTaxFilingEngine.validateThaiTin13(tin13)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OverseerCard {
            SectionHeaderRow(
                title = "ข้อมูลผู้เสียภาษี (สำหรับเตรียมแบบ / RD XML)",
                subtitle = "ตรวจสอบเลข 13 หลักตามสูตร Checksum กรมสรรพากร"
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = tin13,
                onValueChange = { tin13 = it.filter { ch -> ch.isDigit() }.take(13) },
                label = { Text("เลขประจำตัวผู้เสียภาษี 13 หลัก") },
                supportingText = {
                    if (tin13.isNotBlank()) {
                        Text(
                            if (tinValid) "ผ่านการตรวจสอบ Checksum 13 หลัก" else "เลข 13 หลักไม่ถูกต้องตามสูตร Checksum",
                            color = if (tinValid) IncomeEmerald else ExpenseCoral
                        )
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("tin13_input")
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = titleTh, onValueChange = { titleTh = it }, label = { Text("คำนำหน้า") }, modifier = Modifier.weight(0.7f), singleLine = true)
                OutlinedTextField(value = firstName, onValueChange = { firstName = it }, label = { Text("ชื่อ") }, modifier = Modifier.weight(1.2f), singleLine = true)
                OutlinedTextField(value = lastName, onValueChange = { lastName = it }, label = { Text("นามสกุล") }, modifier = Modifier.weight(1.2f), singleLine = true)
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("ที่อยู่ตามทะเบียน") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = province, onValueChange = { province = it }, label = { Text("จังหวัด") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(value = postalCode, onValueChange = { postalCode = it.filter { ch -> ch.isDigit() }.take(5) }, label = { Text("รหัสไปรษณีย์") }, modifier = Modifier.weight(0.8f), singleLine = true)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    onSaveIdentity(
                        active.copy(
                            taxYearBe = selectedYearBe,
                            tin13 = tin13.trim(),
                            titleTh = titleTh.trim(),
                            firstNameTh = firstName.trim(),
                            lastNameTh = lastName.trim(),
                            addressLine = address.trim(),
                            province = province.trim(),
                            postalCode = postalCode.trim()
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth().testTag("save_identity_button")
            ) {
                Text("บันทึกข้อมูลผู้เสียภาษี")
            }
        }

        if (summary != null) {
            OverseerCard {
                Text("รายการตรวจสอบความพร้อมก่อนยื่นแบบ (Checklist)", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                summary.checklist.forEach { item ->
                    val (icon, tint) = when (item.status) {
                        ChecklistStatus.PASS -> Icons.Rounded.CheckCircle to IncomeEmerald
                        ChecklistStatus.WARN -> Icons.Rounded.WarningAmber to WarningAmber
                        ChecklistStatus.PENDING -> Icons.Rounded.PendingActions to WarningAmber
                        ChecklistStatus.BLOCKED -> Icons.Rounded.ErrorOutline to ExpenseCoral
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(icon, contentDescription = item.status.labelTh, tint = tint)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.titleTh, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(item.detailTh, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (item.status != ChecklistStatus.PASS) {
                            TextButton(onClick = { onJumpSection(item.actionTarget) }) { Text("แก้ไข") }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun TaxXmlAndHandoffSection(
    selectedYearBe: Int,
    summary: AnnualTaxWorkspaceSummary?,
    latestFiling: TaxFilingHistoryEntity?,
    onAttemptOfficialXmlExport: (Uri?) -> Unit,
    onExportPrepPdf: (Uri) -> Unit,
    onMarkManuallySubmitted: (TaxFilingHistoryEntity, String) -> Unit
) {
    val context = LocalContext.current
    val xmlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/xml")
    ) { uri -> if (uri != null) onAttemptOfficialXmlExport(uri) }

    val prepPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> if (uri != null) onExportPrepPdf(uri) }

    var submissionRefInput by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OverseerCard {
            SectionHeaderRow(
                title = "ช่องทาง 1: RD XML Public Export",
                subtitle = RdXmlSpecificationRegistry.RD_XML_PUBLIC_SPEC_URL
            )
            Spacer(Modifier.height(8.dp))
            val specVerified = summary?.readyToGenerateOfficialXml == true &&
                summary.eligibility.recommendedForm != null &&
                RdXmlSpecificationRegistry.getSpecFor(summary.eligibility.recommendedForm, selectedYearBe).isOfficiallyVerified

            StatusBadge(
                text = if (specVerified) "พร้อมสร้าง XML ทางการ" else "ระงับ XML ทางการชั่วคราว (รอโหลด XSD Spec กรมสรรพากร)",
                color = if (specVerified) IncomeEmerald else WarningAmber
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "OverseerFinance ยึดหลักไม่สร้างแท็ก XML ที่ไม่ได้รับการยืนยันจากสเปก XSD ทางการของกรมสรรพากร " +
                    "คุณสามารถตรวจสอบความถูกต้องของข้อมูลและสร้าง PDF สรุปเตรียมกรอก e-Filing ได้ทันที",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        if (specVerified) {
                            xmlLauncher.launch("overseer_tax_$selectedYearBe.xml")
                        } else {
                            onAttemptOfficialXmlExport(null)
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("attempt_xml_export_button")
                ) {
                    Text(if (specVerified) "ส่งออก RD XML" else "ตรวจเงื่อนไข XML")
                }
                Button(
                    onClick = { prepPdfLauncher.launch("overseer_efiling_summary_$selectedYearBe.pdf") },
                    modifier = Modifier.weight(1f).testTag("export_prep_pdf_button")
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("PDF เตรียมยื่น")
                }
            }
        }

        OverseerCard {
            SectionHeaderRow(
                title = "ส่งต่อไปยังระบบ e-Filing กรมสรรพากร",
                subtitle = "เปิดเบราว์เซอร์ภายนอกอย่างปลอดภัย (ไม่เก็บหรืกรอกรหัสผ่านแทนผู้ใช้)"
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(RdXmlSpecificationRegistry.RD_EFILING_PORTAL_URL))
                    runCatching { context.startActivity(intent) }
                },
                modifier = Modifier.fillMaxWidth().testTag("open_efiling_browser_button")
            ) {
                Icon(Icons.Rounded.OpenInBrowser, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("เปิดเว็บไซต์ e-Filing (${RdXmlSpecificationRegistry.RD_EFILING_PORTAL_URL})")
            }

            if (latestFiling != null) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "รายการล่าสุด: ${latestFiling.formType.code} v${latestFiling.filingVersion} (${latestFiling.filingState.labelTh})",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = submissionRefInput,
                    onValueChange = { submissionRefInput = it },
                    label = { Text("หมายเลขอ้างอิงการยื่นแบบจาก e-Filing (ถ้ามี)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { onMarkManuallySubmitted(latestFiling, submissionRefInput.trim()) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("บันทึกว่ายื่นแบบด้วยตนเองแล้ว")
                }
            }
        }
    }
}

@Composable
fun TaxOpenApiSettingsSection(
    settings: OpenApiSettingsEntity,
    onSaveSettings: (OpenApiSettingsEntity) -> Unit
) {
    var enabled by remember(settings) { mutableStateOf(settings.isEnabled) }
    var model by remember(settings) { mutableStateOf(settings.providerModel) }
    var env by remember(settings) { mutableStateOf(settings.environment) }
    var clientId by remember(settings) { mutableStateOf(settings.clientId) }
    var baseUrl by remember(settings) { mutableStateOf(settings.baseUrl) }
    var consented by remember(settings) { mutableStateOf(settings.userConsented) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OverseerCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = TransferCyan)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("ช่องทาง 2: RD Open API Architecture", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "ปิดใช้งานโดยค่าเริ่มต้น — บิลด์หลักของ OverseerFinance ทำงานออฟไลน์ 100% (ไม่มีสิทธิ์ INTERNET)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "อ้างอิงเอกสารทางการ: ${FutureRdOpenApiProvider.RD_OPEN_API_DOC_URL}\nและ ${FutureRdOpenApiProvider.RD_API_DOC_URL}",
                style = MaterialTheme.typography.labelSmall,
                color = TransferCyan
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("เปิดใช้การตั้งค่า RD Open API (สำหรับบิลด์ Connector)", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            Spacer(Modifier.height(6.dp))
            Text("รูปแบบผู้ให้บริการ (Service Provider Model)", style = MaterialTheme.typography.labelMedium)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RdOpenApiProviderModel.entries.forEach { m ->
                    FilterChip(
                        selected = model == m,
                        onClick = { model = m },
                        label = { Text(m.labelTh) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("DISABLED", "SANDBOX", "PRODUCTION").forEach { e ->
                    FilterChip(selected = env == e, onClick = { env = e }, label = { Text(e) })
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = clientId, onValueChange = { clientId = it }, label = { Text("RD Client ID (ไม่เก็บรหัสผ่าน)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("HTTPS Base URL (ต้องขึ้นต้นด้วย https://)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("ความยินยอมจากผู้ใช้ในการส่งข้อมูลภาษี", style = MaterialTheme.typography.bodySmall)
                Switch(checked = consented, onCheckedChange = { consented = it })
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    onSaveSettings(
                        settings.copy(
                            isEnabled = enabled,
                            providerModel = model,
                            environment = env,
                            clientId = clientId.trim(),
                            baseUrl = baseUrl.trim(),
                            userConsented = consented
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("บันทึกการตั้งค่า Open API")
            }
        }
    }
}

@Composable
fun TaxFilingHistorySection(
    history: List<TaxFilingHistoryEntity>,
    dateFormat: DateDisplayFormat,
    onUpdateState: (TaxFilingHistoryEntity, TaxFilingState) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OverseerCard {
            SectionHeaderRow(
                title = "ประวัติการเตรียมและยื่นแบบ (${history.size})",
                subtitle = "เก็บรักษาถาวรในฐานข้อมูลและ Backup • เตือนอัตโนมัติหากข้อมูลเปลี่ยนหลังสร้างไฟล์"
            )
            Spacer(Modifier.height(8.dp))
            if (history.isEmpty()) {
                Text("ยังไม่มีประวัติการสร้างแบบหรือยื่นภาษีในปีที่เลือก", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                history.forEach { entry ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${entry.formType.code} • v${entry.filingVersion}", style = MaterialTheme.typography.titleSmall)
                            StatusBadge(
                                text = entry.filingState.labelTh,
                                color = when (entry.filingState) {
                                    TaxFilingState.SUBMISSION_CONFIRMED -> IncomeEmerald
                                    TaxFilingState.REJECTED -> ExpenseCoral
                                    else -> TransferCyan
                                }
                            )
                        }
                        if (entry.isOutdated) {
                            Spacer(Modifier.height(4.dp))
                            StatusBadge("ข้อมูลการเงินเปลี่ยนแปลงแล้ว — แนะนำสร้างฉบับใหม่", WarningAmber)
                        }
                        Text(
                            "เงินได้ ${MoneyFormatter.formatThb(entry.totalAssessableSatang)} • สุทธิ ${MoneyFormatter.formatThb(entry.netTaxableSatang)} • ภาษี ${MoneyFormatter.formatThb(entry.calculatedTaxSatang)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "สร้างเมื่อ ${ThaiDateFormatter.formatDateTime(entry.generatedAtEpochMillis, dateFormat)} • กฎ ${entry.rulesetVersion}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (entry.submissionRef.isNotBlank()) {
                            Text("Ref: ${entry.submissionRef}", style = MaterialTheme.typography.labelSmall)
                        }
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (entry.filingState == TaxFilingState.MANUALLY_SUBMITTED) {
                                TextButton(onClick = { onUpdateState(entry, TaxFilingState.SUBMISSION_CONFIRMED) }) {
                                    Text("ยืนยันเสร็จสมบูรณ์")
                                }
                            }
                            if (entry.filingState == TaxFilingState.SUBMISSION_CONFIRMED) {
                                TextButton(onClick = { onUpdateState(entry, TaxFilingState.ADDITIONAL_FILING_REQUIRED) }) {
                                    Text("ต้องยื่นเพิ่มเติม")
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
