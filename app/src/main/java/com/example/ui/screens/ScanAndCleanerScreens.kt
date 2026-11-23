package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.local.AccountEntity
import com.example.data.local.AttachmentEntity
import com.example.data.local.CategoryEntity
import com.example.data.local.DateDisplayFormat
import com.example.data.local.ImageImportState
import com.example.data.local.ImportedImageEntity
import com.example.data.local.OcrModelStatus
import com.example.data.local.OcrResultEntity
import com.example.data.local.ReviewStatus
import com.example.data.local.ScanSourceEntity
import com.example.data.local.TaxSection
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import com.example.domain.MoneyFormatter
import com.example.domain.ThaiDateFormatter
import com.example.ui.components.DestructiveConfirmDialog
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.OverseerCard
import com.example.ui.components.SectionHeaderRow
import com.example.ui.components.StatusBadge
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeEmerald
import com.example.ui.theme.SpacingTokens
import com.example.ui.theme.TransferCyan
import com.example.ui.theme.WarningAmber
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScanCenterScreen(
    scanSource: ScanSourceEntity?,
    isScanning: Boolean,
    scanFeedback: String?,
    ocrModelStatus: OcrModelStatus,
    pendingTransactions: List<TransactionEntity>,
    importedImages: List<ImportedImageEntity>,
    ocrResults: List<OcrResultEntity>,
    attachments: List<AttachmentEntity>,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    dateFormat: DateDisplayFormat,
    onSelectFolderUri: (Uri) -> Unit,
    onScanNow: () -> Unit,
    onPickSingleSlip: (Uri) -> Unit,
    onConfirmReviewTx: (TransactionEntity) -> Unit,
    onDeleteReviewTx: (TransactionEntity) -> Unit,
    onOpenStorageCleaner: () -> Unit,
    onOpenOcrSettings: () -> Unit = {},
    onReprocessPendingOcr: () -> Unit = {}
) {
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) onSelectFolderUri(uri) }

    val singleSlipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onPickSingleSlip(uri) }

    var reviewingTx by remember { mutableStateOf<TransactionEntity?>(null) }

    val ocrMap = remember(ocrResults) { ocrResults.associateBy { it.importedImageId } }
    val imageMap = remember(importedImages) { importedImages.associateBy { it.id } }
    val attachmentByTx = remember(attachments) { attachments.associateBy { it.transactionId } }

    if (reviewingTx != null) {
        val tx = reviewingTx!!
        val att = attachmentByTx[tx.id]
        val img = att?.importedImageId?.let { imageMap[it] }
        val ocr = img?.id?.let { ocrMap[it] }
        SlipReviewScreen(
            tx = tx,
            image = img,
            ocr = ocr,
            localFilePath = att?.localFilePath ?: img?.privateFilePath.orEmpty(),
            accounts = accounts,
            categories = categories,
            dateFormat = dateFormat,
            onBack = { reviewingTx = null },
            onSaveVerified = { updated ->
                onConfirmReviewTx(updated)
                reviewingTx = null
            },
            onDeleteDraft = {
                onDeleteReviewTx(tx)
                reviewingTx = null
            }
        )
        return
    }

    val verifiedCount = importedImages.count { it.importState == ImageImportState.IMPORTED_VERIFIED }
    val similarCount = importedImages.count { it.importState == ImageImportState.SIMILAR_IMAGE }
    val failedCount = importedImages.count { it.importState == ImageImportState.FAILED }

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
            Text("ศูนย์สแกนสลิป K PLUS & OCR", style = MaterialTheme.typography.headlineSmall)
            Text(
                "ตรวจจับและคัดลอกสลิปเข้าคลังส่วนตัวด้วย SHA-256 + pHash และอ่านข้อความแบบออฟไลน์",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // K PLUS Folder Status Card
        item {
            OverseerCard {
                SectionHeaderRow(
                    title = "โฟลเดอร์ K PLUS (SAF)",
                    subtitle = scanSource?.displayName ?: "ยังไม่ได้เลือกโฟลเดอร์ (แนะนำ Pictures/K Plus)",
                    trailing = {
                        StatusBadge(
                            text = when {
                                scanSource == null -> "ยังไม่เชื่อมต่อ"
                                scanSource.permissionValid -> "เชื่อมต่อแล้ว"
                                else -> "ต้องเลือกใหม่"
                            },
                            color = when {
                                scanSource == null -> WarningAmber
                                scanSource.permissionValid -> IncomeEmerald
                                else -> ExpenseCoral
                            }
                        )
                    }
                )
                Spacer(Modifier.height(8.dp))
                if (scanSource != null) {
                    Text(
                        text = "สถานะล่าสุด: ${scanSource.lastStatus}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    scanSource.lastSuccessEpochMillis?.let { ts ->
                        Text(
                            text = "สแกนสำเร็จล่าสุด: ${ThaiDateFormatter.formatDateTime(ts, dateFormat)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        "Android WorkManager สแกนเบื้องหลังเป็นรอบอัตโนมัติ (ไม่ได้เฝ้าดูโฟลเดอร์แบบเรียลไทม์)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(10.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { folderLauncher.launch(null) },
                        modifier = Modifier.testTag("select_kplus_folder_button")
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (scanSource == null) "เลือกโฟลเดอร์ K PLUS" else "เปลี่ยนโฟลเดอร์", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = onScanNow,
                        enabled = !isScanning && scanSource != null,
                        modifier = Modifier.testTag("scan_now_button")
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("สแกนทันที", maxLines = 1, softWrap = false)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        singleSlipLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.PhotoLibrary, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("นำเข้าภาพสลิปเดี่ยวจากแกลเลอรี", maxLines = 1)
                }

                if (!scanFeedback.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = scanFeedback,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Offline OCR Engine Status Card (honest reporting + direct settings link)
        item {
            OverseerCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.DocumentScanner,
                        contentDescription = null,
                        tint = if (ocrModelStatus == OcrModelStatus.READY) IncomeEmerald else WarningAmber
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("เอนจิน OCR ออฟไลน์ (PaddleOCR PP-OCRv5)", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = ocrModelStatus.labelTh,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ocrModelStatus == OcrModelStatus.READY) IncomeEmerald else WarningAmber
                        )
                    }
                }
                if (ocrModelStatus == OcrModelStatus.MISSING_MODEL_ASSETS) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "หมายเหตุ: พบคลังคำศัพท์ th_dict.txt แล้ว แต่ยังไม่ได้ติดตั้งไฟล์โมเดล .onnx คุณสามารถติดตั้ง/อัปเดตไฟล์โมเดลได้ในหน้าตั้งค่า หรือตรวจสอบสลิปเทียบภาพจริงได้ทันที",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenOcrSettings,
                        modifier = Modifier.testTag("scan_open_ocr_settings_button")
                    ) {
                        Text(
                            if (ocrModelStatus == OcrModelStatus.READY) "จัดการ / อัปเดตโมเดล OCR" else "ติดตั้งโมเดล OCR (ตั้งค่า)",
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    if (ocrModelStatus == OcrModelStatus.READY && pendingTransactions.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onReprocessPendingOcr,
                            enabled = !isScanning
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("อ่าน OCR ซ้ำ (${pendingTransactions.size})", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }

        // Safe Storage Pipeline Summary + Storage Cleaner Button
        item {
            OverseerCard(onClick = onOpenStorageCleaner) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.CleaningServices, contentDescription = null, tint = TransferCyan)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("K Plus Storage Cleaner", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "นำเข้าและตรวจแฮชแล้ว $verifiedCount • ภาพคล้าย $similarCount • ล้มเหลว $failedCount",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    StatusBadge("จัดการต้นฉบับ", TransferCyan)
                }
            }
        }

        // Pending OCR Review Queue
        item {
            SectionHeaderRow(
                title = "สลิปรอตรวจสอบ (${pendingTransactions.size})",
                subtitle = "แตะเพื่อเทียบภาพสลิปต้นฉบับกับข้อมูลที่อ่านได้"
            )
        }

        if (pendingTransactions.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Rounded.CheckCircle,
                    title = "ไม่มีสลิปรอตรวจสอบ",
                    description = "สลิปที่สแกนใหม่จะปรากฏที่นี่เพื่อยืนยันยอดและหมวดหมู่ก่อนลงบัญชีจริง"
                )
            }
        } else {
            items(pendingTransactions, key = { it.id }) { tx ->
                val att = attachmentByTx[tx.id]
                val img = att?.importedImageId?.let { imageMap[it] }
                OverseerCard(onClick = { reviewingTx = tx }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tx.description, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "ยอด: ${if (tx.amountSatang > 0L) MoneyFormatter.formatThb(tx.amountSatang) else "รอระบุ"} • ${ThaiDateFormatter.formatDateTime(tx.timestampEpochMillis, dateFormat)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (img?.importState == ImageImportState.SIMILAR_IMAGE) {
                                Spacer(Modifier.height(4.dp))
                                StatusBadge("พบภาพคล้ายคลึง (pHash) โปรดเช็กซ้ำ", WarningAmber)
                            }
                        }
                        StatusBadge("ตรวจสอบ", WarningAmber)
                    }
                }
            }
        }
        }
    }
}

@Composable
fun SlipReviewScreen(
    tx: TransactionEntity,
    image: ImportedImageEntity?,
    ocr: OcrResultEntity?,
    localFilePath: String,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    dateFormat: DateDisplayFormat,
    onBack: () -> Unit,
    onSaveVerified: (TransactionEntity) -> Unit,
    onDeleteDraft: () -> Unit
) {
    var type by remember { mutableStateOf(tx.type) }
    var amountText by remember {
        mutableStateOf(if (tx.amountSatang > 0L) MoneyFormatter.satangToInputString(tx.amountSatang) else "")
    }
    var description by remember { mutableStateOf(tx.description) }
    var accountId by remember { mutableStateOf(tx.accountId) }
    var categoryId by remember { mutableStateOf(tx.categoryId) }
    var sender by remember { mutableStateOf(tx.senderName) }
    var recipient by remember { mutableStateOf(tx.recipientName) }
    var ref by remember { mutableStateOf(tx.paymentReference) }
    var taxSection by remember { mutableStateOf(tx.taxSection) }
    var isBusiness by remember { mutableStateOf(tx.isBusiness) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    var showAccMenu by remember { mutableStateOf(false) }
    var showCatMenu by remember { mutableStateOf(false) }
    var showTaxMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SpacingTokens.lg),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← กลับ") }
            Text("ตรวจสอบสลิปเทียบต้นฉบับ", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onDeleteDraft) { Text("ลบทิ้ง", color = ExpenseCoral) }
        }

        if (localFilePath.isNotBlank() && File(localFilePath).exists()) {
            OverseerCard {
                Text("สำเนาภาพในคลังส่วนตัว OverseerFinance", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                AsyncImage(
                    model = File(localFilePath),
                    contentDescription = image?.originalFileName ?: "Slip preview",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(MaterialTheme.shapes.small)
                )
                if (image != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "SHA-256: ${image.sha256.take(16)}... • สถานะ: ${image.importState.labelTh}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (ocr != null) {
            OverseerCard {
                Text("ข้อมูลจาก OCR (${ocr.modelStatus.labelTh})", style = MaterialTheme.typography.titleSmall)
                Text(
                    "ความมั่นใจรวม: ${(ocr.overallConfidence * 100).toInt()}% • ยอด ${(ocr.amountConfidence * 100).toInt()}% • วันที่ ${(ocr.dateConfidence * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (ocr.rawText.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(ocr.rawText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        OverseerCard {
            Text("แก้ไขและยืนยันรายการลงบัญชี", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    TransactionType.EXPENSE,
                    TransactionType.INCOME,
                    TransactionType.INTERNAL_TRANSFER,
                    TransactionType.REFUND
                ).forEach { t ->
                    FilterChip(
                        selected = type == t,
                        onClick = { type = t },
                        label = { Text(t.labelTh) }
                    )
                }
            }

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it; errorMsg = null },
                label = { Text("จำนวนเงิน (บาท) *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("คำอธิบายรายการ") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = sender,
                    onValueChange = { sender = it },
                    label = { Text("ผู้โอน") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = recipient,
                    onValueChange = { recipient = it },
                    label = { Text("ผู้รับ") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = ref,
                onValueChange = { ref = it },
                label = { Text("เลขที่อ้างอิง") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(onClick = { showAccMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(accounts.firstOrNull { it.id == accountId }?.name ?: "เลือกบัญชี", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = showAccMenu, onDismissRequest = { showAccMenu = false }) {
                        accounts.forEach { a ->
                            DropdownMenuItem(text = { Text(a.name) }, onClick = { accountId = a.id; showAccMenu = false })
                        }
                    }
                }
                androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(onClick = { showCatMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(categories.firstOrNull { it.id == categoryId }?.nameTh ?: "หมวดหมู่", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = showCatMenu, onDismissRequest = { showCatMenu = false }) {
                        categories.forEach { c ->
                            DropdownMenuItem(text = { Text(c.nameTh) }, onClick = { categoryId = c.id; showCatMenu = false })
                        }
                    }
                }
            }
            androidx.compose.foundation.layout.Box {
                OutlinedButton(onClick = { showTaxMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("มาตราภาษี: ${taxSection.labelTh}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = showTaxMenu, onDismissRequest = { showTaxMenu = false }) {
                    TaxSection.entries.forEach { sec ->
                        DropdownMenuItem(text = { Text(sec.labelTh) }, onClick = { taxSection = sec; showTaxMenu = false })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = isBusiness, onCheckedChange = { isBusiness = it })
                Text("รายการของธุรกิจขนาดย่อม")
            }

            if (errorMsg != null) {
                Text(errorMsg!!, color = ExpenseCoral, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = {
                    val satang = MoneyFormatter.parseThbToSatang(amountText)
                    if (satang == null || satang <= 0L) {
                        errorMsg = "กรุณาระบุจำนวนเงินที่ถูกต้องก่อนยืนยัน"
                        return@Button
                    }
                    onSaveVerified(
                        tx.copy(
                            amountSatang = satang,
                            type = type,
                            description = description.trim().ifEmpty { type.labelTh },
                            accountId = accountId,
                            categoryId = categoryId,
                            senderName = sender.trim(),
                            recipientName = recipient.trim(),
                            paymentReference = ref.trim(),
                            taxSection = taxSection,
                            isBusiness = isBusiness,
                            reviewStatus = ReviewStatus.VERIFIED
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("ยืนยันและบันทึกรายการ")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StorageCleanerScreen(
    importedImages: List<ImportedImageEntity>,
    transactions: List<TransactionEntity>,
    attachments: List<AttachmentEntity>,
    dateFormat: DateDisplayFormat,
    feedbackMessage: String?,
    onBack: () -> Unit,
    onDeleteOriginals: (List<ImportedImageEntity>) -> Unit
) {
    // STRICT SAFETY RULE: Never show deletion options for PENDING or FAILED images.
    val cleanableImages = remember(importedImages) {
        importedImages.filter { img ->
            img.importState == ImageImportState.IMPORTED_VERIFIED &&
                img.sourceDeletedAtEpochMillis == null &&
                img.privateFilePath.isNotBlank() &&
                File(img.privateFilePath).exists()
        }
    }

    val selectedMap = remember { mutableStateMapOf<String, Boolean>() }
    var previewPair by remember { mutableStateOf<Pair<ImportedImageEntity, Boolean>?>(null) } // true = original URI, false = private copy
    var confirmList by remember { mutableStateOf<List<ImportedImageEntity>?>(null) }

    val attByImageId = remember(attachments) { attachments.associateBy { it.importedImageId } }
    val txMap = remember(transactions) { transactions.associateBy { it.id } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(SpacingTokens.lg),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.md)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← กลับ") }
                Text("K Plus Storage Cleaner", style = MaterialTheme.typography.titleLarge)
            }
        }

        item {
            OverseerCard(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Shield, contentDescription = null, tint = IncomeEmerald)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("กฎความปลอดภัยสูงสุด", style = MaterialTheme.typography.titleSmall, color = IncomeEmerald)
                        Text(
                            "• แสดงเฉพาะไฟล์สถานะ IMPORTED_VERIFIED ที่ตรวจ SHA-256 สำเนาครบถ้วนแล้วเท่านั้น\n" +
                                "• การลบที่นี่จะลบเฉพาะไฟล์ต้นฉบับในโฟลเดอร์ K PLUS — สำเนาใน OverseerFinance จะไม่ถูกลบ\n" +
                                "• ไฟล์ PENDING และ FAILED จะไม่ถูกนำมาแสดงในรายการลบเด็ดขาด",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        if (!feedbackMessage.isNullOrBlank()) {
            item {
                OverseerCard {
                    Text(feedbackMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        if (cleanableImages.isNotEmpty()) {
            item {
                val selectedItems = cleanableImages.filter { selectedMap[it.id] == true }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { confirmList = selectedItems },
                        enabled = selectedItems.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = ExpenseCoral),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.DeleteForever, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("ลบที่เลือก (${selectedItems.size})")
                    }
                    OutlinedButton(
                        onClick = { confirmList = cleanableImages },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("ลบต้นฉบับทั้งหมด (${cleanableImages.size})")
                    }
                }
            }
        }

        if (cleanableImages.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Rounded.VerifiedUser,
                    title = "ไม่มีภาพต้นฉบับ K PLUS ที่รอทำความสะอาด",
                    description = "ภาพที่นำเข้าและตรวจสอบ SHA-256 สำเร็จแล้ว (และยังมีต้นฉบับอยู่ในโฟลเดอร์ K PLUS) จะปรากฏที่นี่"
                )
            }
        } else {
            items(cleanableImages, key = { it.id }) { img ->
                val linkedTx = attByImageId[img.id]?.transactionId?.let { txMap[it] }
                val checked = selectedMap[img.id] == true
                OverseerCard {
                    Row(verticalAlignment = Alignment.Top) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { selectedMap[img.id] = it }
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(img.originalFileName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "ขนาด: ${img.fileSizeBytes / 1024} KB • นำเข้า: ${ThaiDateFormatter.formatDate(img.importedAtEpochMillis, dateFormat)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (linkedTx != null) {
                                Text(
                                    "ธุรกรรม: ${ThaiDateFormatter.formatDate(linkedTx.timestampEpochMillis, dateFormat)} • ${MoneyFormatter.formatThb(linkedTx.amountSatang)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            StatusBadge(img.importState.labelTh, IncomeEmerald)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                TextButton(onClick = { previewPair = img to false }) {
                                    Text("ดูสำเนาในแอป", maxLines = 1)
                                }
                                TextButton(onClick = { previewPair = img to true }) {
                                    Text("ดูต้นฉบับ K PLUS", maxLines = 1)
                                }
                                TextButton(onClick = { selectedMap[img.id] = false }) {
                                    Text("เก็บต้นฉบับไว้", maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    previewPair?.let { (img, showOriginal) ->
        AlertDialog(
            onDismissRequest = { previewPair = null },
            title = { Text(if (showOriginal) "ต้นฉบับ: ${img.originalFileName}" else "สำเนา OverseerFinance: ${img.originalFileName}") },
            text = {
                val model: Any = if (showOriginal) Uri.parse(img.sourceUri) else File(img.privateFilePath)
                AsyncImage(
                    model = model,
                    contentDescription = img.originalFileName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = { previewPair = null }) { Text("ปิด") }
            }
        )
    }

    confirmList?.let { list ->
        DestructiveConfirmDialog(
            title = "ลบไฟล์ต้นฉบับในโฟลเดอร์ K PLUS?",
            message = "ระบบจะลบเฉพาะภาพต้นฉบับ ${list.size} ไฟล์ในโฟลเดอร์ K PLUS ที่เลือกไว้ สำเนาในคลัง OverseerFinance จะยังถูกเก็บรักษาไว้อย่างครบถ้วน",
            confirmText = "ลบต้นฉบับ ${list.size} ไฟล์",
            onConfirm = {
                onDeleteOriginals(list)
                list.forEach { selectedMap.remove(it.id) }
                confirmList = null
            },
            onDismiss = { confirmList = null }
        )
    }
}
