package com.example

import com.example.data.local.DeductionType
import com.example.data.local.FilingStatus
import com.example.data.local.OpenApiSettingsEntity
import com.example.data.local.RdOpenApiProviderModel
import com.example.data.local.ReviewStatus
import com.example.data.local.TaxDeductionEntity
import com.example.data.local.TaxFilingState
import com.example.data.local.TaxFormType
import com.example.data.local.TaxProfileEntity
import com.example.data.local.TaxSection
import com.example.data.local.TaxpayerIdentityEntity
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import com.example.domain.ThaiTaxEngine
import com.example.domain.ThaiTaxRulesCatalog
import com.example.tax.api.FutureRdOpenApiProvider
import com.example.tax.api.RdAccessToken
import com.example.tax.api.RdApiTransport
import com.example.tax.api.RdTransportHttpResult
import com.example.tax.api.SubmissionErrorCode
import com.example.tax.api.TaxSubmissionRequest
import com.example.tax.engine.FormEligibilityCertainty
import com.example.tax.engine.ThaiTaxFilingEngine
import com.example.tax.reconciliation.BankCsvColumnMapping
import com.example.tax.reconciliation.BankMappingPreset
import com.example.tax.reconciliation.BankStatementReconciler
import com.example.tax.reconciliation.CsvCharsetOption
import com.example.tax.xml.RdCanonicalTaxReturn
import com.example.tax.xml.RdXmlPublicSerializer
import com.example.tax.xml.RdXmlSpecDescriptor
import com.example.tax.xml.RdXmlSpecificationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.time.LocalDateTime
import java.time.ZoneId

class TaxFilingEngineAndXmlTest {

    private fun bangkokEpoch(year: Int, month: Int, day: Int): Long =
        LocalDateTime.of(year, month, day, 12, 0)
            .atZone(ZoneId.of("Asia/Bangkok"))
            .toInstant()
            .toEpochMilli()

    private fun validTin13(): String {
        val base12 = "110070234567"
        var sum = 0
        for (i in 0 until 12) {
            sum += (base12[i] - '0') * (13 - i)
        }
        val check = (11 - (sum % 11)) % 10
        return "$base12$check"
    }

    @Test
    fun `thai 13-digit TIN checksum validates valid and rejects tampered numbers`() {
        val tin = validTin13()
        assertTrue(ThaiTaxFilingEngine.validateThaiTin13(tin))
        val badLast = ((tin.last() - '0' + 1) % 10).toString()
        assertFalse(ThaiTaxFilingEngine.validateThaiTin13(tin.dropLast(1) + badLast))
        assertFalse(ThaiTaxFilingEngine.validateThaiTin13("1111111111111"))
        assertFalse(ThaiTaxFilingEngine.validateThaiTin13("12345"))
    }

    @Test
    fun `form eligibility selects PND91 for 40(1) only and PND90 when 40(2)-40(8) present`() {
        val rule = ThaiTaxRulesCatalog.createRuleEntityForYear(2569)
        val profile = TaxProfileEntity(taxYearBe = 2569, filingStatus = FilingStatus.SINGLE)

        val salaryOnly = listOf(
            TransactionEntity(
                amountSatang = 60_000_000L,
                type = TransactionType.INCOME,
                timestampEpochMillis = bangkokEpoch(2026, 3, 15),
                description = "เงินเดือน",
                accountId = "acc1",
                taxSection = TaxSection.SEC_40_1,
                reviewStatus = ReviewStatus.VERIFIED
            )
        )
        val report91 = ThaiTaxEngine.calculate(2569, rule, profile, salaryOnly, emptyList(), emptyList())
        val elig91 = ThaiTaxFilingEngine.determineFormEligibility(2569, profile, salaryOnly, emptyList(), report91)
        assertEquals(TaxFormType.PND91, elig91.recommendedForm)
        assertEquals(FormEligibilityCertainty.ELIGIBLE, elig91.certainty)

        val mixedIncome = salaryOnly + TransactionEntity(
            amountSatang = 8_000_000L, // 80,000 THB in Feb under 40(8) -> also triggers PND94 half-year check
            type = TransactionType.INCOME,
            timestampEpochMillis = bangkokEpoch(2026, 2, 20),
            description = "ขายสินค้าออนไลน์",
            accountId = "acc1",
            taxSection = TaxSection.SEC_40_8,
            reviewStatus = ReviewStatus.VERIFIED
        )
        val report90 = ThaiTaxEngine.calculate(2569, rule, profile, mixedIncome, emptyList(), emptyList())
        val elig90 = ThaiTaxFilingEngine.determineFormEligibility(2569, profile, mixedIncome, emptyList(), report90)
        assertEquals(TaxFormType.PND90, elig90.recommendedForm)
        assertTrue(elig90.isPnd94HalfYearRequired)
        assertFalse(elig90.pnd94SpecVerified)
    }

    @Test
    fun `unconfirmed deductions are excluded until user explicitly confirms`() {
        val rule = ThaiTaxRulesCatalog.createRuleEntityForYear(2569)
        val profile = TaxProfileEntity(taxYearBe = 2569)
        val salary = listOf(
            TransactionEntity(
                amountSatang = 50_000_000L,
                type = TransactionType.INCOME,
                timestampEpochMillis = bangkokEpoch(2026, 5, 1),
                description = "เงินเดือน",
                accountId = "acc1",
                taxSection = TaxSection.SEC_40_1
            )
        )
        val unconfirmed = listOf(
            TaxDeductionEntity(
                taxYearBe = 2569,
                deductionType = DeductionType.RMF,
                label = "RMF Unconfirmed",
                amountSatang = 5_000_000L,
                maxAllowedSatang = 50_000_000L,
                isUserConfirmed = false
            )
        )
        val report = ThaiTaxEngine.calculate(2569, rule, profile, salary, unconfirmed, emptyList())
        assertEquals(0L, report.userConfirmedDeductionsSatang)
    }

    @Test
    fun `built-in XML registry blocks unverified XML export and never fabricates tags`() {
        val canonical = RdCanonicalTaxReturn(
            taxYearBe = 2569,
            formType = TaxFormType.PND91,
            filingVersion = 1,
            filingStatus = FilingStatus.SINGLE,
            identity = TaxpayerIdentityEntity(
                taxYearBe = 2569,
                tin13 = validTin13(),
                firstNameTh = "สมชาย",
                lastNameTh = "ใจดี",
                province = "กรุงเทพมหานคร",
                postalCode = "10110"
            ),
            incomeBySectionSatang = mapOf(TaxSection.SEC_40_1 to 50_000_000L),
            totalAssessableIncomeSatang = 50_000_000L,
            expenseDeductionSatang = 10_000_000L,
            personalAndFamilyAllowanceSatang = 6_000_000L,
            itemizedDeductionsSatang = emptyMap(),
            userConfirmedDeductionsSatang = 0L,
            netTaxableIncomeSatang = 34_000_000L,
            calculatedTaxSatang = 1_150_000L,
            withholdingTaxSatang = 0L,
            payableTaxSatang = 1_150_000L,
            refundTaxSatang = 0L,
            rulesetVersion = "RD-2569-v1"
        )

        val unverifiedSpec = RdXmlSpecificationRegistry.getSpecFor(TaxFormType.PND91, 2569)
        val blockedResult = RdXmlPublicSerializer.generate(canonical, unverifiedSpec)
        assertFalse(blockedResult.success)
        assertTrue(blockedResult.isBlockedByMissingOfficialSpec)
        assertTrue(blockedResult.xmlContent.isEmpty())

        // Verify verified spec fixture produces strict UTF-8 XML and rejects PND91 with 40(8) income
        val fixtureSpec = RdXmlSpecDescriptor(
            formType = TaxFormType.PND91,
            taxYearBe = 2569,
            specVersion = "FIXTURE-XSD-2569",
            officialReferenceUrl = RdXmlSpecificationRegistry.RD_XML_PUBLIC_SPEC_URL,
            isOfficiallyVerified = true,
            rootElementName = "PND91Return",
            namespaceUri = "https://efiling.rd.go.th/schema/pnd91",
            elementTagMap = mapOf(
                "tin13" to "TIN",
                "firstNameTh" to "FirstName",
                "lastNameTh" to "LastName",
                "totalAssessable" to "TotalAssessableIncome",
                "netTaxable" to "NetTaxableIncome"
            )
        )
        val okResult = RdXmlPublicSerializer.generate(canonical.copy(identity = canonical.identity.copy(firstNameTh = "สมชาย & ทีม")), fixtureSpec)
        assertTrue(okResult.success)
        assertTrue(okResult.xmlContent.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(okResult.xmlContent.contains("<FirstName>สมชาย &amp; ทีม</FirstName>"))
        assertTrue(okResult.xmlSha256.length == 64)

        val ineligible = canonical.copy(
            incomeBySectionSatang = mapOf(TaxSection.SEC_40_1 to 30_000_000L, TaxSection.SEC_40_8 to 5_000_000L)
        )
        val rejected = RdXmlPublicSerializer.generate(ineligible, fixtureSpec)
        assertFalse(rejected.success)
        assertTrue(rejected.validationErrors.any { it.code == "INELIGIBLE_FORM_PND91" })
    }

    @Test
    fun `FutureRdOpenApiProvider enforces disabled default, consent, https, token expiry, idempotency, and error states`() = runTest {
        val dummyCanonical = RdCanonicalTaxReturn(
            taxYearBe = 2569,
            formType = TaxFormType.PND91,
            filingVersion = 1,
            filingStatus = FilingStatus.SINGLE,
            identity = TaxpayerIdentityEntity(taxYearBe = 2569, tin13 = validTin13(), firstNameTh = "A", lastNameTh = "B", province = "BKK", postalCode = "10110"),
            incomeBySectionSatang = mapOf(TaxSection.SEC_40_1 to 50_000_000L),
            totalAssessableIncomeSatang = 50_000_000L,
            expenseDeductionSatang = 10_000_000L,
            personalAndFamilyAllowanceSatang = 6_000_000L,
            itemizedDeductionsSatang = emptyMap(),
            userConfirmedDeductionsSatang = 0L,
            netTaxableIncomeSatang = 34_000_000L,
            calculatedTaxSatang = 1_150_000L,
            withholdingTaxSatang = 0L,
            payableTaxSatang = 1_150_000L,
            refundTaxSatang = 0L,
            rulesetVersion = "RD-2569-v1"
        )
        val req = TaxSubmissionRequest(dummyCanonical, idempotencyKey = "idem-1")

        // 1. Disabled by default
        var settings = OpenApiSettingsEntity()
        var token: RdAccessToken? = RdAccessToken(accessToken = "secret-tok-123", expiresAtEpochMillis = 2_000_000L)
        assertFalse(token.toString().contains("secret-tok-123"))

        var mockStatus = 200
        val transport = object : RdApiTransport {
            override suspend fun postTaxReturn(
                baseUrl: String,
                endpointPath: String,
                bearerToken: String,
                idempotencyKey: String,
                providerModel: RdOpenApiProviderModel,
                jsonBody: String
            ) = RdTransportHttpResult(mockStatus, submissionRef = "SUB-1", confirmationRef = "CONF-1", errorMessage = "Invalid TIN")
        }
        val provider = FutureRdOpenApiProvider({ settings }, { token }, transport, { 1_000_000L })

        assertEquals(SubmissionErrorCode.PROVIDER_DISABLED, provider.submit(req).errorCode)

        // 2. Requires user consent
        settings = settings.copy(
            isEnabled = true,
            providerModel = RdOpenApiProviderModel.TAX_FILING_PROVIDER,
            baseUrl = "http://insecure.rd.go.th",
            userConsented = false
        )
        assertEquals(SubmissionErrorCode.USER_CONSENT_REQUIRED, provider.submit(req).errorCode)

        // 3. Requires HTTPS
        settings = settings.copy(userConsented = true)
        assertEquals(SubmissionErrorCode.INSECURE_TRANSPORT, provider.submit(req).errorCode)

        // 4. Rejects expired token
        settings = settings.copy(baseUrl = "https://sandbox-api.rd.go.th")
        token = RdAccessToken(accessToken = "tok", expiresAtEpochMillis = 900_000L)
        assertEquals(SubmissionErrorCode.MISSING_OR_EXPIRED_TOKEN, provider.submit(req).errorCode)

        // 5. HTTP error mapping & duplicate idempotency prevention
        token = RdAccessToken(accessToken = "tok", expiresAtEpochMillis = 2_000_000L)
        mockStatus = 401
        assertEquals(SubmissionErrorCode.UNAUTHORIZED, provider.submit(req).errorCode)
        mockStatus = 422
        val res422 = provider.submit(req)
        assertEquals(TaxFilingState.REJECTED, res422.resultingState)
        assertEquals(SubmissionErrorCode.VALIDATION_REJECTED, res422.errorCode)

        mockStatus = 200
        val res200 = provider.submit(req)
        assertTrue(res200.accepted)
        assertEquals(TaxFilingState.SUBMISSION_CONFIRMED, res200.resultingState)

        // Repeating same idempotencyKey must be blocked
        val dup = provider.submit(req)
        assertFalse(dup.accepted)
        assertEquals(SubmissionErrorCode.DUPLICATE_SUBMISSION, dup.errorCode)
    }

    @Test
    fun `CSV bank statement parser supports quoted Thai amounts and TIS-620 encoding`() {
        val csv = "วันที่,รายการ,ถอน,ฝาก,อ้างอิง\n23/11/2569,\"โอนค่าสินค้า, ลูกค้า A\",,\"12,500.50\",REF998\n"
        val tisBytes = csv.toByteArray(Charset.forName("windows-874"))
        val mapping = BankCsvColumnMapping.forPreset(BankMappingPreset.KBANK_CSV)
            .copy(charset = CsvCharsetOption.TIS_620)
        val (rows, invalid) = BankStatementReconciler.parseCsvBytes(tisBytes, mapping)
        assertEquals(0, invalid)
        assertEquals(1, rows.size)
        assertEquals(1_250_050L, rows.first().amountSatang)
        assertTrue(rows.first().isInflow)
        assertEquals("REF998", rows.first().reference)
        assertTrue(rows.first().description.contains("โอนค่าสินค้า"))
    }
}
