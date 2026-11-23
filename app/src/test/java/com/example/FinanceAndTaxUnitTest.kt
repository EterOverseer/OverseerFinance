package com.example

import com.example.data.local.AccountEntity
import com.example.data.local.AccountType
import com.example.data.local.DeductionType
import com.example.data.local.FilingStatus
import com.example.data.local.TaxDeductionEntity
import com.example.data.local.TaxProfileEntity
import com.example.data.local.TaxSection
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import com.example.data.local.WithholdingRecordEntity
import com.example.data.repository.FinanceRepository
import com.example.domain.FinancialIntelligenceEngine
import com.example.domain.ImageHashing
import com.example.domain.MoneyFormatter
import com.example.domain.OcrTextLine
import com.example.domain.ThaiBankSlipParser
import com.example.domain.ThaiDateFormatter
import com.example.domain.ThaiTaxEngine
import com.example.domain.ThaiTaxRulesCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class FinanceAndTaxUnitTest {

    @Test
    fun `satang formatter and parser avoid floating point drift`() {
        val satang = MoneyFormatter.parseThbToSatang("1,234,567.89")
        assertEquals(123_456_789L, satang)
        assertEquals("฿1,234,567.89", MoneyFormatter.formatThb(satang!!))
        assertEquals("+฿1,234,567.89", MoneyFormatter.formatThb(satang, showSign = true))
        assertEquals("-฿50.00", MoneyFormatter.formatThb(-5_000L))
    }

    @Test
    fun `internal transfer preserves total net balance across own accounts`() {
        val kbank = AccountEntity(id = "a1", name = "KBank", accountType = AccountType.KBANK, openingBalanceSatang = 100_000L)
        val cash = AccountEntity(id = "a2", name = "Cash", accountType = AccountType.CASH, openingBalanceSatang = 0L)
        val txs = listOf(
            TransactionEntity(amountSatang = 50_000L, type = TransactionType.INCOME, timestampEpochMillis = 1L, description = "Income", accountId = "a1"),
            TransactionEntity(amountSatang = 20_000L, type = TransactionType.EXPENSE, timestampEpochMillis = 2L, description = "Lunch", accountId = "a1"),
            TransactionEntity(amountSatang = 30_000L, type = TransactionType.INTERNAL_TRANSFER, timestampEpochMillis = 3L, description = "ATM", accountId = "a1", toAccountId = "a2"),
            TransactionEntity(amountSatang = 5_000L, type = TransactionType.REFUND, timestampEpochMillis = 4L, description = "Refund", accountId = "a2")
        )
        assertEquals(100_000L, FinanceRepository.computeAccountBalanceSatang(kbank, txs))
        assertEquals(35_000L, FinanceRepository.computeAccountBalanceSatang(cash, txs))
        assertEquals(135_000L, FinanceRepository.computeAccountBalanceSatang(kbank, txs) + FinanceRepository.computeAccountBalanceSatang(cash, txs))
    }

    @Test
    fun `thai bank slip parser extracts BE date, time, amount, ref, sender and recipient`() {
        val lines = listOf(
            OcrTextLine("K PLUS", 0.99f),
            OcrTextLine("โอนเงินสำเร็จ", 0.97f),
            OcrTextLine("23 พ.ย. 69 14:35 น.", 0.96f),
            OcrTextLine("จาก นายทดสอบ ระบบดี", 0.92f),
            OcrTextLine("xxx-x-x1234-x", 0.90f),
            OcrTextLine("ไปยัง ร้านกาแฟตัวอย่าง", 0.94f),
            OcrTextLine("จำนวน: 1,450.50 บาท", 0.98f),
            OcrTextLine("ค่าธรรมเนียม 0.00 บาท", 0.97f),
            OcrTextLine("เลขที่รายการ: 016327143500123456", 0.95f)
        )
        val parsed = ThaiBankSlipParser.parse(lines)
        assertEquals("KBank (K PLUS)", parsed.detectedBank)
        assertEquals(145_050L, parsed.amountSatang)
        assertEquals("14:35", parsed.timeText)
        assertEquals("นายทดสอบ ระบบดี", parsed.senderName)
        assertEquals("ร้านกาแฟตัวอย่าง", parsed.recipientName)
        assertEquals("016327143500123456", parsed.paymentReference)
        assertEquals(LocalDate.of(2026, 11, 23), ThaiDateFormatter.toBangkokDate(parsed.dateEpochMillis!!))
        assertFalse(parsed.needsReview)
    }

    @Test
    fun `thai bank slip parser never invents missing fields and flags review`() {
        val parsed = ThaiBankSlipParser.parse(listOf(OcrTextLine("ข้อความไม่สมบูรณ์", 0.40f)))
        assertNull(parsed.amountSatang)
        assertNull(parsed.dateEpochMillis)
        assertTrue(parsed.needsReview)
    }

    @Test
    fun `sha256 and perceptual hash detect exact and similar images reliably`() {
        val sha1 = ImageHashing.sha256("hello-slip-1".toByteArray())
        val sha2 = ImageHashing.sha256("hello-slip-1".toByteArray())
        val sha3 = ImageHashing.sha256("hello-slip-2".toByteArray())
        assertEquals(sha1, sha2)
        assertNotEquals(sha1, sha3)

        val patternA = Array(32) { x -> DoubleArray(32) { y -> ((x * 8 + y * 3) % 255).toDouble() } }
        val patternASlight = Array(32) { x -> DoubleArray(32) { y -> (((x * 8 + y * 3) % 255) + 2).toDouble() } }
        val patternInverted = Array(32) { x -> DoubleArray(32) { y -> (255 - ((x * 8 + y * 3) % 255)).toDouble() } }

        val hA = ImageHashing.computePerceptualHashFromMatrix(patternA)
        val hASlight = ImageHashing.computePerceptualHashFromMatrix(patternASlight)
        val hInv = ImageHashing.computePerceptualHashFromMatrix(patternInverted)

        assertEquals(0, ImageHashing.hammingDistance(hA, hA))
        assertTrue(ImageHashing.isVisuallySimilar(hA, hASlight))
        assertFalse(ImageHashing.isVisuallySimilar(hA, hInv))
    }

    @Test
    fun `thai progressive marginal tax brackets compute exact Revenue Department values`() {
        val brackets = ThaiTaxRulesCatalog.STANDARD_BRACKETS
        // 150,000 THB -> 0
        assertEquals(0L, ThaiTaxEngine.computeProgressiveMarginalTax(15_000_000L, brackets).first)
        // 300,000 THB -> 150,000 * 5% = 7,500 THB
        assertEquals(750_000L, ThaiTaxEngine.computeProgressiveMarginalTax(30_000_000L, brackets).first)
        // 500,000 THB -> 7,500 + 200,000 * 10% = 27,500 THB
        assertEquals(2_750_000L, ThaiTaxEngine.computeProgressiveMarginalTax(50_000_000L, brackets).first)
        // 1,000,000 THB -> 27,500 + 250k*15%(37,500) + 250k*20%(50,000) = 115,000 THB
        assertEquals(11_500_000L, ThaiTaxEngine.computeProgressiveMarginalTax(100_000_000L, brackets).first)
        // 5,000,000 THB -> 115,000 + 1M*25%(250,000) + 3M*30%(900,000) = 1,265,000 THB
        assertEquals(126_500_000L, ThaiTaxEngine.computeProgressiveMarginalTax(500_000_000L, brackets).first)
        // 6,000,000 THB -> 1,265,000 + 1M*35%(350,000) = 1,615,000 THB
        assertEquals(161_500_000L, ThaiTaxEngine.computeProgressiveMarginalTax(600_000_000L, brackets).first)
    }

    @Test
    fun `tax engine respects user-confirmed deductions, excludes transfers, and reconciles withholding`() {
        val yearBe = 2569
        val ceTs = LocalDateTime.of(2026, 6, 15, 10, 0).atZone(ThaiDateFormatter.BANGKOK_ZONE).toInstant().toEpochMilli()
        val rule = ThaiTaxRulesCatalog.createRuleEntityForYear(yearBe)
        val profile = TaxProfileEntity(taxYearBe = yearBe, filingStatus = FilingStatus.SINGLE)

        val txs = listOf(
            // 600,000 THB salary 40(1)
            TransactionEntity(amountSatang = 60_000_000L, type = TransactionType.INCOME, timestampEpochMillis = ceTs, description = "Salary", accountId = "a1", taxSection = TaxSection.SEC_40_1),
            // 500,000 THB internal transfer -> MUST NOT be taxed!
            TransactionEntity(amountSatang = 50_000_000L, type = TransactionType.INTERNAL_TRANSFER, timestampEpochMillis = ceTs, description = "Own Transfer", accountId = "a1", toAccountId = "a2")
        )
        val deductions = listOf(
            TaxDeductionEntity(taxYearBe = yearBe, deductionType = DeductionType.SOCIAL_SECURITY, label = "SSO", amountSatang = 900_000L, maxAllowedSatang = 900_000L, isUserConfirmed = true),
            // Unconfirmed deduction MUST NOT be applied automatically
            TaxDeductionEntity(taxYearBe = yearBe, deductionType = DeductionType.RMF, label = "RMF", amountSatang = 5_000_000L, maxAllowedSatang = 50_000_000L, isUserConfirmed = false)
        )
        val withheld = listOf(
            WithholdingRecordEntity(taxYearBe = yearBe, payerName = "Company A", incomeAmountSatang = 60_000_000L, withheldSatang = 2_500_000L)
        )

        val report = ThaiTaxEngine.calculate(yearBe, rule, profile, txs, deductions, withheld)
        // Assessable = 600k, Exp 40(1) = 100k, Personal = 60k, SSO = 9k => Net = 431,000 THB
        assertEquals(60_000_000L, report.totalAssessableIncomeSatang)
        assertEquals(50_000_000L, report.nonTaxableExcludedSatang)
        assertEquals(10_000_000L, report.standardExpenseDeductionSatang)
        assertEquals(6_000_000L, report.personalAndFamilyAllowanceSatang)
        assertEquals(900_000L, report.userConfirmedDeductionsSatang)
        assertEquals(43_100_000L, report.netTaxableIncomeSatang)
        // Tax on 431k = 150k*0 + 150k*5%(7500) + 131k*10%(13100) = 20,600 THB
        assertEquals(2_060_000L, report.progressiveTaxSatang)
        // Withheld 25,000 THB - 20,600 THB = 4,400 THB refund
        assertEquals(0L, report.netTaxPayableSatang)
        assertEquals(440_000L, report.netTaxRefundSatang)
    }

    @Test
    fun `financial intelligence refuses to fabricate insights on insufficient data`() {
        val insights = FinancialIntelligenceEngine.analyze(
            transactions = emptyList(),
            categories = emptyList(),
            budgets = emptyList(),
            recurring = emptyList(),
            totalAvailableBalanceSatang = 0L
        )
        assertEquals(1, insights.size)
        assertEquals("insufficient_data", insights.first().id)
    }
}
