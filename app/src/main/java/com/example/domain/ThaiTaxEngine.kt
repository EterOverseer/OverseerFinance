package com.example.domain

import com.example.data.local.DeductionType
import com.example.data.local.FilingStatus
import com.example.data.local.TaxDeductionEntity
import com.example.data.local.TaxProfileEntity
import com.example.data.local.TaxRuleEntity
import com.example.data.local.TaxSection
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import com.example.data.local.WithholdingRecordEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode

@Serializable
data class TaxBracketRule(
    val lowerBoundInclusiveBaht: Long,
    val upperBoundInclusiveBaht: Long?, // null = infinity
    val ratePercent: Int
)

data class TaxBracketBreakdownItem(
    val label: String,
    val ratePercent: Int,
    val taxableInBracketSatang: Long,
    val taxAmountSatang: Long
)

data class TaxCalculationReport(
    val taxYearBe: Int,
    val ruleVersion: String,
    val sourceUrl: String,
    val incomeBySectionSatang: Map<TaxSection, Long>,
    val totalAssessableIncomeSatang: Long,
    val nonTaxableExcludedSatang: Long,
    val standardExpenseDeductionSatang: Long,
    val personalAndFamilyAllowanceSatang: Long,
    val userConfirmedDeductionsSatang: Long,
    val netTaxableIncomeSatang: Long,
    val progressiveTaxSatang: Long,
    val alternativeHalfPercentTaxSatang: Long,
    val usedAlternativeMethod: Boolean,
    val grossTaxCalculatedSatang: Long,
    val totalWithholdingTaxSatang: Long,
    val netTaxPayableSatang: Long,
    val netTaxRefundSatang: Long,
    val bracketBreakdown: List<TaxBracketBreakdownItem>,
    val disclaimerTh: String
)

object ThaiTaxRulesCatalog {
    const val OFFICIAL_RD_URL = "https://www.rd.go.th/26218.html"

    /**
     * Verified Revenue Department progressive marginal tax brackets (https://www.rd.go.th/26218.html)
     */
    val STANDARD_BRACKETS: List<TaxBracketRule> = listOf(
        TaxBracketRule(0L, 150_000L, 0),
        TaxBracketRule(150_001L, 300_000L, 5),
        TaxBracketRule(300_001L, 500_000L, 10),
        TaxBracketRule(500_001L, 750_000L, 15),
        TaxBracketRule(750_001L, 1_000_000L, 20),
        TaxBracketRule(1_000_001L, 2_000_000L, 25),
        TaxBracketRule(2_000_001L, 5_000_000L, 30),
        TaxBracketRule(5_000_001L, null, 35)
    )

    fun createRuleEntityForYear(taxYearBe: Int): TaxRuleEntity {
        return TaxRuleEntity(
            id = "rd_rule_$taxYearBe",
            taxYearBe = taxYearBe,
            ruleVersion = "RD-$taxYearBe-v1",
            sourceUrl = OFFICIAL_RD_URL,
            bracketsJson = Json.encodeToString(STANDARD_BRACKETS),
            personalAllowanceSatang = 6_000_000L, // 60,000 THB
            spouseAllowanceSatang = 6_000_000L, // 60,000 THB
            childAllowanceSatang = 3_000_000L, // 30,000 THB/child
            parentAllowanceSatang = 3_000_000L, // 30,000 THB/parent (max 4)
            disabilityAllowanceSatang = 6_000_000L, // 60,000 THB/person
            section401_402ExpenseRatePercent = 50,
            section401_402ExpenseCapSatang = 10_000_000L, // 100,000 THB
            notes = "อัตราภาษีเงินได้บุคคลธรรมดาแบบขั้นบันได กรมสรรพากร"
        )
    }
}

object ThaiTaxEngine {

    private val json = Json { ignoreUnknownKeys = true }

    const val DISCLAIMER_TH =
        "หมายเหตุสำคัญ: การคำนวณนี้เป็นการประมาณการเบื้องต้นเพื่อช่วยวางแผนภาษีเท่านั้น ไม่สามารถใช้แทนแบบแสดงรายการภาษีอย่างเป็นทางการหรือคำปรึกษาจากผู้เชี่ยวชาญด้านภาษี โปรดตรวจสอบเงื่อนไขล่าสุดกับกรมสรรพากร (rd.go.th) ก่อนยื่นแบบจริง"

    fun calculate(
        taxYearBe: Int,
        rule: TaxRuleEntity,
        profile: TaxProfileEntity,
        transactions: List<TransactionEntity>,
        confirmedDeductions: List<TaxDeductionEntity>,
        withholdingRecords: List<WithholdingRecordEntity>,
        additional408BusinessExpenseSatang: Long = 0L
    ): TaxCalculationReport {
        val taxYearCe = taxYearBe - 543
        val yearTransactions = transactions.filter { tx ->
            ThaiDateFormatter.toBangkokDate(tx.timestampEpochMillis).year == taxYearCe
        }

        // Only count transactions explicitly classified under taxable Section 40(1)-40(8)
        val incomeBySection = mutableMapOf<TaxSection, Long>()
        var nonTaxableExcluded = 0L

        for (tx in yearTransactions) {
            when {
                tx.type == TransactionType.INTERNAL_TRANSFER ||
                    tx.type == TransactionType.LOAN_RECEIVED ||
                    tx.type == TransactionType.REFUND ||
                    tx.type == TransactionType.DEBT_COLLECTION ||
                    tx.taxSection == TaxSection.NON_TAXABLE -> {
                    if (tx.type == TransactionType.INTERNAL_TRANSFER || tx.type.isCashInflow || tx.taxSection == TaxSection.NON_TAXABLE) {
                        nonTaxableExcluded += tx.amountSatang
                    }
                }
                tx.type == TransactionType.INCOME && tx.taxSection.isTaxableIncome -> {
                    incomeBySection[tx.taxSection] =
                        (incomeBySection[tx.taxSection] ?: 0L) + tx.amountSatang
                }
            }
        }

        val totalAssessable = incomeBySection.values.sum()

        // 40(1) + 40(2) combined standard expense: 50% capped at 100,000 THB
        val sec401And402 = (incomeBySection[TaxSection.SEC_40_1] ?: 0L) +
            (incomeBySection[TaxSection.SEC_40_2] ?: 0L)
        val exp401402 = minOf(
            percentageOfSatang(sec401And402, rule.section401_402ExpenseRatePercent),
            rule.section401_402ExpenseCapSatang
        )

        // 40(8) actual documented business expenses (if user recorded business costs)
        val sec408 = incomeBySection[TaxSection.SEC_40_8] ?: 0L
        val exp408 = additional408BusinessExpenseSatang.coerceIn(0L, sec408)
        val totalExpenses = exp401402 + exp408

        // Family & personal statutory allowances from confirmed TaxProfile
        val personal = rule.personalAllowanceSatang
        val spouse = if (profile.filingStatus != FilingStatus.SINGLE && profile.spouseNoIncome) {
            rule.spouseAllowanceSatang
        } else 0L
        val children = profile.childCount.coerceAtLeast(0) * rule.childAllowanceSatang
        val parents = profile.parentCareCount.coerceIn(0, 4) * rule.parentAllowanceSatang
        val disability = profile.disabilityCareCount.coerceAtLeast(0) * rule.disabilityAllowanceSatang
        val profileAllowances = personal + spouse + children + parents + disability

        // Additional user-confirmed deductions only (never auto-applied without user confirmation)
        val confirmedUserDeductions = confirmedDeductions
            .filter { it.isUserConfirmed }
            .sumOf { item ->
                if (item.deductionType == DeductionType.DONATION_EDUCATION_2X) {
                    item.amountSatang * 2L
                } else {
                    if (item.maxAllowedSatang > 0L) minOf(item.amountSatang, item.maxAllowedSatang)
                    else item.amountSatang
                }
            }

        val netTaxable = (totalAssessable - totalExpenses - profileAllowances - confirmedUserDeductions)
            .coerceAtLeast(0L)

        val brackets = runCatching {
            json.decodeFromString<List<TaxBracketRule>>(rule.bracketsJson)
        }.getOrElse { ThaiTaxRulesCatalog.STANDARD_BRACKETS }

        val (progressiveTax, breakdown) = computeProgressiveMarginalTax(netTaxable, brackets)

        // Section 48(2) alternative 0.5% rule on non-40(1) assessable income >= 120,000 THB
        // Only applies if 0.5% tax > progressive tax AND exceeds 5,000 THB waiver threshold
        val non401Income = totalAssessable - (incomeBySection[TaxSection.SEC_40_1] ?: 0L)
        val altTaxCandidate = if (non401Income >= 12_000_000L) {
            percentageOfSatangDecimal(non401Income, BigDecimal("0.005"))
        } else 0L
        val useAlt = altTaxCandidate > 500_000L && altTaxCandidate > progressiveTax
        val grossTax = if (useAlt) altTaxCandidate else progressiveTax

        val totalWithheld = withholdingRecords.sumOf { it.withheldSatang }
        val diff = grossTax - totalWithheld
        val payable = if (diff > 0L) diff else 0L
        val refund = if (diff < 0L) -diff else 0L

        return TaxCalculationReport(
            taxYearBe = taxYearBe,
            ruleVersion = rule.ruleVersion,
            sourceUrl = rule.sourceUrl,
            incomeBySectionSatang = incomeBySection.toMap(),
            totalAssessableIncomeSatang = totalAssessable,
            nonTaxableExcludedSatang = nonTaxableExcluded,
            standardExpenseDeductionSatang = totalExpenses,
            personalAndFamilyAllowanceSatang = profileAllowances,
            userConfirmedDeductionsSatang = confirmedUserDeductions,
            netTaxableIncomeSatang = netTaxable,
            progressiveTaxSatang = progressiveTax,
            alternativeHalfPercentTaxSatang = altTaxCandidate,
            usedAlternativeMethod = useAlt,
            grossTaxCalculatedSatang = grossTax,
            totalWithholdingTaxSatang = totalWithheld,
            netTaxPayableSatang = payable,
            netTaxRefundSatang = refund,
            bracketBreakdown = breakdown,
            disclaimerTh = DISCLAIMER_TH
        )
    }

    /**
     * Exact marginal progressive bracket tax computation in Satang.
     */
    fun computeProgressiveMarginalTax(
        netTaxableSatang: Long,
        brackets: List<TaxBracketRule>
    ): Pair<Long, List<TaxBracketBreakdownItem>> {
        var totalTax = 0L
        val items = mutableListOf<TaxBracketBreakdownItem>()

        for (bracket in brackets) {
            val lowerSatang = if (bracket.lowerBoundInclusiveBaht <= 1L) 0L
            else (bracket.lowerBoundInclusiveBaht - 1L) * 100L
            val upperSatang = bracket.upperBoundInclusiveBaht?.times(100L)

            val taxableInBracket = when {
                netTaxableSatang <= lowerSatang -> 0L
                upperSatang == null -> netTaxableSatang - lowerSatang
                else -> minOf(netTaxableSatang, upperSatang) - lowerSatang
            }.coerceAtLeast(0L)

            val taxInBracket = percentageOfSatang(taxableInBracket, bracket.ratePercent)
            totalTax += taxInBracket

            val label = if (bracket.upperBoundInclusiveBaht == null) {
                "มากกว่า ${String.format("%,d", bracket.lowerBoundInclusiveBaht - 1)} บาท"
            } else {
                "${String.format("%,d", bracket.lowerBoundInclusiveBaht)} - ${String.format("%,d", bracket.upperBoundInclusiveBaht)} บาท"
            }
            items.add(
                TaxBracketBreakdownItem(
                    label = label,
                    ratePercent = bracket.ratePercent,
                    taxableInBracketSatang = taxableInBracket,
                    taxAmountSatang = taxInBracket
                )
            )
        }
        return totalTax to items
    }

    private fun percentageOfSatang(satang: Long, percent: Int): Long {
        if (satang <= 0L || percent <= 0) return 0L
        return BigDecimal.valueOf(satang)
            .multiply(BigDecimal.valueOf(percent.toLong()))
            .divide(BigDecimal(100), 0, RoundingMode.HALF_UP)
            .longValueExact()
    }

    private fun percentageOfSatangDecimal(satang: Long, rate: BigDecimal): Long {
        if (satang <= 0L) return 0L
        return BigDecimal.valueOf(satang)
            .multiply(rate)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }
}
