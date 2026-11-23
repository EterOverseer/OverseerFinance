package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.local.BackupHistoryEntity
import com.example.data.local.BackupStatus
import com.example.data.local.DatabaseMigrations
import com.example.data.local.DateDisplayFormat
import com.example.data.local.OcrModelStatus
import com.example.data.local.ScanSourceEntity
import com.example.data.local.ThemeMode
import com.example.data.preferences.UserPreferences
import com.example.domain.OcrModelDiagnostics
import com.example.domain.OcrModelSlot
import com.example.domain.PaddleOcrEngine
import com.example.domain.ThaiDateFormatter
import com.example.notifications.ReminderScheduler
import com.example.ui.components.OverseerCard
import com.example.ui.components.SectionHeaderRow
import com.example.ui.components.StatusBadge
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeEmerald
import com.example.ui.theme.SpacingTokens
import com.example.ui.theme.TransferCyan
import com.example.ui.theme.WarningAmber
import com.example.ui.viewmodel.BackupCenterUiState

@Composable
fun BackupCenterScreen(
    uiState: BackupCenterUiState,
    backupHistory: List<BackupHistoryEntity>,
    dateFormat: DateDisplayFormat,
    onBack: () -> Unit,
    onExportEfBackup: (Uri, String, String) -> Unit,
    onExportSqlite: (Uri, String) -> Unit,
    onInspectFile: (Uri, String, Boolean, Boolean) -> Unit,
    onConfirmFullRestore: () -> Unit,
    onDismissPreview: () -> Unit,
    onReconnectKPlus: () -> Unit
) {
    var showCreateEfPasswordDialog by remember { mutableStateOf(false) }
    var efExportPassword by remember { mutableStateOf("") }
    var showSqliteWarningDialog by remember { mutableStateOf(false) }
    var showImportPasswordDialog by remember { mutableStateOf<Pair<Uri, Boolean>?>(null) } // uri, verifyOnly

    val createEfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null && efExportPassword.length >= 6) {
            onExportEfBackup(uri, "overseer_backup_${System.currentTimeMillis()}.efbackup", efExportPassword)
            efExportPassword = ""
        }
    }

    val createSqliteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-sqlite3")
    ) { uri ->
        if (uri != null) {
            onExportSqlite(uri, "overseer_portable_${System.currentTimeMillis()}.sqlite")
        }
    }

    val openEfForRestoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) showImportPasswordDialog = uri to false
    }

    val openEfForVerifyLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) showImportPasswordDialog = uri to true
    }

    val openSqliteForRestoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onInspectFile(uri, "", true, false)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(SpacingTokens.lg),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.md)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← กลับ") }
                Text("ศูนย์สำรองและกู้คืนข้อมูล", style = MaterialTheme.typography.titleLarge)
            }
        }

        if (uiState.isBusy) {
            item {
                OverseerCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("กำลังประมวลผลและตรวจสอบความสมบูรณ์ SHA-256...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        uiState.statusBanner?.let { msg ->
            item {
                OverseerCard(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = IncomeEmerald)
                        Spacer(Modifier.width(10.dp))
                        Text(msg, style = MaterialTheme.typography.bodyMedium, color = IncomeEmerald)
                    }
                }
            }
        }

        uiState.errorBanner?.let { err ->
            item {
                OverseerCard(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = ExpenseCoral)
                        Spacer(Modifier.width(10.dp))
                        Text(err, style = MaterialTheme.typography.bodyMedium, color = ExpenseCoral)
                    }
                }
            }
        }

        if (uiState.showReconnectKPlusBanner) {
            item {
                OverseerCard(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
                    onClick = onReconnectKPlus
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = WarningAmber)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("กู้คืนสำเร็จแล้ว — แนะนำให้เลือกโฟลเดอร์ K PLUS ใหม่", style = MaterialTheme.typography.titleSmall, color = WarningAmber)
                            Text("สิทธิ์โฟลเดอร์ SAF ผูกเฉพาะเครื่องเดิม ข้อมูลและภาพทั้งหมดถูกกู้คืนเรียบร้อยแล้ว", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // Export Section
        item {
            OverseerCard {
                SectionHeaderRow(
                    title = "สำรองข้อมูลออก (Export)",
                    subtitle = "รวมฐานข้อมูล ภาพสลิปต้นฉบับ การตั้งค่า และกฎภาษีทั้งหมดไว้ในไฟล์เดียว"
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { showCreateEfPasswordDialog = true },
                    enabled = !uiState.isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("export_efbackup_button")
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("ส่งออก Full Recovery (.efbackup เข้ารหัส AES-256-GCM)")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showSqliteWarningDialog = true },
                    enabled = !uiState.isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("export_sqlite_button")
                ) {
                    Icon(Icons.Rounded.Storage, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("ส่งออก SQLite พกพา (.sqlite พร้อมภาพ BLOB)")
                }
            }
        }

        // Import & Verify Section
        item {
            OverseerCard {
                SectionHeaderRow(
                    title = "กู้คืนและตรวจสอบไฟล์ (Import & Verify)",
                    subtitle = "ค่าเริ่มต้น: Full Restore แทนที่ข้อมูลปัจจุบันทั้งหมดแบบอะตอมมิกพร้อม Rollback"
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { openEfForRestoreLauncher.launch(arrayOf("*/*")) },
                    enabled = !uiState.isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Restore, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("นำเข้าไฟล์ .efbackup (Full Restore)")
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { openSqliteForRestoreLauncher.launch(arrayOf("*/*")) },
                        enabled = !uiState.isBusy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("นำเข้า .sqlite")
                    }
                    OutlinedButton(
                        onClick = { openEfForVerifyLauncher.launch(arrayOf("*/*")) },
                        enabled = !uiState.isBusy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Verified, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("ตรวจไฟล์")
                    }
                }
            }
        }

        // Backup History
        item {
            Text("ประวัติการสำรองข้อมูล (${backupHistory.size})", style = MaterialTheme.typography.titleMedium)
        }
        items(backupHistory, key = { it.id }) { item ->
            OverseerCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.fileName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${item.format.labelTh} • ${item.transactionCount} รายการ • ${item.imageCount} ภาพ • ${item.fileSizeBytes / 1024} KB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            ThaiDateFormatter.formatDateTime(item.createdAtEpochMillis, dateFormat),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusBadge(
                        item.status.labelTh,
                        when (item.status) {
                            BackupStatus.VERIFIED, BackupStatus.SUCCESS -> IncomeEmerald
                            BackupStatus.FAILED -> ExpenseCoral
                        }
                    )
                }
            }
        }
    }

    // Create .efbackup Password Dialog
    if (showCreateEfPasswordDialog) {
        var pw by remember { mutableStateOf("") }
        var confirmPw by remember { mutableStateOf("") }
        var pwErr by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showCreateEfPasswordDialog = false },
            title = { Text("ตั้งรหัสผ่านเข้ารหัส .efbackup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "ไฟล์จะถูกเข้ารหัสด้วย AES-256-GCM + PBKDF2-HMAC-SHA256 หากลืมรหัสผ่านนี้จะไม่สามารถกู้คืนข้อมูลได้",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarningAmber
                    )
                    OutlinedTextField(
                        value = pw,
                        onValueChange = { pw = it; pwErr = null },
                        label = { Text("รหัสผ่าน (อย่างน้อย 6 ตัวอักษร)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = confirmPw,
                        onValueChange = { confirmPw = it; pwErr = null },
                        label = { Text("ยืนยันรหัสผ่าน") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    if (pwErr != null) Text(pwErr!!, color = ExpenseCoral, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (pw.length < 6) {
                        pwErr = "รหัสผ่านต้องมีอย่างน้อย 6 ตัวอักษร"
                        return@Button
                    }
                    if (pw != confirmPw) {
                        pwErr = "รหัสผ่านทั้งสองช่องไม่ตรงกัน"
                        return@Button
                    }
                    efExportPassword = pw
                    showCreateEfPasswordDialog = false
                    createEfLauncher.launch("overseer_backup_${System.currentTimeMillis()}.efbackup")
                }) { Text("เลือกที่บันทึก") }
            },
            dismissButton = { TextButton(onClick = { showCreateEfPasswordDialog = false }) { Text("ยกเลิก") } }
        )
    }

    // SQLite unencrypted warning
    if (showSqliteWarningDialog) {
        AlertDialog(
            onDismissRequest = { showSqliteWarningDialog = false },
            title = { Text("คำเตือน: ไฟล์ .sqlite ไม่ได้เข้ารหัส") },
            text = {
                Text("ไฟล์ .sqlite มีข้อมูลการเงินและภาพหลักฐานทั้งหมดในรูปแบบที่เปิดอ่านได้ด้วยเครื่องมือ SQLite ทั่วไป กรุณาเก็บรักษาในที่ปลอดภัย หรือใช้รูปแบบ .efbackup สำหรับการเข้ารหัส")
            },
            confirmButton = {
                Button(onClick = {
                    showSqliteWarningDialog = false
                    createSqliteLauncher.launch("overseer_portable_${System.currentTimeMillis()}.sqlite")
                }) { Text("เข้าใจแล้ว ส่งออก .sqlite") }
            },
            dismissButton = { TextButton(onClick = { showSqliteWarningDialog = false }) { Text("ยกเลิก") } }
        )
    }

    // Password prompt for reading .efbackup
    showImportPasswordDialog?.let { (uri, verifyOnly) ->
        var pw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportPasswordDialog = null },
            title = { Text(if (verifyOnly) "ใส่รหัสผ่านเพื่อตรวจสอบไฟล์" else "ใส่รหัสผ่านไฟล์ .efbackup") },
            text = {
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    label = { Text("รหัสผ่านสำรองข้อมูล") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    showImportPasswordDialog = null
                    onInspectFile(uri, pw, false, verifyOnly)
                }) { Text("ตรวจสอบ") }
            },
            dismissButton = { TextButton(onClick = { showImportPasswordDialog = null }) { Text("ยกเลิก") } }
        )
    }

    // Restore Preview & Destructive Confirmation
    val preview = uiState.pendingPreview
    if (preview != null && uiState.pendingRestoreUri != null) {
        AlertDialog(
            onDismissRequest = onDismissPreview,
            title = { Text("ยืนยัน Full Restore (แทนที่ข้อมูลปัจจุบัน)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "คำเตือน: การกู้คืนแบบ Full Restore จะแทนที่ฐานข้อมูล รูปภาพสลิป การตั้งค่า และประวัติทั้งหมดในเครื่องนี้ด้วยข้อมูลจากไฟล์สำรอง (ไม่นำมารวมกัน)",
                        style = MaterialTheme.typography.bodySmall,
                        color = ExpenseCoral
                    )
                    Text("รูปแบบ: ${preview.format.labelTh}")
                    Text("จำนวนธุรกรรม: ${preview.transactionCount} รายการ")
                    Text("จำนวนภาพหลักฐาน: ${preview.imageCount} ไฟล์")
                    Text("พื้นที่โดยประมาณ: ${(preview.estimatedRequiredBytes / 1024).coerceAtLeast(1)} KB")
                    Text("เวอร์ชัน Schema: v${preview.schemaVersion}")
                    preview.verificationDetails.forEach { d ->
                        Text("✓ $d", style = MaterialTheme.typography.labelSmall, color = IncomeEmerald)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirmFullRestore,
                    colors = ButtonDefaults.buttonColors(containerColor = ExpenseCoral)
                ) { Text("แทนที่ข้อมูลทั้งหมดเดี๋ยวนี้") }
            },
            dismissButton = { TextButton(onClick = onDismissPreview) { Text("ยกเลิก") } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsDiagnosticsScreen(
    prefs: UserPreferences,
    scanSource: ScanSourceEntity?,
    transactionCount: Int,
    storedImagesCount: Int,
    vaultBytes: Long,
    dbBytes: Long,
    ocrStatus: OcrModelStatus,
    ocrDiagnostics: OcrModelDiagnostics,
    pendingOcrCount: Int,
    lastBackupTimeMillis: Long?,
    onBack: () -> Unit,
    onSetTheme: (ThemeMode) -> Unit,
    onSetDateFormat: (DateDisplayFormat) -> Unit,
    onSetLanguage: (String) -> Unit,
    onSetAutoScan: (Boolean, Int) -> Unit,
    onSetNotifications: (Boolean) -> Unit,
    onInstallOcrModels: (List<Uri>, OcrModelSlot?) -> Unit,
    onResetOcrModels: () -> Unit,
    onReprocessPendingOcr: () -> Unit,
    onOpenBackupCenter: () -> Unit
) {
    val context = LocalContext.current
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onSetNotifications(granted)
    }

    var pendingSlot by remember { mutableStateOf<OcrModelSlot?>(null) }
    val multiModelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onInstallOcrModels(uris, null)
        }
    }
    val singleSlotModelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val slot = pendingSlot
        pendingSlot = null
        if (uri != null) {
            onInstallOcrModels(listOf(uri), slot)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 1040.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(SpacingTokens.lg),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBack) { Text("← กลับ", maxLines = 1) }
                    Text(
                        "ตั้งค่าและวินิจฉัยระบบ",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Install / Update Offline OCR Model Card
            item {
                OverseerCard(modifier = Modifier.testTag("ocr_model_settings_card")) {
                    SectionHeaderRow(
                        title = "ติดตั้ง / อัปเดตโมเดล OCR (PP-OCRv5)",
                        subtitle = "ประมวลผลออฟไลน์ในเครื่อง 100% ผ่าน ONNX Runtime",
                        trailing = {
                            StatusBadge(
                                text = if (ocrDiagnostics.status == OcrModelStatus.READY) "พร้อมใช้งาน" else "รอติดตั้งไฟล์",
                                color = if (ocrDiagnostics.status == OcrModelStatus.READY) IncomeEmerald else WarningAmber
                            )
                        }
                    )
                    Spacer(Modifier.height(10.dp))

                    OcrSlotStatusRow(
                        fileName = PaddleOcrEngine.DET_MODEL_FILE,
                        label = OcrModelSlot.DETECTION.labelTh,
                        installed = ocrDiagnostics.hasDetectionModel,
                        source = ocrDiagnostics.detectionSource,
                        sizeBytes = ocrDiagnostics.detectionSizeBytes
                    )
                    Spacer(Modifier.height(6.dp))
                    OcrSlotStatusRow(
                        fileName = PaddleOcrEngine.REC_MODEL_FILE,
                        label = OcrModelSlot.RECOGNITION.labelTh,
                        installed = ocrDiagnostics.hasRecognitionModel,
                        source = ocrDiagnostics.recognitionSource,
                        sizeBytes = ocrDiagnostics.recognitionSizeBytes
                    )
                    Spacer(Modifier.height(6.dp))
                    OcrSlotStatusRow(
                        fileName = PaddleOcrEngine.DICT_FILE,
                        label = OcrModelSlot.DICTIONARY.labelTh,
                        installed = ocrDiagnostics.hasDictionary,
                        source = ocrDiagnostics.dictionarySource,
                        sizeBytes = ocrDiagnostics.dictionarySizeBytes
                    )

                    ocrDiagnostics.lastUpdatedEpochMillis?.let { ts ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "อัปเดตโมเดลล่าสุด: ${ThaiDateFormatter.formatDateTime(ts, prefs.dateFormat)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { multiModelLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("install_update_ocr_model_button")
                    ) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (ocrDiagnostics.status == OcrModelStatus.READY) {
                                "อัปเดตโมเดล OCR (.onnx / th_dict.txt)"
                            } else {
                                "ติดตั้งโมเดล OCR จากไฟล์ในเครื่อง (.onnx)"
                            },
                            maxLines = 1
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                pendingSlot = OcrModelSlot.DETECTION
                                singleSlotModelLauncher.launch(arrayOf("*/*"))
                            }
                        ) {
                            Icon(Icons.Rounded.DocumentScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("เลือก Det (.onnx)", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = {
                                pendingSlot = OcrModelSlot.RECOGNITION
                                singleSlotModelLauncher.launch(arrayOf("*/*"))
                            }
                        ) {
                            Icon(Icons.Rounded.DocumentScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("เลือก Rec (.onnx)", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(PaddleOcrEngine.OFFICIAL_MODEL_GUIDE_URL))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("เปิดหน้าดาวน์โหลดโมเดล", maxLines = 1)
                        }
                    }

                    if (ocrDiagnostics.status == OcrModelStatus.READY && pendingOcrCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onReprocessPendingOcr,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("สแกน OCR ซ้ำกับสลิปรอตรวจ ($pendingOcrCount รายการ)", maxLines = 1)
                        }
                    }

                    if (ocrDiagnostics.hasUserInstalledFiles) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = onResetOcrModels) {
                            Text("ล้างไฟล์โมเดลที่ติดตั้งเอง", color = ExpenseCoral)
                        }
                    }
                }
            }

            item {
                OverseerCard {
                    Text("การแสดงผลและภาษา", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(selected = prefs.themeMode == mode, onClick = { onSetTheme(mode) }, label = { Text(mode.labelTh) })
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        DateDisplayFormat.entries.forEach { fmt ->
                            FilterChip(selected = prefs.dateFormat == fmt, onClick = { onSetDateFormat(fmt) }, label = { Text(fmt.labelTh) })
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(selected = prefs.primaryLanguage == "th", onClick = { onSetLanguage("th") }, label = { Text("ภาษาไทย (หลัก)") })
                        FilterChip(selected = prefs.primaryLanguage == "en", onClick = { onSetLanguage("en") }, label = { Text("English (Secondary)") })
                    }
                }
            }

            item {
                OverseerCard {
                    Text("สแกน K PLUS อัตโนมัติ & การแจ้งเตือนออฟไลน์", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "เปิดสแกนเบื้องหลังด้วย WorkManager",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        )
                        Switch(
                            checked = prefs.autoScanEnabled,
                            onCheckedChange = { onSetAutoScan(it, prefs.scanIntervalMinutes) }
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(15, 30, 60).forEach { mins ->
                            FilterChip(
                                selected = prefs.scanIntervalMinutes == mins,
                                onClick = { onSetAutoScan(prefs.autoScanEnabled, mins) },
                                label = { Text("ทุก $mins นาที") }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "แจ้งเตือนบิลประจำและหนี้สิน",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        )
                        Switch(
                            checked = prefs.notificationsEnabled,
                            onCheckedChange = { enable ->
                                if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    !ReminderScheduler.hasNotificationPermission(context)
                                ) {
                                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    onSetNotifications(enable)
                                }
                            }
                        )
                    }
                }
            }

            item {
                OverseerCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Security, contentDescription = null, tint = IncomeEmerald)
                        Spacer(Modifier.width(8.dp))
                        Text("วินิจฉัยระบบภายใน & ความเป็นส่วนตัว", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("• เวอร์ชันแอป: 1.0.0 (Database Schema v${DatabaseMigrations.CURRENT_SCHEMA_VERSION})", style = MaterialTheme.typography.bodySmall)
                    Text("• สถานะเครือข่าย: ออฟไลน์ 100% (ไม่มีสิทธิ์ INTERNET ใน Manifest)", style = MaterialTheme.typography.bodySmall, color = IncomeEmerald)
                    Text("• เอนจิน OCR: ${ocrStatus.labelTh}", style = MaterialTheme.typography.bodySmall)
                    Text("• จำนวนธุรกรรมทั้งหมด: $transactionCount รายการ", style = MaterialTheme.typography.bodySmall)
                    Text("• ภาพหลักฐานในคลังส่วนตัว: $storedImagesCount ไฟล์ (${vaultBytes / 1024} KB)", style = MaterialTheme.typography.bodySmall)
                    Text("• ขนาดฐานข้อมูล SQLite: ${dbBytes / 1024} KB", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "• สแกนสำเร็จล่าสุด: ${scanSource?.lastSuccessEpochMillis?.let { ThaiDateFormatter.formatDateTime(it, prefs.dateFormat) } ?: "ยังไม่เคยสแกน"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "• สำรองข้อมูลล่าสุด: ${lastBackupTimeMillis?.let { ThaiDateFormatter.formatDateTime(it, prefs.dateFormat) } ?: "ยังไม่เคยสำรองข้อมูล"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onOpenBackupCenter, modifier = Modifier.fillMaxWidth()) {
                        Text("ไปที่ศูนย์สำรองและกู้คืนข้อมูล")
                    }
                }
            }
        }
    }
}

@Composable
private fun OcrSlotStatusRow(
    fileName: String,
    label: String,
    installed: Boolean,
    source: String,
    sizeBytes: Long
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "$fileName • $source${if (sizeBytes > 0L) " (${sizeBytes / 1024} KB)" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        StatusBadge(
            text = if (installed) "ติดตั้งแล้ว" else "ขาดไฟล์",
            color = if (installed) IncomeEmerald else WarningAmber
        )
    }
}
