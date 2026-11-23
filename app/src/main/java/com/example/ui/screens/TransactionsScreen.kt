package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.local.AccountEntity
import com.example.data.local.AttachmentEntity
import com.example.data.local.CategoryEntity
import com.example.data.local.DateDisplayFormat
import com.example.data.local.ReviewStatus
import com.example.data.local.TaxSection
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionSource
import com.example.data.local.TransactionType
import com.example.domain.MoneyFormatter
import com.example.domain.ThaiDateFormatter
import com.example.ui.components.DestructiveConfirmDialog
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.OverseerCard
import com.example.ui.components.StatusBadge
import com.example.ui.components.categoryIconForKey
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeEmerald
import com.example.ui.theme.NumericMediumStyle
import com.example.ui.theme.SpacingTokens
import com.example.ui.theme.TransferCyan
import com.example.ui.theme.WarningAmber
import java.io.File
import java.util.UUID

@Composable
fun TransactionsScreen(
    transactions: List<TransactionEntity>,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    attachments: List<AttachmentEntity>,
    dateFormat: DateDisplayFormat,
    openEditorImmediately: Boolean = false,
    onConsumeOpenEditorFlag: () -> Unit = {},
    onSaveTransaction: (TransactionEntity, Uri?) -> Unit,
    onDeleteTransaction: (TransactionEntity) -> Unit,
    onExportCsv: (Uri, List<TransactionEntity>) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf<TransactionType?>(null) }
    var selectedAccountId by remember { mutableStateOf<String?>(null) }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var minAmountText by remember { mutableStateOf("") }
    var maxAmountText by remember { mutableStateOf("") }
    var showAdvancedFilters by remember { mutableStateOf(false) }

    var editingTx by remember { mutableStateOf<TransactionEntity?>(null) }
    var showEditor by remember(openEditorImmediately) {
        if (openEditorImmediately) onConsumeOpenEditorFlag()
        mutableStateOf(openEditorImmediately)
    }
    var detailTx by remember { mutableStateOf<TransactionEntity?>(null) }
    var pendingDeleteTx by remember { mutableStateOf<TransactionEntity?>(null) }

    val accountMap = remember(accounts) { accounts.associateBy { it.id } }
    val categoryMap = remember(categories) { categories.associateBy { it.id } }
    val attachmentByTx = remember(attachments) { attachments.groupBy { it.transactionId } }

    val filtered = remember(
        transactions,
        searchQuery,
        selectedType,
        selectedAccountId,
        selectedCategoryId,
        minAmountText,
        maxAmountText
    ) {
        val minSatang = MoneyFormatter.parseThbToSatang(minAmountText)
        val maxSatang = MoneyFormatter.parseThbToSatang(maxAmountText)
        transactions.filter { tx ->
            val q = searchQuery.trim()
            val matchesQuery = q.isEmpty() ||
                tx.description.contains(q, true) ||
                tx.recipientName.contains(q, true) ||
                tx.senderName.contains(q, true) ||
                tx.paymentReference.contains(q, true) ||
                tx.notes.contains(q, true)
            val matchesType = selectedType == null || tx.type == selectedType
            val matchesAcc = selectedAccountId == null || tx.accountId == selectedAccountId || tx.toAccountId == selectedAccountId
            val matchesCat = selectedCategoryId == null || tx.categoryId == selectedCategoryId
            val matchesMin = minSatang == null || tx.amountSatang >= minSatang
            val matchesMax = maxSatang == null || tx.amountSatang <= maxSatang
            matchesQuery && matchesType && matchesAcc && matchesCat && matchesMin && matchesMax
        }
    }

    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) onExportCsv(uri, filtered)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingTx = null
                    showEditor = true
                },
                modifier = Modifier.testTag("fab_add_transaction")
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "เพิ่มรายการ")
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 1040.dp)
                    .fillMaxSize()
                    .padding(horizontal = SpacingTokens.lg)
            ) {
                Spacer(Modifier.height(SpacingTokens.lg))
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
                        Text("รายการธุรกรรม", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "พบ ${filtered.size} รายการจากทั้งหมด ${transactions.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                Row {
                    IconButton(onClick = { showAdvancedFilters = !showAdvancedFilters }) {
                        Icon(Icons.Rounded.FilterList, contentDescription = "ตัวกรองขั้นสูง")
                    }
                    IconButton(onClick = {
                        csvLauncher.launch("overseer_transactions_${System.currentTimeMillis()}.csv")
                    }) {
                        Icon(Icons.Rounded.Download, contentDescription = "ส่งออก CSV")
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("ค้นหารายการ ผู้โอน ผู้รับ เลขอ้างอิง...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .testTag("tx_search_input")
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedType == null,
                    onClick = { selectedType = null },
                    label = { Text("ทั้งหมด") }
                )
                TransactionType.entries.forEach { t ->
                    FilterChip(
                        selected = selectedType == t,
                        onClick = { selectedType = if (selectedType == t) null else t },
                        label = { Text(t.labelTh) }
                    )
                }
            }

            if (showAdvancedFilters) {
                OverseerCard(modifier = Modifier.padding(vertical = 6.dp)) {
                    Text("กรองตามบัญชี หมวดหมู่ และจำนวนเงิน", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = minAmountText,
                            onValueChange = { minAmountText = it },
                            label = { Text("ขั้นต่ำ (฿)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = maxAmountText,
                            onValueChange = { maxAmountText = it },
                            label = { Text("สูงสุด (฿)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        accounts.forEach { acc ->
                            FilterChip(
                                selected = selectedAccountId == acc.id,
                                onClick = { selectedAccountId = if (selectedAccountId == acc.id) null else acc.id },
                                label = { Text(acc.name) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        categories.forEach { cat ->
                            FilterChip(
                                selected = selectedCategoryId == cat.id,
                                onClick = { selectedCategoryId = if (selectedCategoryId == cat.id) null else cat.id },
                                label = { Text(cat.nameTh) }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            if (filtered.isEmpty()) {
                EmptyStateCard(
                    icon = Icons.Rounded.ReceiptLong,
                    title = "ไม่พบรายการที่ตรงเงื่อนไข",
                    description = "ลองปรับตัวกรอง หรือแตะปุ่ม + เพื่อเพิ่มรายการใหม่",
                    actionLabel = "เพิ่มรายการ",
                    onAction = {
                        editingTx = null
                        showEditor = true
                    }
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(filtered, key = { it.id }) { tx ->
                        val cat = categoryMap[tx.categoryId]
                        val acc = accountMap[tx.accountId]
                        val hasAttachment = !attachmentByTx[tx.id].isNullOrEmpty()
                        OverseerCard(onClick = { detailTx = tx }) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (tx.type == TransactionType.INTERNAL_TRANSFER) Icons.Rounded.SwapHoriz
                                        else categoryIconForKey(cat?.iconKey ?: ""),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = tx.description,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${cat?.nameTh ?: tx.type.labelTh} • ${acc?.name ?: "-"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = ThaiDateFormatter.formatDateTime(tx.timestampEpochMillis, dateFormat),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (hasAttachment) {
                                            Icon(
                                                Icons.Rounded.AttachFile,
                                                contentDescription = "มีไฟล์แนบ",
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    val color = when {
                                        tx.type == TransactionType.INTERNAL_TRANSFER -> TransferCyan
                                        tx.type.isCashInflow -> IncomeEmerald
                                        else -> ExpenseCoral
                                    }
                                    Text(
                                        text = MoneyFormatter.formatThb(
                                            if (tx.type.isCashInflow || tx.type == TransactionType.INTERNAL_TRANSFER) tx.amountSatang else -tx.amountSatang,
                                            showSign = tx.type != TransactionType.INTERNAL_TRANSFER
                                        ),
                                        style = NumericMediumStyle,
                                        color = color
                                    )
                                    if (tx.reviewStatus == ReviewStatus.NEEDS_REVIEW) {
                                        StatusBadge("รอตรวจสอบ", WarningAmber)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }

    if (showEditor) {
        TransactionEditorSheet(
            initial = editingTx,
            accounts = accounts,
            categories = categories,
            onDismiss = { showEditor = false },
            onSave = { saved, photoUri ->
                onSaveTransaction(saved, photoUri)
                showEditor = false
            }
        )
    }

    detailTx?.let { tx ->
        val txAttachments = attachmentByTx[tx.id].orEmpty()
        AlertDialog(
            onDismissRequest = { detailTx = null },
            title = { Text(tx.description) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("จำนวนเงิน: ${MoneyFormatter.formatThb(tx.amountSatang)}", style = NumericMediumStyle)
                    Text("ประเภท: ${tx.type.labelTh}")
                    Text("วันที่เวลา: ${ThaiDateFormatter.formatDateTime(tx.timestampEpochMillis, dateFormat)}")
                    Text("บัญชี: ${accountMap[tx.accountId]?.name ?: "-"}")
                    if (tx.toAccountId != null) {
                        Text("บัญชีปลายทาง: ${accountMap[tx.toAccountId]?.name ?: "-"}")
                    }
                    Text("หมวดหมู่: ${categoryMap[tx.categoryId]?.nameTh ?: "ยังไม่ระบุ"}")
                    Text("มาตราภาษี: ${tx.taxSection.labelTh}")
                    if (tx.senderName.isNotBlank()) Text("ผู้โอน: ${tx.senderName}")
                    if (tx.recipientName.isNotBlank()) Text("ผู้รับ: ${tx.recipientName}")
                    if (tx.paymentReference.isNotBlank()) Text("เลขอ้างอิง: ${tx.paymentReference}")
                    if (tx.notes.isNotBlank()) Text("หมายเหตุ: ${tx.notes}")
                    Text("แหล่งที่มา: ${tx.source.labelTh} • ${tx.reviewStatus.labelTh}")
                    txAttachments.forEach { att ->
                        val file = File(att.localFilePath)
                        if (file.exists()) {
                            AsyncImage(
                                model = file,
                                contentDescription = att.originalFileName,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(MaterialTheme.shapes.small)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = {
                        pendingDeleteTx = tx
                        detailTx = null
                    }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "ลบรายการ", tint = ExpenseCoral)
                    }
                    IconButton(onClick = {
                        editingTx = tx
                        detailTx = null
                        showEditor = true
                    }) {
                        Icon(Icons.Rounded.Edit, contentDescription = "แก้ไขรายการ")
                    }
                    TextButton(onClick = { detailTx = null }) { Text("ปิด") }
                }
            }
        )
    }

    pendingDeleteTx?.let { tx ->
        DestructiveConfirmDialog(
            title = "ยืนยันการลบรายการ?",
            message = "คุณต้องการลบ \"${tx.description}\" จำนวน ${MoneyFormatter.formatThb(tx.amountSatang)} ใช่หรือไม่?",
            confirmText = "ลบรายการ",
            onConfirm = {
                onDeleteTransaction(tx)
                pendingDeleteTx = null
            },
            onDismiss = { pendingDeleteTx = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionEditorSheet(
    initial: TransactionEntity?,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (TransactionEntity, Uri?) -> Unit
) {
    var type by remember { mutableStateOf(initial?.type ?: TransactionType.EXPENSE) }
    var amountText by remember {
        mutableStateOf(if (initial != null && initial.amountSatang > 0L) MoneyFormatter.satangToInputString(initial.amountSatang) else "")
    }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var accountId by remember { mutableStateOf(initial?.accountId ?: accounts.firstOrNull()?.id ?: "acc_kbank_main") }
    var toAccountId by remember { mutableStateOf(initial?.toAccountId ?: accounts.getOrNull(1)?.id) }
    var categoryId by remember { mutableStateOf(initial?.categoryId) }
    var senderName by remember { mutableStateOf(initial?.senderName ?: "") }
    var recipientName by remember { mutableStateOf(initial?.recipientName ?: "") }
    var paymentRef by remember { mutableStateOf(initial?.paymentReference ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var taxSection by remember { mutableStateOf(initial?.taxSection ?: TaxSection.NONE) }
    var isBusiness by remember { mutableStateOf(initial?.isBusiness ?: false) }
    var costText by remember {
        mutableStateOf(if ((initial?.businessCostSatang ?: 0L) > 0L) MoneyFormatter.satangToInputString(initial!!.businessCostSatang) else "")
    }
    var pickedPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    var showAccMenu by remember { mutableStateOf(false) }
    var showToAccMenu by remember { mutableStateOf(false) }
    var showCatMenu by remember { mutableStateOf(false) }
    var showTaxMenu by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) pickedPhotoUri = uri }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SpacingTokens.lg, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                if (initial == null) "บันทึกรายการใหม่" else "แก้ไขรายการ",
                style = MaterialTheme.typography.titleLarge
            )

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TransactionType.entries.forEach { t ->
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
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("tx_amount_input")
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it; errorMsg = null },
                label = { Text("รายละเอียด / ชื่อรายการ *") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("tx_desc_input")
            )

            // Account & Category pickers
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(onClick = { showAccMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            accounts.firstOrNull { it.id == accountId }?.name ?: "เลือกบัญชี",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    DropdownMenu(expanded = showAccMenu, onDismissRequest = { showAccMenu = false }) {
                        accounts.forEach { acc ->
                            DropdownMenuItem(
                                text = { Text(acc.name) },
                                onClick = { accountId = acc.id; showAccMenu = false }
                            )
                        }
                    }
                }

                if (type == TransactionType.INTERNAL_TRANSFER) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(onClick = { showToAccMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "ไป: " + (accounts.firstOrNull { it.id == toAccountId }?.name ?: "ปลายทาง"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        DropdownMenu(expanded = showToAccMenu, onDismissRequest = { showToAccMenu = false }) {
                            accounts.forEach { acc ->
                                DropdownMenuItem(
                                    text = { Text(acc.name) },
                                    onClick = { toAccountId = acc.id; showToAccMenu = false }
                                )
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(onClick = { showCatMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                categories.firstOrNull { it.id == categoryId }?.nameTh ?: "เลือกหมวดหมู่",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        DropdownMenu(expanded = showCatMenu, onDismissRequest = { showCatMenu = false }) {
                            categories.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat.nameTh) },
                                    onClick = {
                                        categoryId = cat.id
                                        if (cat.defaultTaxSection != TaxSection.NONE) {
                                            taxSection = cat.defaultTaxSection
                                        }
                                        if (cat.isBusinessCategory) isBusiness = true
                                        showCatMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Tax Classification picker (never assumes every incoming payment is taxable income)
            Box {
                OutlinedButton(onClick = { showTaxMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("การจำแนกภาษี: ${taxSection.labelTh}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = showTaxMenu, onDismissRequest = { showTaxMenu = false }) {
                    TaxSection.entries.forEach { sec ->
                        DropdownMenuItem(
                            text = { Text(sec.labelTh) },
                            onClick = { taxSection = sec; showTaxMenu = false }
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = isBusiness, onCheckedChange = { isBusiness = it })
                Text("เป็นรายการของธุรกิจขนาดย่อม", style = MaterialTheme.typography.bodyMedium)
            }

            if (isBusiness && type == TransactionType.INCOME) {
                OutlinedTextField(
                    value = costText,
                    onValueChange = { costText = it },
                    label = { Text("ต้นทุนสินค้า/บริการ (บาท - ถ้ามี)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = senderName,
                    onValueChange = { senderName = it },
                    label = { Text("ผู้โอน") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = recipientName,
                    onValueChange = { recipientName = it },
                    label = { Text("ผู้รับ") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            OutlinedTextField(
                value = paymentRef,
                onValueChange = { paymentRef = it },
                label = { Text("เลขอ้างอิง (Ref)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("บันทึกเพิ่มเติม") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedButton(
                onClick = {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.AttachFile, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (pickedPhotoUri == null) "แนบภาพสลิป / ใบเสร็จ (Photo Picker)" else "เลือกภาพแนบแล้ว 1 ไฟล์")
            }

            if (errorMsg != null) {
                Text(errorMsg!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = {
                    val satang = MoneyFormatter.parseThbToSatang(amountText)
                    if (satang == null || satang <= 0L) {
                        errorMsg = "กรุณาระบุจำนวนเงินให้ถูกต้อง"
                        return@Button
                    }
                    val desc = description.trim().ifEmpty { type.labelTh }
                    val tx = (initial ?: TransactionEntity(
                        id = UUID.randomUUID().toString(),
                        amountSatang = satang,
                        type = type,
                        timestampEpochMillis = System.currentTimeMillis(),
                        description = desc,
                        accountId = accountId,
                        source = TransactionSource.MANUAL,
                        reviewStatus = ReviewStatus.VERIFIED
                    )).copy(
                        amountSatang = satang,
                        type = type,
                        description = desc,
                        accountId = accountId,
                        toAccountId = if (type == TransactionType.INTERNAL_TRANSFER) toAccountId else null,
                        categoryId = categoryId,
                        senderName = senderName.trim(),
                        recipientName = recipientName.trim(),
                        paymentReference = paymentRef.trim(),
                        notes = notes.trim(),
                        taxSection = taxSection,
                        isBusiness = isBusiness,
                        businessCostSatang = MoneyFormatter.parseThbToSatang(costText) ?: 0L,
                        reviewStatus = ReviewStatus.VERIFIED
                    )
                    onSave(tx, pickedPhotoUri)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("save_tx_button")
            ) {
                Text("บันทึกรายการ")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
