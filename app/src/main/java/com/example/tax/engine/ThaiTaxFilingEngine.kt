package com.example.tax.engine

import com.example.data.local.DeductionType
import com.example.data.local.DocumentVerificationStatus
import com.example.data.local.FilingStatus
import com.example.data.local.ReviewStatus
import com.example.data.local.TaxDeductionEntity
import com.example.data.local.TaxDocumentCategory
import com.example.data.local.TaxDocumentEntity
import com.example.data.local.TaxFormType
import com.example.data.local.TaxProfileEntity
import com.example.data.local.TaxRuleEntity
import com.example.data.local.TaxSection
import com.example.data.local.TaxpayerIdentityEntity
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import com.example.data.local.WithholdingRecordEntity
import com.example.domain.TaxCalculationReport
import com.example.domain.ThaiDateFormatter

enum class FormEligibilityCertainty(val labelTh: String) {
    ELIGIBLE("เข้าเกณฑ์ยื่นแบบ"),
    UNCERTAIN("ยังไม่สามารถสรุปแบบที่แน่ชัด"),
    BELOW_STATUTORY_THRESHOLD("ต่ำกว่าเกณฑ์บังคับยื่น (อาจยื่นเพื่อขอคืนภาษีได้)")
}

data class FormEligibilityAssessment(
    val recommendedForm: TaxFormType?,
    val certainty: FormEligibilityCertainty,
    val reasonsTh: List<String>,
    val blockersTh: List<String>,
    val isPnd94HalfYearRequired: Boolean,
    val pnd94HalfYear405To408Satang: Long,
    val pnd94SpecVerified: Boolean = false
)

enum class ChecklistStatus(val labelTh: String) {
    PASS("ผ่าน"),
    WARN("ควรตรวจสอบ"),
    PENDING("รอดำเนินการ"),
    BLOCKED("ติดเงื่อนไข / บล็อกการสร้างไฟล์")
}

data class FilingChecklistItem(
    val id: String,
    val titleTh: String,
    val status: ChecklistStatus,
    val detailTh: String,
    val actionTarget: String
)

data class MissingDocumentAlert(
    val titleTh: String,
    val detailTh: String,
    val suggestedCategory: TaxDocumentCategory
)

data class AnnualTaxWorkspaceSummary(
    val taxYearBe: Int,
    val report: TaxCalculationReport,
    val eligibility: FormEligibilityAssessment,
    val checklist: List<FilingChecklistItem>,
    val missingDocuments: List<MissingDocumentAlert>,
    val unclassifiedCount: Int,
    val unconfirmedDeductionCount: Int,
    val pendingVaultDocCount: Int,
    val readyToGenerateOfficialXml: Boolean,
    val readyForManualSummaryPreparation: Boolean
)

object ThaiTaxFilingEngine {

    /**
     * Official Thai 13-digit National ID / Taxpayer Identification Number (TIN) modulus-11 checksum.
     */
    fun validateThaiTin13(rawTin: String): Boolean {
        val digits = rawTin.filter { it.isDigit() }
        if (digits.length != 13 || digits != rawTin.trim()) return false
        if (digits.all { it == digits[0] }) return false

        var sum = 0
        for (i in 0 until 12) {
            val d = digits[i] - '0'
            sum += d * (13 - i)
        }
        val expectedCheck = (11 - (sum % 11)) % 10
        val actualCheck = digits[12] - '0'
        return expectedCheck == actualCheck
    }

    /**
     * Evaluates Thai personal income tax form eligibility (ภ.ง.ด.91 / ภ.ง.ด.90 / ภ.ง.ด.94)
     * strictly based on Revenue Department income sections, filing status, and statutory thresholds
     * — never by income total alone.
     */
    fun determineFormEligibility(
        taxYearBe: Int,
        profile: TaxProfileEntity,
        transactions: List<TransactionEntity>,
        withholdingRecords: List<WithholdingRecordEntity>,
        report: TaxCalculationReport
    ): FormEligibilityAssessment {
        val taxYearCe = taxYearBe - 543
        val yearTxs = transactions.filter {
            ThaiDateFormatter.toBangkokDate(it.timestampEpochMillis).year == taxYearCe
        }
        val unclassifiedInflows = yearTxs.count {
            it.reviewStatus != ReviewStatus.VERIFIED &&
                (it.type == TransactionType.OTHER || (it.type == TransactionType.INCOME && !it.taxSection.isTaxableIncome))
        }

        val sec401 = report.incomeBySectionSatang[TaxSection.SEC_40_1] ?: 0L
        val has402To408 = listOf(
            TaxSection.SEC_40_2,
            TaxSection.SEC_40_3,
            TaxSection.SEC_40_4,
            TaxSection.SEC_40_5,
            TaxSection.SEC_40_6,
            TaxSection.SEC_40_7,
            TaxSection.SEC_40_8
        ).any { (report.incomeBySectionSatang[it] ?: 0L) > 0L }

        val pnd94HalfYearIncome = yearTxs.filter { tx ->
            val d = ThaiDateFormatter.toBangkokDate(tx.timestampEpochMillis)
            d.monthValue in 1..6 &&
                tx.type == TransactionType.INCOME &&
                tx.taxSection in setOf(
                    TaxSection.SEC_40_5,
                    TaxSection.SEC_40_6,
                    TaxSection.SEC_40_7,
                    TaxSection.SEC_40_8
                )
        }.sumOf { it.amountSatang }

        val pnd94Threshold = if (profile.filingStatus == FilingStatus.SINGLE) 6_000_000L else 12_000_000L
        val requiresPnd94 = pnd94HalfYearIncome > pnd94Threshold

        val reasons = mutableListOf<String>()
        val blockers = mutableListOf<String>()

        if (unclassifiedInflows > 0) {
            blockers.add("มีรายการเงินเข้า/รอตรวจสอบ $unclassifiedInflows รายการที่ยังไม่ยืนยันมาตราเงินได้")
        }

        val totalAssessable = report.totalAssessableIncomeSatang
        val hasWithholding = withholdingRecords.isNotEmpty()

        val recommendedForm: TaxFormType?
        val certainty: FormEligibilityCertainty

        when {
            totalAssessable == 0L && hasWithholding -> {
                recommendedForm = null
                certainty = FormEligibilityCertainty.UNCERTAIN
                reasons.add("พบหนังสือรับรองหัก ณ ที่จ่าย แต่ยังไม่มีรายการเงินได้พึงประเมินที่ยืนยันมาตรา 40(1)–40(8)")
                blockers.add("กรุณาจัดประเภทรายรับก่อนระบุแบบแสดงรายการ")
            }
            totalAssessable == 0L -> {
                recommendedForm = null
                certainty = FormEligibilityCertainty.UNCERTAIN
                reasons.add("ยังไม่มีเงินได้พึงประเมินที่ยืนยันประเภทในปีภาษี $taxYearBe")
                blockers.add("ยังไม่มีข้อมูลเงินได้เพียงพอสำหรับระบุแบบ ภ.ง.ด.90 หรือ ภ.ง.ด.91")
            }
            has402To408 -> {
                recommendedForm = TaxFormType.PND90
                val threshold = if (profile.filingStatus == FilingStatus.SINGLE) 6_000_000L else 12_000_000L
                reasons.add("มีเงินได้พึงประเมินมาตรา 40(2)–40(8) จึงต้องใช้แบบ ภ.ง.ด.90 (ไม่สามารถใช้ ภ.ง.ด.91 ได้)")
                if (totalAssessable <= threshold && !hasWithholding) {
                    certainty = FormEligibilityCertainty.BELOW_STATUTORY_THRESHOLD
                    reasons.add("เงินได้พึงประเมินไม่เกินเกณฑ์บังคับยื่น (${threshold / 100} บาท)")
                } else {
                    certainty = if (unclassifiedInflows > 0) FormEligibilityCertainty.UNCERTAIN else FormEligibilityCertainty.ELIGIBLE
                    if (totalAssessable <= threshold && hasWithholding) {
                        reasons.add("แม้รายได้ต่ำกว่าเกณฑ์บังคับยื่น แต่มีภาษีหัก ณ ที่จ่าย จึงแนะนำให้ยื่นเพื่อขอคืนภาษี")
                    }
                }
            }
            sec401 > 0L -> {
                recommendedForm = TaxFormType.PND91
                val threshold = if (profile.filingStatus == FilingStatus.SINGLE) 12_000_000L else 22_000_000L
                reasons.add("มีเงินได้เฉพาะมาตรา 40(1) เงินเดือน/ค่าจ้าง จึงเข้าเกณฑ์แบบ ภ.ง.ด.91")
                if (sec401 <= threshold && !hasWithholding) {
                    certainty = FormEligibilityCertainty.BELOW_STATUTORY_THRESHOLD
                    reasons.add("เงินได้มาตรา 40(1) ไม่เกินเกณฑ์บังคับยื่น (${threshold / 100} บาท)")
                } else {
                    certainty = if (unclassifiedInflows > 0) FormEligibilityCertainty.UNCERTAIN else FormEligibilityCertainty.ELIGIBLE
                    if (sec401 <= threshold && hasWithholding) {
                        reasons.add("แม้เงินเดือนไม่ถึงเกณฑ์บังคับยื่น แต่มีภาษีหัก ณ ที่จ่าย จึงสามารถยื่นขอคืนภาษีได้")
                    }
                }
            }
            else -> {
                recommendedForm = null
                certainty = FormEligibilityCertainty.UNCERTAIN
            }
        }

        if (requiresPnd94) {
            reasons.add(
                "ตรวจพบเงินได้ 40(5)–40(8) ช่วงครึ่งปีแรก (ม.ค.–มิ.ย.) เกินเกณฑ์ ${pnd94Threshold / 100} บาท: เข้าเกณฑ์ต้องยื่น ภ.ง.ด.94 ครึ่งปีด้วย (ระบบรองรับการคำนวณเบื้องต้น แต่ยังระงับการสร้าง XML ภ.ง.ด.94 จนกว่าจะได้สเปกทางการ)"
            )
        }

        return FormEligibilityAssessment(
            recommendedForm = recommendedForm,
            certainty = certainty,
            reasonsTh = reasons,
            blockersTh = blockers,
            isPnd94HalfYearRequired = requiresPnd94,
            pnd94HalfYear405To408Satang = pnd94HalfYearIncome,
            pnd94SpecVerified = false
        )
    }

    fun inspectMissingDocuments(
        report: TaxCalculationReport,
        confirmedDeductions: List<TaxDeductionEntity>,
        withholdingRecords: List<WithholdingRecordEntity>,
        documents: List<TaxDocumentEntity>
    ): List<MissingDocumentAlert> {
        val alerts = mutableListOf<MissingDocumentAlert>()
        val hasCategory: (TaxDocumentCategory) -> Boolean = { cat ->
            documents.any { it.category == cat && it.verificationStatus != DocumentVerificationStatus.REJECTED }
        }

        val sec401 = report.incomeBySectionSatang[TaxSection.SEC_40_1] ?: 0L
        if (sec401 > 0L && !hasCategory(TaxDocumentCategory.WITHHOLDING_50_TAVI) && !hasCategory(TaxDocumentCategory.SALARY_CERT)) {
            alerts.add(
                MissingDocumentAlert(
                    titleTh = "ไม่พบหนังสือรับรองเงินเดือน / 50 ทวิ",
                    detailTh = "มีเงินได้มาตรา 40(1) ควรแนบ 50 ทวิ หรือหนังสือรับรองเงินเดือนในคลังเอกสาร",
                    suggestedCategory = TaxDocumentCategory.WITHHOLDING_50_TAVI
                )
            )
        }
        if (withholdingRecords.isNotEmpty() && !hasCategory(TaxDocumentCategory.WITHHOLDING_50_TAVI)) {
            alerts.add(
                MissingDocumentAlert(
                    titleTh = "ขาดเอกสาร 50 ทวิ สำหรับภาษีหัก ณ ที่จ่าย",
                    detailTh = "บันทึกภาษีหัก ณ ที่จ่ายไว้ ${withholdingRecords.size} รายการ แต่ยังไม่มีไฟล์แนบ 50 ทวิ",
                    suggestedCategory = TaxDocumentCategory.WITHHOLDING_50_TAVI
                )
            )
        }

        val mappedCategories = mapOf(
            DeductionType.SOCIAL_SECURITY to TaxDocumentCategory.SOCIAL_SECURITY,
            DeductionType.LIFE_INSURANCE to TaxDocumentCategory.LIFE_INSURANCE,
            DeductionType.HEALTH_INSURANCE to TaxDocumentCategory.HEALTH_INSURANCE,
            DeductionType.PROVIDENT_FUND to TaxDocumentCategory.RETIREMENT_FUND,
            DeductionType.RMF to TaxDocumentCategory.RETIREMENT_FUND,
            DeductionType.SSF to TaxDocumentCategory.INVESTMENT,
            DeductionType.THAI_ESG to TaxDocumentCategory.INVESTMENT,
            DeductionType.HOME_LOAN_INTEREST to TaxDocumentCategory.MORTGAGE_INTEREST,
            DeductionType.DONATION_GENERAL to TaxDocumentCategory.DONATION,
            DeductionType.DONATION_EDUCATION_2X to TaxDocumentCategory.DONATION
        )

        confirmedDeductions.filter { it.isUserConfirmed }.forEach { ded ->
            val expectedCat = mappedCategories[ded.deductionType]
            if (expectedCat != null && !hasCategory(expectedCat)) {
                alerts.add(
                    MissingDocumentAlert(
                        titleTh = "ขาดเอกสารยืนยัน: ${ded.label}",
                        detailTh = "เปิดใช้สิทธิลดหย่อนแล้ว ควรอัปโหลดหลักฐานเพื่อเตรียมตรวจทาน",
                        suggestedCategory = expectedCat
                    )
                )
            }
        }
        return alerts.distinctBy { it.titleTh }
    }

    fun buildWorkspaceSummary(
        taxYearBe: Int,
        rule: TaxRuleEntity,
        profile: TaxProfileEntity,
        identity: TaxpayerIdentityEntity?,
        transactions: List<TransactionEntity>,
        deductions: List<TaxDeductionEntity>,
        withholdingRecords: List<WithholdingRecordEntity>,
        documents: List<TaxDocumentEntity>,
        report: TaxCalculationReport,
        officialXmlSpecVerified: Boolean
    ): AnnualTaxWorkspaceSummary {
        val taxYearCe = taxYearBe - 543
        val yearTxs = transactions.filter {
            ThaiDateFormatter.toBangkokDate(it.timestampEpochMillis).year == taxYearCe
        }
        val unclassifiedCount = yearTxs.count {
            it.reviewStatus != ReviewStatus.VERIFIED || it.type == TransactionType.OTHER ||
                (it.type == TransactionType.INCOME && it.taxSection == TaxSection.NONE)
        }
        val unconfirmedDeductions = deductions.count { !it.isUserConfirmed }
        val pendingVaultDocs = documents.count { it.verificationStatus == DocumentVerificationStatus.PENDING_REVIEW }

        val eligibility = determineFormEligibility(taxYearBe, profile, transactions, withholdingRecords, report)
        val missingDocs = inspectMissingDocuments(report, deductions, withholdingRecords, documents)

        val checklist = mutableListOf<FilingChecklistItem>()

        // 1. Identity & TIN13
        val tinValid = identity != null && validateThaiTin13(identity.tin13)
        val namePresent = identity != null && identity.firstNameTh.isNotBlank() && identity.lastNameTh.isNotBlank()
        checklist.add(
            FilingChecklistItem(
                id = "identity_tin",
                titleTh = "ข้อมูลผู้เสียภาษีและเลขประจำตัว 13 หลัก",
                status = when {
                    tinValid && namePresent -> ChecklistStatus.PASS
                    identity == null || identity.tin13.isBlank() -> ChecklistStatus.PENDING
                    else -> ChecklistStatus.BLOCKED
                },
                detailTh = when {
                    tinValid && namePresent -> "ยืนยันเลข 13 หลัก (${identity.tin13}) และชื่อ-สกุลครบถ้วน"
                    identity == null || identity.tin13.isBlank() -> "ยังไม่ได้กรอกเลขผู้เสียภาษี 13 หลักและชื่อ-สกุล"
                    !tinValid -> "เลขประจำตัวผู้เสียภาษี 13 หลักไม่ผ่านการตรวจสอบ Checksum กรมสรรพากร"
                    else -> "กรุณากรอกชื่อและนามสกุลให้ครบถ้วน"
                },
                actionTarget = "identity"
            )
        )

        // 2. Unclassified transactions
        checklist.add(
            FilingChecklistItem(
                id = "reconciliation",
                titleTh = "จำแนกรายรับ-รายจ่ายและกระทบยอดประจำปี",
                status = if (unclassifiedCount == 0 && yearTxs.isNotEmpty()) ChecklistStatus.PASS else if (unclassifiedCount > 0) ChecklistStatus.BLOCKED else ChecklistStatus.PENDING,
                detailTh = when {
                    yearTxs.isEmpty() -> "ยังไม่มีรายการธุรกรรมในปีภาษี $taxYearBe"
                    unclassifiedCount > 0 -> "ยังมี $unclassifiedCount รายการที่รอตรวจสอบ/ยังไม่ระบุมาตราภาษี"
                    else -> "ตรวจสอบและจัดประเภทครบทั้ง ${yearTxs.size} รายการแล้ว"
                },
                actionTarget = "reconciliation"
            )
        )

        // 3. Form Eligibility
        checklist.add(
            FilingChecklistItem(
                id = "form_eligibility",
                titleTh = "การเลือกแบบแสดงรายการ (${eligibility.recommendedForm?.code ?: "ยังไม่ระบุ"})",
                status = when {
                    eligibility.recommendedForm == null || eligibility.certainty == FormEligibilityCertainty.UNCERTAIN -> ChecklistStatus.BLOCKED
                    else -> ChecklistStatus.PASS
                },
                detailTh = eligibility.reasonsTh.firstOrNull() ?: "รอตรวจสอบสิทธิการยื่นแบบ",
                actionTarget = "calculator"
            )
        )

        // 4. Deductions & Vault confirmation
        checklist.add(
            FilingChecklistItem(
                id = "deductions_vault",
                titleTh = "การยืนยันค่าลดหย่อนและคลังเอกสารภาษี",
                status = when {
                    unconfirmedDeductions > 0 || pendingVaultDocs > 0 -> ChecklistStatus.PENDING
                    missingDocs.isNotEmpty() -> ChecklistStatus.WARN
                    else -> ChecklistStatus.PASS
                },
                detailTh = when {
                    unconfirmedDeductions > 0 || pendingVaultDocs > 0 ->
                        "มีค่าลดหย่อนรอยืนยัน $unconfirmedDeductions รายการ และเอกสารในคลังรอยืนยัน $pendingVaultDocs ฉบับ"
                    missingDocs.isNotEmpty() ->
                        "แนะนำแนบเอกสารเพิ่มเติม ${missingDocs.size} รายการเพื่อความสมบูรณ์"
                    else -> "รายการลดหย่อนและเอกสารยืนยันครบถ้วน"
                },
                actionTarget = "vault"
            )
        )

        // 5. Tax ruleset metadata
        checklist.add(
            FilingChecklistItem(
                id = "ruleset",
                titleTh = "ชุดกฎภาษีประจำปี ${rule.taxYearBe}",
                status = if (rule.sourceUrl.startsWith("https://")) ChecklistStatus.PASS else ChecklistStatus.WARN,
                detailTh = "ใช้กฎเวอร์ชัน ${rule.ruleVersion} (${rule.sourceUrl})",
                actionTarget = "calculator"
            )
        )

        // 6. Official RD XML Public Schema verification
        checklist.add(
            FilingChecklistItem(
                id = "rd_xml_spec",
                titleTh = "สเปกโครงสร้าง RD XML Public ทางการ",
                status = if (officialXmlSpecVerified) ChecklistStatus.PASS else ChecklistStatus.BLOCKED,
                detailTh = if (officialXmlSpecVerified) {
                    "โหลดและตรวจสอบ XSD/Spec ของแบบที่เลือกแล้ว"
                } else {
                    "ระบบระงับการส่งออก XML ทางการไว้จนกว่าจะนำเข้า/ยืนยันสเปก XSD จาก rd-cms/tax?_target=system (สามารถส่งออก PDF สรุปเพื่อกรอก e-Filing ได้)"
                },
                actionTarget = "xml_handoff"
            )
        )

        val readyForManualSummary = tinValid && namePresent && unclassifiedCount == 0 &&
            eligibility.recommendedForm != null && eligibility.certainty != FormEligibilityCertainty.UNCERTAIN

        val readyForOfficialXml = readyForManualSummary && officialXmlSpecVerified

        return AnnualTaxWorkspaceSummary(
            taxYearBe = taxYearBe,
            report = report,
            eligibility = eligibility,
            checklist = checklist,
            missingDocuments = missingDocs,
            unclassifiedCount = unclassifiedCount,
            unconfirmedDeductionCount = unconfirmedDeductions,
            pendingVaultDocCount = pendingVaultDocs,
            readyToGenerateOfficialXml = readyForOfficialXml,
            readyForManualSummaryPreparation = readyForManualSummary
        )
    }
}
