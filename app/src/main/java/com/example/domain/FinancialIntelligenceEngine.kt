package com.example.domain

import com.example.data.local.BudgetEntity
import com.example.data.local.CategoryEntity
import com.example.data.local.RecurringTransactionEntity
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth

enum class InsightSeverity { INFO, POSITIVE, WARNING }

data class FinancialInsight(
    val id: String,
    val title: String,
    val message: String,
    val severity: InsightSeverity,
    val evidenceSummary: String
)

/**
 * Local deterministic rule-based analytics engine.
 * Never fabricates insights from insufficient data; every insight cites real stored records.
 */
object FinancialIntelligenceEngine {

    fun analyze(
        transactions: List<TransactionEntity>,
        categories: List<CategoryEntity>,
        budgets: List<BudgetEntity>,
        recurring: List<RecurringTransactionEntity>,
        totalAvailableBalanceSatang: Long,
        referenceYearMonth: String = ThaiDateFormatter.currentYearMonth()
    ): List<FinancialInsight> {
        val verifiedOrAll = transactions.filter { it.type.affectsIncomeExpense }
        if (verifiedOrAll.size < 2) {
            return listOf(
                FinancialInsight(
                    id = "insufficient_data",
                    title = "รอข้อมูลเพิ่มเพื่อวิเคราะห์แนวโน้ม",
                    message = "บันทึกหรือสแกนรายการเพิ่มอีกเล็กน้อยเพื่อให้ระบบวิเคราะห์พฤติกรรมการใช้จ่ายและกระแสเงินสดอย่างแม่นยำ",
                    severity = InsightSeverity.INFO,
                    evidenceSummary = "รายการที่มีผลต่อรายรับ-รายจ่ายปัจจุบัน: ${verifiedOrAll.size} รายการ"
                )
            )
        }

        val insights = mutableListOf<FinancialInsight>()
        val categoryMap = categories.associateBy { it.id }

        val currentYm = YearMonth.parse(referenceYearMonth)
        val prevYm = currentYm.minusMonths(1)

        val currentMonthTx = verifiedOrAll.filter {
            val d = ThaiDateFormatter.toBangkokDate(it.timestampEpochMillis)
            d.year == currentYm.year && d.monthValue == currentYm.monthValue
        }
        val prevMonthTx = verifiedOrAll.filter {
            val d = ThaiDateFormatter.toBangkokDate(it.timestampEpochMillis)
            d.year == prevYm.year && d.monthValue == prevYm.monthValue
        }

        val currentIncome = currentMonthTx.filter { it.type == TransactionType.INCOME }.sumOf { it.amountSatang }
        val currentExpense = currentMonthTx.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountSatang }
        val monthlySavings = currentIncome - currentExpense

        // 1. Largest expense category this month
        val expenseByCat = currentMonthTx
            .filter { it.type == TransactionType.EXPENSE }
            .groupBy { it.categoryId }
            .mapValues { (_, list) -> list.sumOf { it.amountSatang } }

        val topCategoryEntry = expenseByCat.maxByOrNull { it.value }
        if (topCategoryEntry != null && topCategoryEntry.value > 0L) {
            val catName = categoryMap[topCategoryEntry.key]?.nameTh ?: "ไม่ระบุหมวดหมู่"
            val sharePct = if (currentExpense > 0L) {
                ((topCategoryEntry.value * 100L) / currentExpense).toInt()
            } else 0
            insights.add(
                FinancialInsight(
                    id = "top_category",
                    title = "หมวดหมู่รายจ่ายสูงสุดเดือนนี้",
                    message = "\"$catName\" เป็นค่าใช้จ่ายอันดับ 1 ของคุณ (${MoneyFormatter.formatThb(topCategoryEntry.value)} หรือ $sharePct% ของรายจ่ายเดือนนี้)",
                    severity = InsightSeverity.INFO,
                    evidenceSummary = "คำนวณจาก ${currentMonthTx.count { it.type == TransactionType.EXPENSE && it.categoryId == topCategoryEntry.key }} รายการ"
                )
            )
        }

        // 2. Month-over-Month category change
        val prevExpenseByCat = prevMonthTx
            .filter { it.type == TransactionType.EXPENSE }
            .groupBy { it.categoryId }
            .mapValues { (_, list) -> list.sumOf { it.amountSatang } }

        for ((catId, curVal) in expenseByCat) {
            val prevVal = prevExpenseByCat[catId] ?: 0L
            if (prevVal >= 10_000L && curVal > 0L) { // >= 100 THB baseline to avoid noisy small fractions
                val pctDiff = BigDecimal.valueOf(curVal - prevVal)
                    .multiply(BigDecimal(100))
                    .divide(BigDecimal.valueOf(prevVal), 0, RoundingMode.HALF_UP)
                    .toInt()
                if (kotlin.math.abs(pctDiff) >= 15) {
                    val catName = categoryMap[catId]?.nameTh ?: "หมวดหมู่"
                    val verb = if (pctDiff > 0) "เพิ่มขึ้น $pctDiff%" else "ลดลง ${-pctDiff}%"
                    insights.add(
                        FinancialInsight(
                            id = "mom_$catId",
                            title = "แนวโน้มหมวด $catName",
                            message = "ค่าใช้จ่ายหมวด$catName $verb เมื่อเทียบกับเดือนก่อน (${MoneyFormatter.formatThb(curVal)} vs ${MoneyFormatter.formatThb(prevVal)})",
                            severity = if (pctDiff > 0) InsightSeverity.WARNING else InsightSeverity.POSITIVE,
                            evidenceSummary = "เปรียบเทียบเดือน ${ThaiDateFormatter.formatMonthYearTh(prevYm.toString())} กับปัจจุบัน"
                        )
                    )
                    break
                }
            }
        }

        // 3. Budget deviations
        val monthBudgets = budgets.filter { it.isActive && it.yearMonth == referenceYearMonth }
        for (budget in monthBudgets) {
            if (budget.limitSatang <= 0L) continue
            val spent = if (budget.categoryId == null) currentExpense else (expenseByCat[budget.categoryId] ?: 0L)
            val usedPct = ((spent * 100L) / budget.limitSatang).toInt()
            if (usedPct >= budget.alertThresholdPercent) {
                insights.add(
                    FinancialInsight(
                        id = "budget_${budget.id}",
                        title = if (usedPct >= 100) "เกินงบประมาณ: ${budget.name}" else "ใกล้ถึงเพดานงบ: ${budget.name}",
                        message = "ใช้ไปแล้ว $usedPct% (${MoneyFormatter.formatThb(spent)} / ${MoneyFormatter.formatThb(budget.limitSatang)})",
                        severity = InsightSeverity.WARNING,
                        evidenceSummary = "อ้างอิงจากงบประมาณประจำเดือน $referenceYearMonth"
                    )
                )
            }
        }

        // 4. Recurring bills & estimated remaining balance
        val activeRecurringTotal = recurring.filter { it.isActive && it.type == TransactionType.EXPENSE }
            .sumOf { it.amountSatang }
        if (activeRecurringTotal > 0L) {
            val projectedRemaining = totalAvailableBalanceSatang - activeRecurringTotal
            insights.add(
                FinancialInsight(
                    id = "recurring_forecast",
                    title = "ภาระรายจ่ายประจำและยอดคงเหลือคาดการณ์",
                    message = "รายการประจำเดือนนี้รวม ${MoneyFormatter.formatThb(activeRecurringTotal)} คาดว่าหลังหักรายการประจำจะเหลือยอดรวม ${MoneyFormatter.formatThb(projectedRemaining)}",
                    severity = if (projectedRemaining < 0L) InsightSeverity.WARNING else InsightSeverity.INFO,
                    evidenceSummary = "คำนวณจาก ${recurring.count { it.isActive }} รายการประจำที่เปิดใช้งาน"
                )
            )
        }

        // 5. Frequent recipient
        val recipientCounts = currentMonthTx
            .filter { it.type == TransactionType.EXPENSE && it.recipientName.isNotBlank() }
            .groupBy { it.recipientName.trim() }
            .maxByOrNull { it.value.size }
        if (recipientCounts != null && recipientCounts.value.size >= 2) {
            val totalToRecipient = recipientCounts.value.sumOf { it.amountSatang }
            insights.add(
                FinancialInsight(
                    id = "freq_recipient",
                    title = "ผู้รับโอนบ่อยเดือนนี้",
                    message = "คุณโอนให้ \"${recipientCounts.key}\" จำนวน ${recipientCounts.value.size} ครั้ง รวม ${MoneyFormatter.formatThb(totalToRecipient)}",
                    severity = InsightSeverity.INFO,
                    evidenceSummary = "อ้างอิงจากรายการโอนออกในเดือนปัจจุบัน"
                )
            )
        }

        // 6. Monthly savings summary
        if (currentIncome > 0L) {
            val savingsRate = ((monthlySavings * 100L) / currentIncome).toInt()
            insights.add(
                FinancialInsight(
                    id = "savings_rate",
                    title = "สรุปเงินออมสุทธิเดือนนี้",
                    message = if (monthlySavings >= 0L) {
                        "กระแสเงินสดสุทธิ +${MoneyFormatter.formatThb(monthlySavings)} (อัตราออม $savingsRate% ของรายรับ)"
                    } else {
                        "เดือนนี้รายจ่ายสูงกว่ารายรับ ${MoneyFormatter.formatThb(-monthlySavings)}"
                    },
                    severity = if (monthlySavings >= 0L) InsightSeverity.POSITIVE else InsightSeverity.WARNING,
                    evidenceSummary = "รายรับ ${MoneyFormatter.formatThb(currentIncome)} • รายจ่าย ${MoneyFormatter.formatThb(currentExpense)}"
                )
            )
        }

        return insights
    }
}
