package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BusinessCenter
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.AccountEntity
import com.example.data.local.AccountType
import com.example.data.local.BudgetEntity
import com.example.data.local.CategorizationRuleEntity
import com.example.data.local.CategoryEntity
import com.example.data.local.DateDisplayFormat
import com.example.data.local.DebtDirection
import com.example.data.local.DebtEntity
import com.example.data.local.DebtRepaymentEntity
import com.example.data.local.DebtStatus
import com.example.data.local.RecurrenceFrequency
import com.example.data.local.RecurringTransactionEntity
import com.example.data.local.RuleMatchField
import com.example.data.local.TaxSection
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import com.example.data.repository.FinanceRepository
import com.example.domain.MoneyFormatter
import com.example.domain.ReportType
import com.example.domain.ThaiDateFormatter
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.OverseerCard
import com.example.ui.components.SectionHeaderRow
import com.example.ui.components.StatusBadge
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeEmerald
import com.example.ui.theme.NumericMediumStyle
import com.example.ui.theme.SpacingTokens
import com.example.ui.theme.TransferCyan
import com.example.ui.theme.WarningAmber

enum class MoreSubScreen {
    MENU,
    ACCOUNTS_CATEGORIES,
    DEBTS,
    BUDGETS_RECURRING,
    BUSINESS,
    REPORTS,
    BACKUP_CENTER,
    SETTINGS_DIAGNOSTICS
}

@Composable
fun MoreHubScreen(
    currentSubScreen: MoreSubScreen,
    onNavigateSub: (MoreSubScreen) -> Unit,
    accountsContent: @Composable () -> Unit,
    debtsContent: @Composable () -> Unit,
    budgetsContent: @Composable () -> Unit,
    businessContent: @Composable () -> Unit,
    reportsContent: @Composable () -> Unit,
    backupContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit
) {
    if (currentSubScreen != MoreSubScreen.MENU) {
        BackHandler { onNavigateSub(MoreSubScreen.MENU) }
    }

    when (currentSubScreen) {
        MoreSubScreen.MENU -> MoreMenuList(onNavigateSub)
        MoreSubScreen.ACCOUNTS_CATEGORIES -> accountsContent()
        MoreSubScreen.DEBTS -> debtsContent()
        MoreSubScreen.BUDGETS_RECURRING -> budgetsContent()
        MoreSubScreen.BUSINESS -> businessContent()
        MoreSubScreen.REPORTS -> reportsContent()
        MoreSubScreen.BACKUP_CENTER -> backupContent()
        MoreSubScreen.SETTINGS_DIAGNOSTICS -> settingsContent()
    }
}

@Composable
private fun MoreMenuList(onNavigate: (MoreSubScreen) -> Unit) {
    val menuItems = listOf(
        Triple(MoreSubScreen.BACKUP_CENTER, Icons.Rounded.Backup, "ศูนย์สำรองและกู้คืนข้อมูล (.efbackup / .sqlite)"),
        Triple(MoreSubScreen.ACCOUNTS_CATEGORIES, Icons.Rounded.AccountBalance, "บัญชี หมวดหมู่ และกฎจัดหมวดอัตโนมัติ"),
        Triple(MoreSubScreen.DEBTS, Icons.Rounded.Handshake, "จัดการหนี้สิน ให้กู้ยืม และตารางผ่อนชำระ"),
        Triple(MoreSubScreen.BUDGETS_RECURRING, Icons.Rounded.Savings, "งบประมาณ และรายจ่ายประจำ / สมัครสมาชิก"),
        Triple(MoreSubScreen.BUSINESS, Icons.Rounded.BusinessCenter, "บัญชีธุรกิจขนาดย่อม (Mini Accounting)"),
        Triple(MoreSubScreen.REPORTS, Icons.Rounded.Assessment, "รายงานและส่งออก (CSV / PDF)"),
        Triple(MoreSubScreen.SETTINGS_DIAGNOSTICS, Icons.Rounded.Settings, "ตั้งค่าแอป การแจ้งเตือน และวินิจฉัยระบบ")
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(SpacingTokens.lg),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("โมดูลทั้งหมดและเครื่องมือ", style = MaterialTheme.typography.headlineSmall)
            Text(
                "OverseerFinance • ทำงานแบบออฟไลน์สมบูรณ์บนอุปกรณ์ของคุณ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(menuItems) { (screen, icon, title) ->
            MoreMenuRow(icon = icon, title = title, tag = "more_nav_${screen.name}") {
                onNavigate(screen)
            }
        }
    }
}

@Composable
private fun MoreMenuRow(
    icon: ImageVector,
    title: String,
    tag: String,
    onClick: () -> Unit
) {
    OverseerCard(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
fun AccountsCategoriesScreen(
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    rules: List<CategorizationRuleEntity>,
    transactions: List<TransactionEntity>,
    onBack: () -> Unit,
    onSaveAccount: (AccountEntity) -> Unit,
    onDeleteAccount: (AccountEntity) -> Unit,
    onSaveCategory: (CategoryEntity) -> Unit,
    onSaveRule: (CategorizationRuleEntity) -> Unit,
    onDeleteRule: (CategorizationRuleEntity) -> Unit
) {
    var showAddAccount by remember { mutableStateOf(false) }
    var showAddCategory by remember { mutableStateOf(false) }
    var showAddRule by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(SpacingTokens.lg),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← กลับ") }
                Text("บัญชี หมวดหมู่ และกฎอัตโนมัติ", style = MaterialTheme.typography.titleLarge)
            }
        }

        item {
            SectionHeaderRow(
                title = "บัญชีของฉัน (${accounts.size})",
                subtitle = "ใช้แยกเงินโอนระหว่างบัญชีตนเองออกจากรายรับ",
                trailing = { IconButton(onClick = { showAddAccount = true }) { Icon(Icons.Rounded.Add, contentDescription = "เพิ่มบัญชี") } }
            )
        }
        items(accounts, key = { it.id }) { acc ->
            val bal = FinanceRepository.computeAccountBalanceSatang(acc, transactions)
            OverseerCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(acc.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${acc.accountType.labelTh} • ${if (acc.isBusinessAccount) "ธุรกิจ" else "ส่วนตัว"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(MoneyFormatter.formatThb(bal), style = NumericMediumStyle, color = IncomeEmerald)
                        Switch(checked = acc.isActive, onCheckedChange = { onSaveAccount(acc.copy(isActive = it)) })
                    }
                }
            }
        }

        item {
            SectionHeaderRow(
                title = "หมวดหมู่ (${categories.size})",
                trailing = { IconButton(onClick = { showAddCategory = true }) { Icon(Icons.Rounded.Add, contentDescription = "เพิ่มหมวดหมู่") } }
            )
        }
        items(categories, key = { it.id }) { cat ->
            OverseerCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${cat.nameTh} (${cat.nameEn})", style = MaterialTheme.typography.bodyMedium)
                    StatusBadge(cat.type.labelTh, if (cat.type == TransactionType.INCOME) IncomeEmerald else ExpenseCoral)
                }
            }
        }

        item {
            SectionHeaderRow(
                title = "กฎจัดหมวดหมู่และภาษีอัตโนมัติ (${rules.size})",
                subtitle = "กำหนดชื่อผู้รับ/คำสำคัญเพื่อให้สลิป K PLUS จัดหมวดให้อัตโนมัติ",
                trailing = { IconButton(onClick = { showAddRule = true }) { Icon(Icons.Rounded.Add, contentDescription = "เพิ่มกฎ") } }
            )
        }
        items(rules, key = { it.id }) { rule ->
            val catName = categories.firstOrNull { it.id == rule.categoryId }?.nameTh ?: "-"
            OverseerCard {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("คำค้น: \"${rule.keywordOrRecipient}\" (${rule.matchField.labelTh})", style = MaterialTheme.typography.titleSmall)
                        Text("→ $catName • ${rule.transactionType.labelTh}", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { onDeleteRule(rule) }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "ลบกฎ", tint = ExpenseCoral)
                    }
                }
            }
        }
    }

    if (showAddAccount) {
        var name by remember { mutableStateOf("") }
        var openingText by remember { mutableStateOf("0") }
        var accType by remember { mutableStateOf(AccountType.SAVINGS) }
        var isBiz by remember { mutableStateOf(false) }
        var showMenu by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showAddAccount = false },
            title = { Text("เพิ่มบัญชีใหม่") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("ชื่อบัญชี") }, singleLine = true)
                    Box {
                        OutlinedButton(onClick = { showMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(accType.labelTh) }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            AccountType.entries.forEach { t ->
                                DropdownMenuItem(text = { Text(t.labelTh) }, onClick = { accType = t; showMenu = false })
                            }
                        }
                    }
                    OutlinedTextField(value = openingText, onValueChange = { openingText = it }, label = { Text("ยอดยกมา (บาท)") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isBiz, onCheckedChange = { isBiz = it })
                        Text("ใช้สำหรับธุรกิจ")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank()) {
                        onSaveAccount(
                            AccountEntity(
                                name = name.trim(),
                                accountType = accType,
                                openingBalanceSatang = MoneyFormatter.parseThbToSatang(openingText, allowNegative = true) ?: 0L,
                                isBusinessAccount = isBiz
                            )
                        )
                        showAddAccount = false
                    }
                }) { Text("บันทึก") }
            },
            dismissButton = { TextButton(onClick = { showAddAccount = false }) { Text("ยกเลิก") } }
        )
    }

    if (showAddCategory) {
        var nameTh by remember { mutableStateOf("") }
        var nameEn by remember { mutableStateOf("") }
        var txType by remember { mutableStateOf(TransactionType.EXPENSE) }
        AlertDialog(
            onDismissRequest = { showAddCategory = false },
            title = { Text("เพิ่มหมวดหมู่กำหนดเอง") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = txType == TransactionType.EXPENSE, onClick = { txType = TransactionType.EXPENSE }, label = { Text("รายจ่าย") })
                        FilterChip(selected = txType == TransactionType.INCOME, onClick = { txType = TransactionType.INCOME }, label = { Text("รายรับ") })
                    }
                    OutlinedTextField(value = nameTh, onValueChange = { nameTh = it }, label = { Text("ชื่อหมวดหมู่ (ไทย)") }, singleLine = true)
                    OutlinedTextField(value = nameEn, onValueChange = { nameEn = it }, label = { Text("ชื่อภาษาอังกฤษ") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (nameTh.isNotBlank()) {
                        onSaveCategory(
                            CategoryEntity(
                                nameTh = nameTh.trim(),
                                nameEn = nameEn.trim().ifEmpty { nameTh.trim() },
                                type = txType,
                                iconKey = "receipt_long",
                                colorHex = 0xFF34D399
                            )
                        )
                        showAddCategory = false
                    }
                }) { Text("บันทึก") }
            },
            dismissButton = { TextButton(onClick = { showAddCategory = false }) { Text("ยกเลิก") } }
        )
    }

    if (showAddRule) {
        var keyword by remember { mutableStateOf("") }
        var selectedCatId by remember { mutableStateOf(categories.firstOrNull()?.id ?: "") }
        var selectedType by remember { mutableStateOf(TransactionType.EXPENSE) }
        var showCatMenu by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showAddRule = false },
            title = { Text("สร้างกฎจัดหมวดอัตโนมัติ") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = keyword, onValueChange = { keyword = it }, label = { Text("คำค้นหรือชื่อผู้รับโอน") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = selectedType == TransactionType.EXPENSE, onClick = { selectedType = TransactionType.EXPENSE }, label = { Text("รายจ่าย") })
                        FilterChip(selected = selectedType == TransactionType.INCOME, onClick = { selectedType = TransactionType.INCOME }, label = { Text("รายรับ") })
                        FilterChip(selected = selectedType == TransactionType.INTERNAL_TRANSFER, onClick = { selectedType = TransactionType.INTERNAL_TRANSFER }, label = { Text("โอนเอง") })
                    }
                    Box {
                        OutlinedButton(onClick = { showCatMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(categories.firstOrNull { it.id == selectedCatId }?.nameTh ?: "เลือกหมวดหมู่")
                        }
                        DropdownMenu(expanded = showCatMenu, onDismissRequest = { showCatMenu = false }) {
                            categories.forEach { c ->
                                DropdownMenuItem(text = { Text(c.nameTh) }, onClick = { selectedCatId = c.id; showCatMenu = false })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (keyword.isNotBlank() && selectedCatId.isNotBlank()) {
                        onSaveRule(
                            CategorizationRuleEntity(
                                keywordOrRecipient = keyword.trim(),
                                matchField = RuleMatchField.ANY,
                                categoryId = selectedCatId,
                                transactionType = selectedType
                            )
                        )
                        showAddRule = false
                    }
                }) { Text("บันทึกกฎ") }
            },
            dismissButton = { TextButton(onClick = { showAddRule = false }) { Text("ยกเลิก") } }
        )
    }
}

@Composable
fun DebtsScreen(
    debts: List<DebtEntity>,
    repayments: List<DebtRepaymentEntity>,
    accounts: List<AccountEntity>,
    dateFormat: DateDisplayFormat,
    onBack: () -> Unit,
    onSaveDebt: (DebtEntity) -> Unit,
    onRecordRepayment: (DebtEntity, Long, String, String) -> Unit,
    onDeleteDebt: (DebtEntity) -> Unit
) {
    var showAddDebt by remember { mutableStateOf(false) }
    var repayTarget by remember { mutableStateOf<DebtEntity?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(SpacingTokens.lg),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("← กลับ") }
                    Text("หนี้สินและการผ่อนชำระ", style = MaterialTheme.typography.titleLarge)
                }
                IconButton(onClick = { showAddDebt = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = "เพิ่มรายการหนี้")
                }
            }
        }

        if (debts.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Rounded.Handshake,
                    title = "ยังไม่มีรายการหนี้สิน",
                    description = "บันทึกเงินที่ให้ผู้อื่นกู้ยืมหรือเงินที่คุณกู้มา พร้อมติดตามการผ่อนชำระเป็นงวด",
                    actionLabel = "เพิ่มรายการหนี้",
                    onAction = { showAddDebt = true }
                )
            }
        } else {
            items(debts, key = { it.id }) { debt ->
                val history = repayments.filter { it.debtId == debt.id }
                val progress = if (debt.principalSatang > 0L) {
                    ((debt.principalSatang - debt.remainingSatang).toFloat() / debt.principalSatang.toFloat()).coerceIn(0f, 1f)
                } else 0f

                OverseerCard {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(debt.title, style = MaterialTheme.typography.titleMedium)
                            Text("${debt.direction.labelTh} • ${debt.counterpartyName}", style = MaterialTheme.typography.bodySmall)
                        }
                        StatusBadge(
                            debt.status.labelTh,
                            when (debt.status) {
                                DebtStatus.PAID_OFF -> IncomeEmerald
                                DebtStatus.OVERDUE -> ExpenseCoral
                                DebtStatus.ACTIVE -> WarningAmber
                            }
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "คงเหลือ ${MoneyFormatter.formatThb(debt.remainingSatang)} / เงินต้น ${MoneyFormatter.formatThb(debt.principalSatang)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    Text(
                        "ชำระแล้ว ${history.size} งวด" + (debt.dueEpochMillis?.let { " • ครบกำหนด ${ThaiDateFormatter.formatDate(it, dateFormat)}" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (debt.remainingSatang > 0L) {
                            OutlinedButton(onClick = { repayTarget = debt }) {
                                Text("บันทึกชำระงวด")
                            }
                        }
                        TextButton(onClick = { onDeleteDebt(debt) }) {
                            Text("ลบ", color = ExpenseCoral)
                        }
                    }
                }
            }
        }
    }

    if (showAddDebt) {
        var title by remember { mutableStateOf("") }
        var counterparty by remember { mutableStateOf("") }
        var amountText by remember { mutableStateOf("") }
        var installmentsText by remember { mutableStateOf("1") }
        var direction by remember { mutableStateOf(DebtDirection.BORROWED) }
        AlertDialog(
            onDismissRequest = { showAddDebt = false },
            title = { Text("เพิ่มรายการหนี้สิน") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = direction == DebtDirection.BORROWED, onClick = { direction = DebtDirection.BORROWED }, label = { Text("กู้ยืมมา") })
                        FilterChip(selected = direction == DebtDirection.LENT, onClick = { direction = DebtDirection.LENT }, label = { Text("ให้ยืม") })
                    }
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("หัวข้อ (เช่น ผ่อนอุปกรณ์)") }, singleLine = true)
                    OutlinedTextField(value = counterparty, onValueChange = { counterparty = it }, label = { Text("คู่สัญญา / ชื่อผู้เกี่ยวข้อง") }, singleLine = true)
                    OutlinedTextField(value = amountText, onValueChange = { amountText = it }, label = { Text("ยอดเงินต้น (บาท)") }, singleLine = true)
                    OutlinedTextField(value = installmentsText, onValueChange = { installmentsText = it }, label = { Text("จำนวนงวด") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val principal = MoneyFormatter.parseThbToSatang(amountText) ?: 0L
                    val count = (installmentsText.toIntOrNull() ?: 1).coerceAtLeast(1)
                    if (title.isNotBlank() && principal > 0L) {
                        onSaveDebt(
                            DebtEntity(
                                title = title.trim(),
                                direction = direction,
                                counterpartyName = counterparty.trim().ifEmpty { "ไม่ระบุ" },
                                principalSatang = principal,
                                remainingSatang = principal,
                                dueEpochMillis = System.currentTimeMillis() + 30L * 86_400_000L,
                                installmentCount = count,
                                installmentAmountSatang = principal / count
                            )
                        )
                        showAddDebt = false
                    }
                }) { Text("บันทึก") }
            },
            dismissButton = { TextButton(onClick = { showAddDebt = false }) { Text("ยกเลิก") } }
        )
    }

    repayTarget?.let { debt ->
        var amountText by remember { mutableStateOf(MoneyFormatter.satangToInputString(debt.installmentAmountSatang.takeIf { it > 0L } ?: debt.remainingSatang)) }
        var notes by remember { mutableStateOf("") }
        var accId by remember { mutableStateOf(accounts.firstOrNull()?.id ?: "acc_kbank_main") }
        AlertDialog(
            onDismissRequest = { repayTarget = null },
            title = { Text("บันทึกชำระหนี้: ${debt.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("คงเหลือปัจจุบัน: ${MoneyFormatter.formatThb(debt.remainingSatang)}")
                    OutlinedTextField(value = amountText, onValueChange = { amountText = it }, label = { Text("จำนวนเงินงวดนี้ (บาท)") }, singleLine = true)
                    OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("หมายเหตุ") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val amt = MoneyFormatter.parseThbToSatang(amountText) ?: 0L
                    if (amt > 0L) {
                        onRecordRepayment(debt, amt, accId, notes.trim())
                        repayTarget = null
                    }
                }) { Text("ยืนยันชำระ") }
            },
            dismissButton = { TextButton(onClick = { repayTarget = null }) { Text("ยกเลิก") } }
        )
    }
}

@Composable
fun BudgetsRecurringScreen(
    budgets: List<BudgetEntity>,
    recurring: List<RecurringTransactionEntity>,
    categories: List<CategoryEntity>,
    accounts: List<AccountEntity>,
    transactions: List<TransactionEntity>,
    dateFormat: DateDisplayFormat,
    onBack: () -> Unit,
    onSaveBudget: (BudgetEntity) -> Unit,
    onDeleteBudget: (BudgetEntity) -> Unit,
    onSaveRecurring: (RecurringTransactionEntity) -> Unit,
    onDeleteRecurring: (RecurringTransactionEntity) -> Unit
) {
    var showAddBudget by remember { mutableStateOf(false) }
    var showAddRecurring by remember { mutableStateOf(false) }
    val currentYm = ThaiDateFormatter.currentYearMonth()
    val catMap = remember(categories) { categories.associateBy { it.id } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(SpacingTokens.lg),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← กลับ") }
                Text("งบประมาณ & รายการประจำ", style = MaterialTheme.typography.titleLarge)
            }
        }

        item {
            SectionHeaderRow(
                title = "งบประมาณเดือนนี้ (${ThaiDateFormatter.formatMonthYearTh(currentYm)})",
                trailing = { IconButton(onClick = { showAddBudget = true }) { Icon(Icons.Rounded.Add, contentDescription = "เพิ่มงบ") } }
            )
        }
        items(budgets, key = { it.id }) { b ->
            val spent = transactions.filter { tx ->
                tx.type == TransactionType.EXPENSE &&
                    (b.categoryId == null || tx.categoryId == b.categoryId) &&
                    ThaiDateFormatter.toBangkokDate(tx.timestampEpochMillis).let {
                        String.format("%04d-%02d", it.year, it.monthValue) == b.yearMonth
                    }
            }.sumOf { it.amountSatang }
            val ratio = if (b.limitSatang > 0L) (spent.toFloat() / b.limitSatang.toFloat()).coerceIn(0f, 1f) else 0f
            OverseerCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(b.name, style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { onDeleteBudget(b) }) { Icon(Icons.Rounded.Delete, contentDescription = "ลบ", tint = ExpenseCoral) }
                }
                Text("${MoneyFormatter.formatThb(spent)} / ${MoneyFormatter.formatThb(b.limitSatang)} (${(ratio * 100).toInt()}%)")
                LinearProgressIndicator(
                    progress = { ratio },
                    color = if (ratio >= 0.9f) ExpenseCoral else IncomeEmerald,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp).clip(RoundedCornerShape(4.dp))
                )
            }
        }

        item {
            SectionHeaderRow(
                title = "รายการประจำ & การแจ้งเตือนออฟไลน์ (${recurring.size})",
                trailing = { IconButton(onClick = { showAddRecurring = true }) { Icon(Icons.Rounded.Add, contentDescription = "เพิ่มรายการประจำ") } }
            )
        }
        items(recurring, key = { it.id }) { item ->
            OverseerCard {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${item.frequency.labelTh} • ${MoneyFormatter.formatThb(item.amountSatang)} • ครบกำหนด ${ThaiDateFormatter.formatDate(item.nextDueEpochMillis, dateFormat)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onDeleteRecurring(item) }) { Icon(Icons.Rounded.Delete, contentDescription = "ลบ", tint = ExpenseCoral) }
                }
            }
        }
    }

    if (showAddBudget) {
        var name by remember { mutableStateOf("งบรายจ่ายรวม") }
        var limitText by remember { mutableStateOf("") }
        var catId by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showAddBudget = false },
            title = { Text("ตั้งงบประมาณประจำเดือน") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("ชื่องบประมาณ") }, singleLine = true)
                    OutlinedTextField(value = limitText, onValueChange = { limitText = it }, label = { Text("วงเงินสูงสุด (บาท)") }, singleLine = true)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = catId == null, onClick = { catId = null }, label = { Text("รวมทุกหมวด") })
                        categories.filter { it.type == TransactionType.EXPENSE }.forEach { c ->
                            FilterChip(selected = catId == c.id, onClick = { catId = c.id; name = "งบ${c.nameTh}" }, label = { Text(c.nameTh) })
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val limit = MoneyFormatter.parseThbToSatang(limitText) ?: 0L
                    if (limit > 0L) {
                        onSaveBudget(BudgetEntity(name = name.trim(), categoryId = catId, yearMonth = currentYm, limitSatang = limit))
                        showAddBudget = false
                    }
                }) { Text("บันทึก") }
            },
            dismissButton = { TextButton(onClick = { showAddBudget = false }) { Text("ยกเลิก") } }
        )
    }

    if (showAddRecurring) {
        var title by remember { mutableStateOf("") }
        var amountText by remember { mutableStateOf("") }
        var freq by remember { mutableStateOf(RecurrenceFrequency.MONTHLY) }
        AlertDialog(
            onDismissRequest = { showAddRecurring = false },
            title = { Text("เพิ่มรายการประจำ / บิลเตือนล่วงหน้า") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("ชื่อรายการ (เช่น ค่าเน็ต ค่าเช่า)") }, singleLine = true)
                    OutlinedTextField(value = amountText, onValueChange = { amountText = it }, label = { Text("จำนวนเงิน (บาท)") }, singleLine = true)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RecurrenceFrequency.entries.forEach { f ->
                            FilterChip(selected = freq == f, onClick = { freq = f }, label = { Text(f.labelTh) })
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val amt = MoneyFormatter.parseThbToSatang(amountText) ?: 0L
                    if (title.isNotBlank() && amt > 0L) {
                        onSaveRecurring(
                            RecurringTransactionEntity(
                                title = title.trim(),
                                amountSatang = amt,
                                accountId = accounts.firstOrNull()?.id ?: "acc_kbank_main",
                                frequency = freq,
                                nextDueEpochMillis = System.currentTimeMillis() + 7L * 86_400_000L
                            )
                        )
                        showAddRecurring = false
                    }
                }) { Text("บันทึก") }
            },
            dismissButton = { TextButton(onClick = { showAddRecurring = false }) { Text("ยกเลิก") } }
        )
    }
}

@Composable
fun BusinessAccountingScreen(
    transactions: List<TransactionEntity>,
    dateFormat: DateDisplayFormat,
    onBack: () -> Unit,
    onToggleCustomerPaid: (TransactionEntity) -> Unit
) {
    val bizTx = remember(transactions) { transactions.filter { it.isBusiness } }
    val grossSales = bizTx.filter { it.type == TransactionType.INCOME }.sumOf { it.amountSatang }
    val directCosts = bizTx.sumOf { it.businessCostSatang + it.shippingCostSatang + it.adSpendSatang }
    val bizExpenses = bizTx.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountSatang }
    val totalCost = directCosts + bizExpenses
    val netProfit = grossSales - totalCost
    val unpaidReceivables = bizTx.filter { it.type == TransactionType.INCOME && !it.isCustomerPaid }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(SpacingTokens.lg),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← กลับ") }
                Text("บัญชีธุรกิจขนาดย่อม", style = MaterialTheme.typography.titleLarge)
            }
        }
        item {
            OverseerCard {
                Text("สรุปผลประกอบการธุรกิจ", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("ยอดขายรวม (Gross Sales)")
                    Text(MoneyFormatter.formatThb(grossSales), style = NumericMediumStyle, color = IncomeEmerald)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("ต้นทุน ค่าส่ง ค่าโฆษณา และค่าใช้จ่าย")
                    Text("-${MoneyFormatter.formatThb(totalCost)}", style = NumericMediumStyle, color = ExpenseCoral)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("กำไรสุทธิ (Net Profit)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        MoneyFormatter.formatThb(netProfit, showSign = true),
                        style = NumericMediumStyle,
                        color = if (netProfit >= 0L) IncomeEmerald else ExpenseCoral
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "ยอดค้างรับจากลูกค้า (${unpaidReceivables.size} รายการ): ${MoneyFormatter.formatThb(unpaidReceivables.sumOf { it.amountSatang })}",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarningAmber
                )
            }
        }

        if (bizTx.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Rounded.BusinessCenter,
                    title = "ยังไม่มีรายการธุรกิจ",
                    description = "เมื่อบันทึกรายรับ-รายจ่าย ให้ติ๊ก \"เป็นรายการของธุรกิจขนาดย่อม\" เพื่อแยกรายงานจากค่าใช้จ่ายส่วนตัว"
                )
            }
        } else {
            items(bizTx, key = { it.id }) { tx ->
                OverseerCard {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tx.description, style = MaterialTheme.typography.titleSmall)
                            Text(ThaiDateFormatter.formatDate(tx.timestampEpochMillis, dateFormat), style = MaterialTheme.typography.bodySmall)
                            if (tx.type == TransactionType.INCOME) {
                                TextButton(onClick = { onToggleCustomerPaid(tx.copy(isCustomerPaid = !tx.isCustomerPaid)) }) {
                                    Text(if (tx.isCustomerPaid) "สถานะ: ลูกค้าชำระแล้ว" else "สถานะ: ค้างรับ (แตะเพื่อรับชำระ)")
                                }
                            }
                        }
                        Text(
                            MoneyFormatter.formatThb(tx.amountSatang),
                            style = NumericMediumStyle,
                            color = if (tx.type == TransactionType.INCOME) IncomeEmerald else ExpenseCoral
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ReportsScreen(
    transactions: List<TransactionEntity>,
    onBack: () -> Unit,
    onExportCsv: (Uri, List<TransactionEntity>) -> Unit,
    onExportPdf: (Uri, ReportType, String, List<TransactionEntity>) -> Unit
) {
    var pendingPdfType by remember { mutableStateOf(ReportType.MONTHLY) }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) onExportCsv(uri, transactions)
    }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) onExportPdf(uri, pendingPdfType, "ข้อมูลปัจจุบัน (${transactions.size} รายการ)", transactions)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(SpacingTokens.lg),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← กลับ") }
                Text("รายงานและการส่งออก", style = MaterialTheme.typography.titleLarge)
            }
        }
        item {
            OverseerCard {
                Text("ส่งออกข้อมูลธุรกรรมทั้งหมดเป็น CSV (UTF-8)", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { csvLauncher.launch("overseer_all_${System.currentTimeMillis()}.csv") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("ส่งออก CSV (${transactions.size} รายการ)")
                }
            }
        }
        items(ReportType.entries) { reportType ->
            OverseerCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(reportType.labelTh, style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick = {
                        pendingPdfType = reportType
                        pdfLauncher.launch("overseer_${reportType.name.lowercase()}_${System.currentTimeMillis()}.pdf")
                    }) {
                        Text("สร้าง PDF")
                    }
                }
            }
        }
    }
}
