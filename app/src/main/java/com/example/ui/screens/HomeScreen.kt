package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoGraph
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.AccountEntity
import com.example.data.local.CategoryEntity
import com.example.data.local.DateDisplayFormat
import com.example.data.local.DebtEntity
import com.example.data.local.DebtStatus
import com.example.data.local.PeriodFilter
import com.example.data.local.RecurringTransactionEntity
import com.example.data.local.ReviewStatus
import com.example.data.local.ScanSourceEntity
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import com.example.domain.InsightSeverity
import com.example.domain.MoneyFormatter
import com.example.domain.TaxCalculationReport
import com.example.domain.ThaiDateFormatter
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.IncomeExpenseBarChart
import com.example.ui.components.OverseerCard
import com.example.ui.components.SectionHeaderRow
import com.example.ui.components.SpendingTrendLineChart
import com.example.ui.components.StatusBadge
import com.example.ui.components.categoryIconForKey
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeEmerald
import com.example.ui.theme.NavyElevated
import com.example.ui.theme.NavySurface
import com.example.ui.theme.NumericDisplayStyle
import com.example.ui.theme.NumericMediumStyle
import com.example.ui.theme.SpacingTokens
import com.example.ui.theme.TransferCyan
import com.example.ui.theme.WarningAmber
import com.example.ui.viewmodel.DashboardSummaryState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    dashboard: DashboardSummaryState,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    transactions: List<TransactionEntity>,
    recurring: List<RecurringTransactionEntity>,
    debts: List<DebtEntity>,
    scanSource: ScanSourceEntity?,
    taxReport: TaxCalculationReport?,
    dateFormat: DateDisplayFormat,
    onSelectPeriod: (PeriodFilter, Long?, Long?) -> Unit,
    onOpenTransactions: () -> Unit,
    onOpenAddTransaction: () -> Unit,
    onOpenScanCenter: () -> Unit,
    onOpenTaxTab: () -> Unit
) {
    var showCustomDatePicker by remember { mutableStateOf(false) }
    var showBalanceExplainDialog by remember { mutableStateOf(false) }

    val categoryMap = remember(categories) { categories.associateBy { it.id } }
    val accountMap = remember(accounts) { accounts.associateBy { it.id } }
    val upcomingBills = remember(recurring) {
        recurring.filter { it.isActive }.sortedBy { it.nextDueEpochMillis }.take(3)
    }
    val upcomingDebts = remember(debts) {
        debts.filter { it.status != DebtStatus.PAID_OFF && it.dueEpochMillis != null }
            .sortedBy { it.dueEpochMillis }
            .take(2)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(SpacingTokens.lg),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.md)
    ) {
        // Header + Quick Add
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "OverseerFinance",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Personal Finance Intelligence • ออฟไลน์ 100%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Button(
                    onClick = onOpenAddTransaction,
                    modifier = Modifier.testTag("home_add_tx_button")
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "เพิ่มรายการ")
                    Spacer(Modifier.width(4.dp))
                    Text("บันทึกรายการ")
                }
            }
        }

        // Permission revoked warning if SAF folder needs reconnect
        if (scanSource != null && !scanSource.permissionValid) {
            item {
                OverseerCard(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                    borderColor = ExpenseCoral.copy(alpha = 0.6f),
                    onClick = onOpenScanCenter
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.FolderOff,
                            contentDescription = "สิทธิ์โฟลเดอร์ขาดหาย",
                            tint = ExpenseCoral
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "สิทธิ์โฟลเดอร์ K PLUS ถูกยกเลิกหรือเปลี่ยนเครื่อง",
                                style = MaterialTheme.typography.titleSmall,
                                color = ExpenseCoral
                            )
                            Text(
                                "แตะเพื่อเลือกโฟลเดอร์ K PLUS ใหม่ ข้อมูลและรูปเดิมยังปลอดภัย",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        // Hero Balance Card
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(NavyElevated, NavySurface, EmeraldDark.copy(alpha = 0.25f))
                        )
                    )
                    .padding(SpacingTokens.xl)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.AccountBalanceWallet,
                                contentDescription = null,
                                tint = EmeraldPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "ยอดคงเหลือรวมทุกบัญชี",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.Rounded.Info,
                            contentDescription = "คำอธิบายยอดเงิน",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { showBalanceExplainDialog = true }
                        )
                    }
                    Text(
                        text = MoneyFormatter.formatThb(dashboard.totalAvailableBalanceSatang),
                        style = NumericDisplayStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("total_balance_text")
                    )
                    Text(
                        text = "คำนวณจาก ${accounts.count { it.isActive }} บัญชีที่เปิดใช้งาน (ไม่นับโอนระหว่างบัญชีเป็นรายรับ)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Period Filter Chips
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PeriodFilter.entries.forEach { period ->
                    FilterChip(
                        selected = dashboard.periodFilter == period,
                        onClick = {
                            if (period == PeriodFilter.CUSTOM) {
                                showCustomDatePicker = true
                            } else {
                                onSelectPeriod(period, null, null)
                            }
                        },
                        label = { Text(period.labelTh) },
                        modifier = Modifier.testTag("period_chip_${period.name}")
                    )
                }
            }
        }

        // Income / Expense / Net Cash Flow summary cards
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OverseerCard(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ArrowDownward, contentDescription = null, tint = IncomeEmerald, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("รายรับ (${dashboard.periodFilter.labelTh})", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = MoneyFormatter.formatThb(dashboard.periodIncomeSatang),
                        style = NumericMediumStyle,
                        color = IncomeEmerald,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OverseerCard(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = ExpenseCoral, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("รายจ่าย (${dashboard.periodFilter.labelTh})", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = MoneyFormatter.formatThb(dashboard.periodExpenseSatang),
                        style = NumericMediumStyle,
                        color = ExpenseCoral,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        item {
            OverseerCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("กระแสเงินสดสุทธิ (${dashboard.periodFilter.labelTh})", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "รายรับจริง - รายจ่ายจริง • โอนระหว่างบัญชี ${MoneyFormatter.formatThb(dashboard.periodTransferSatang)} แยกต่างหาก",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = MoneyFormatter.formatThb(dashboard.netCashFlowSatang, showSign = true),
                        style = NumericMediumStyle,
                        color = if (dashboard.netCashFlowSatang >= 0L) IncomeEmerald else ExpenseCoral
                    )
                }
            }
        }

        // Pending OCR Reviews Banner
        if (dashboard.pendingOcrCount > 0) {
            item {
                OverseerCard(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
                    borderColor = WarningAmber.copy(alpha = 0.6f),
                    onClick = onOpenScanCenter
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.DocumentScanner, contentDescription = null, tint = WarningAmber)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "มีสลิป OCR รอตรวจสอบ ${dashboard.pendingOcrCount} รายการ",
                                style = MaterialTheme.typography.titleSmall,
                                color = WarningAmber
                            )
                            Text(
                                "แตะเพื่อตรวจสอบยอดเงิน หมวดหมู่ และประเภทภาษีเทียบกับภาพสลิป",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "ตรวจสอบ")
                    }
                }
            }
        }

        // Charts: Income vs Expense + Monthly Spending Trend
        item {
            OverseerCard {
                SectionHeaderRow(
                    title = "รายรับเทียบรายจ่าย (6 เดือนล่าสุด)",
                    subtitle = "แยกเงินโอนระหว่างบัญชีออกจากการคำนวณแล้ว"
                )
                Spacer(Modifier.height(10.dp))
                IncomeExpenseBarChart(points = dashboard.sixMonthChart)
            }
        }

        item {
            OverseerCard {
                SectionHeaderRow(
                    title = "แนวโน้มรายจ่ายรายเดือน",
                    subtitle = "ติดตามความเปลี่ยนแปลงของการใช้จ่าย"
                )
                Spacer(Modifier.height(10.dp))
                SpendingTrendLineChart(points = dashboard.sixMonthChart)
            }
        }

        // Top Expense Categories & Budget Progress
        item {
            OverseerCard {
                SectionHeaderRow(title = "หมวดหมู่รายจ่ายสูงสุด (${dashboard.periodFilter.labelTh})")
                Spacer(Modifier.height(10.dp))
                if (dashboard.topExpenseCategories.isEmpty()) {
                    Text(
                        "ยังไม่มีรายจ่ายในช่วงเวลานี้",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val totalExp = dashboard.periodExpenseSatang.coerceAtLeast(1L)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        dashboard.topExpenseCategories.forEach { (cat, sum) ->
                            val ratio = (sum.toFloat() / totalExp.toFloat()).coerceIn(0f, 1f)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(cat?.nameTh ?: "ไม่ระบุหมวดหมู่", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${MoneyFormatter.formatThb(sum)} (${(ratio * 100).toInt()}%)",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { ratio },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                )
                            }
                        }
                    }
                }
            }
        }

        // Monthly Budget Progress + Tax Estimate Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OverseerCard(modifier = Modifier.weight(1f)) {
                    Text("งบประมาณเดือนนี้", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    if (dashboard.monthlyBudgetLimitSatang > 0L) {
                        val ratio = (dashboard.monthlyBudgetSpentSatang.toFloat() /
                            dashboard.monthlyBudgetLimitSatang.toFloat()).coerceIn(0f, 1f)
                        Text(
                            "${MoneyFormatter.formatCompactThb(dashboard.monthlyBudgetSpentSatang)} / ${MoneyFormatter.formatCompactThb(dashboard.monthlyBudgetLimitSatang)}",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { ratio },
                            color = if (ratio >= 0.9f) ExpenseCoral else MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    } else {
                        Text(
                            "ยังไม่ได้ตั้งงบประมาณเดือนนี้",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OverseerCard(
                    modifier = Modifier.weight(1f),
                    onClick = onOpenTaxTab
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Calculate, contentDescription = null, tint = TransferCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("ประมาณการภาษี", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(6.dp))
                    if (taxReport != null) {
                        val value = if (taxReport.netTaxPayableSatang > 0L) {
                            "ชำระเพิ่ม ${MoneyFormatter.formatCompactThb(taxReport.netTaxPayableSatang)}"
                        } else if (taxReport.netTaxRefundSatang > 0L) {
                            "ขอคืน ${MoneyFormatter.formatCompactThb(taxReport.netTaxRefundSatang)}"
                        } else {
                            "฿0.00 (ยกเว้น)"
                        }
                        Text(
                            value,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (taxReport.netTaxPayableSatang > 0L) WarningAmber else IncomeEmerald
                        )
                        Text(
                            "ปีภาษี ${taxReport.taxYearBe}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Upcoming Payment Reminders
        if (upcomingBills.isNotEmpty() || upcomingDebts.isNotEmpty()) {
            item {
                OverseerCard {
                    SectionHeaderRow(
                        title = "การแจ้งเตือนที่กำลังจะมาถึง",
                        subtitle = "บิลประจำและกำหนดชำระหนี้"
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        upcomingBills.forEach { bill ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.NotificationsActive, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(bill.title, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "ครบกำหนด ${ThaiDateFormatter.formatDate(bill.nextDueEpochMillis, dateFormat)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(MoneyFormatter.formatThb(bill.amountSatang), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        upcomingDebts.forEach { debt ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("${debt.title} (${debt.counterpartyName})", style = MaterialTheme.typography.bodyMedium)
                                    debt.dueEpochMillis?.let {
                                        Text(
                                            "ครบกำหนด ${ThaiDateFormatter.formatDate(it, dateFormat)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(MoneyFormatter.formatThb(debt.remainingSatang), style = MaterialTheme.typography.labelLarge, color = WarningAmber)
                            }
                        }
                    }
                }
            }
        }

        // Offline Financial Intelligence
        item {
            OverseerCard {
                SectionHeaderRow(
                    title = "วิเคราะห์การเงินอัจฉริยะ (ออฟไลน์)",
                    subtitle = "อ้างอิงจากรายการจริงในฐานข้อมูลของคุณ",
                    trailing = {
                        Icon(Icons.Rounded.AutoGraph, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                )
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    dashboard.insights.forEach { insight ->
                        val accent = when (insight.severity) {
                            InsightSeverity.POSITIVE -> IncomeEmerald
                            InsightSeverity.WARNING -> WarningAmber
                            InsightSeverity.INFO -> TransferCyan
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(insight.title, style = MaterialTheme.typography.labelLarge, color = accent)
                            Text(insight.message, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                insight.evidenceSummary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Recent Transactions
        item {
            SectionHeaderRow(
                title = "รายการล่าสุด",
                trailing = {
                    TextButton(onClick = onOpenTransactions) {
                        Text("ดูทั้งหมด")
                    }
                }
            )
        }

        if (transactions.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Rounded.AccountBalanceWallet,
                    title = "ยังไม่มีรายการธุรกรรม",
                    description = "เริ่มต้นด้วยการบันทึกรายรับ-รายจ่าย หรือเชื่อมต่อโฟลเดอร์ K PLUS เพื่อสแกนสลิปอัตโนมัติ",
                    actionLabel = "บันทึกรายการแรก",
                    onAction = onOpenAddTransaction
                )
            }
        } else {
            items(transactions.take(6), key = { it.id }) { tx ->
                val cat = categoryMap[tx.categoryId]
                val acc = accountMap[tx.accountId]
                OverseerCard(onClick = onOpenTransactions) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (tx.type == TransactionType.INTERNAL_TRANSFER) Icons.Rounded.SwapHoriz
                                else categoryIconForKey(cat?.iconKey ?: ""),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
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
                                text = "${cat?.nameTh ?: tx.type.labelTh} • ${acc?.name ?: "-"} • ${ThaiDateFormatter.formatDate(tx.timestampEpochMillis, dateFormat)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
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
        item { Spacer(Modifier.height(12.dp)) }
    }

    if (showCustomDatePicker) {
        val pickerState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showCustomDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onSelectPeriod(
                        PeriodFilter.CUSTOM,
                        pickerState.selectedStartDateMillis,
                        pickerState.selectedEndDateMillis?.plus(86_399_999L)
                    )
                    showCustomDatePicker = false
                }) { Text("ตกลง") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDatePicker = false }) { Text("ยกเลิก") }
            }
        ) {
            DateRangePicker(state = pickerState, modifier = Modifier.height(420.dp))
        }
    }

    if (showBalanceExplainDialog) {
        AlertDialog(
            onDismissRequest = { showBalanceExplainDialog = false },
            title = { Text("หลักการคำนวณยอดเงิน") },
            text = {
                Text(
                    "1. ยอดคงเหลือรวม = ยอดยกมา + รายรับ/เงินกู้/รีฟันด์ - รายจ่าย/ชำระหนี้ ของทุกบัญชีที่เปิดใช้งาน\n" +
                        "2. รายการโอนระหว่างบัญชีตนเองจะย้ายยอดจากบัญชีต้นทางไปบัญชีปลายทางโดยไม่ถูกนับซ้ำเป็นรายรับหรือรายจ่าย\n" +
                        "3. ตัวเลขทั้งหมดจัดเก็บเป็นหน่วยสตางค์ (Integer Satang) เพื่อความแม่นยำ 100%"
                )
            },
            confirmButton = {
                TextButton(onClick = { showBalanceExplainDialog = false }) { Text("เข้าใจแล้ว") }
            }
        )
    }
}
