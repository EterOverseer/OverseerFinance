package com.example.tax.xml

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.example.data.local.DeductionType
import com.example.data.local.FilingStatus
import com.example.data.local.TaxDeductionEntity
import com.example.data.local.TaxFormType
import com.example.data.local.TaxProfileEntity
import com.example.data.local.TaxSection
import com.example.data.local.TaxpayerIdentityEntity
import com.example.domain.ImageHashing
import com.example.domain.MoneyFormatter
import com.example.domain.TaxCalculationReport
import com.example.tax.engine.ThaiTaxFilingEngine
import java.io.OutputStream
import java.math.BigDecimal

data class RdCanonicalTaxReturn(
    val taxYearBe: Int,
    val formType: TaxFormType,
    val filingVersion: Int,
    val filingStatus: FilingStatus,
    val identity: TaxpayerIdentityEntity,
    val incomeBySectionSatang: Map<TaxSection, Long>,
    val totalAssessableIncomeSatang: Long,
    val expenseDeductionSatang: Long,
    val personalAndFamilyAllowanceSatang: Long,
    val itemizedDeductionsSatang: Map<DeductionType, Long>,
    val userConfirmedDeductionsSatang: Long,
    val netTaxableIncomeSatang: Long,
    val calculatedTaxSatang: Long,
    val withholdingTaxSatang: Long,
    val payableTaxSatang: Long,
    val refundTaxSatang: Long,
    val rulesetVersion: String
)

/**
 * Specification descriptor for official Revenue Department XML Public exports.
 * Real XSD element mappings must come from an officially verified RD XSD package
 * (https://efiling.rd.go.th/rd-cms/tax?_target=system). Unverified specs cannot export official XML.
 */
data class RdXmlSpecDescriptor(
    val formType: TaxFormType,
    val taxYearBe: Int,
    val specVersion: String,
    val officialReferenceUrl: String,
    val isOfficiallyVerified: Boolean,
    val rootElementName: String = "",
    val namespaceUri: String? = null,
    val elementTagMap: Map<String, String> = emptyMap(),
    val statusDetailTh: String = ""
)

data class XmlValidationError(
    val fieldKey: String,
    val code: String,
    val messageTh: String
)

data class RdXmlGenerationResult(
    val success: Boolean,
    val xmlContent: String = "",
    val xmlSha256: String = "",
    val validationErrors: List<XmlValidationError> = emptyList(),
    val isBlockedByMissingOfficialSpec: Boolean = false
)

object RdXmlSpecificationRegistry {
    const val RD_EFILING_PORTAL_URL = "https://efiling.rd.go.th/rd-cms/"
    const val RD_XML_PUBLIC_SPEC_URL = "https://efiling.rd.go.th/rd-cms/tax?_target=system"

    /**
     * Built-in registry deliberately marks official RD XML XSD tags as unverified until an
     * official RD XML Public specification file from rd-cms/tax?_target=system is loaded.
     * OverseerFinance strictly prohibits inventing unverified XML tags.
     */
    fun getSpecFor(formType: TaxFormType, taxYearBe: Int): RdXmlSpecDescriptor {
        return RdXmlSpecDescriptor(
            formType = formType,
            taxYearBe = taxYearBe,
            specVersion = "UNLOADED",
            officialReferenceUrl = RD_XML_PUBLIC_SPEC_URL,
            isOfficiallyVerified = false,
            statusDetailTh = "ยังไม่ได้โหลดสเปก XSD/XML Public ทางการของ ${formType.code} ปี $taxYearBe " +
                "(ดาวน์โหลดสเปกทางการได้ที่ $RD_XML_PUBLIC_SPEC_URL)"
        )
    }
}

object RdXmlValidator {

    fun validateCanonicalReturn(
        canonical: RdCanonicalTaxReturn,
        spec: RdXmlSpecDescriptor
    ): List<XmlValidationError> {
        val errors = mutableListOf<XmlValidationError>()

        if (!spec.isOfficiallyVerified || spec.rootElementName.isBlank() || spec.elementTagMap.isEmpty()) {
            errors.add(
                XmlValidationError(
                    fieldKey = "spec",
                    code = "OFFICIAL_SPEC_NOT_VERIFIED",
                    messageTh = "ยังไม่สามารถสร้างไฟล์ XML ทางการได้ เนื่องจากยังไม่ได้ตรวจสอบ/โหลดสเปก XSD ทางการจากกรมสรรพากร (${spec.officialReferenceUrl})"
                )
            )
        }

        if (canonical.taxYearBe !in 2560..2600) {
            errors.add(XmlValidationError("taxYearBe", "INVALID_TAX_YEAR", "ปีภาษี ${canonical.taxYearBe} ไม่อยู่ในช่วงที่รองรับ"))
        }

        if (!ThaiTaxFilingEngine.validateThaiTin13(canonical.identity.tin13)) {
            errors.add(
                XmlValidationError(
                    fieldKey = "tin13",
                    code = "INVALID_TIN_CHECKSUM",
                    messageTh = "เลขประจำตัวผู้เสียภาษี 13 หลักไม่ถูกต้องหรือไม่ผ่าน Checksum"
                )
            )
        }

        if (canonical.identity.firstNameTh.isBlank() || canonical.identity.lastNameTh.isBlank()) {
            errors.add(
                XmlValidationError(
                    fieldKey = "name",
                    code = "MISSING_TAXPAYER_NAME",
                    messageTh = "กรุณาระบุชื่อและนามสกุลผู้เสียภาษีให้ครบถ้วน"
                )
            )
        }

        if (canonical.identity.province.isBlank() || !canonical.identity.postalCode.matches(Regex("^\\d{5}$"))) {
            errors.add(
                XmlValidationError(
                    fieldKey = "address",
                    code = "INVALID_ADDRESS_OR_POSTAL",
                    messageTh = "กรุณาระบุจังหวัดและรหัสไปรษณีย์ 5 หลักให้ครบถ้วน"
                )
            )
        }

        if (canonical.filingStatus != FilingStatus.SINGLE && canonical.identity.spouseTin13.isNotBlank()) {
            if (!ThaiTaxFilingEngine.validateThaiTin13(canonical.identity.spouseTin13)) {
                errors.add(
                    XmlValidationError(
                        fieldKey = "spouseTin13",
                        code = "INVALID_SPOUSE_TIN",
                        messageTh = "เลขประจำตัวคู่สมรส 13 หลักไม่ผ่าน Checksum"
                    )
                )
            }
        }

        // Form eligibility verification: PND91 is strictly 40(1) only
        val non401 = canonical.incomeBySectionSatang
            .filterKeys { it != TaxSection.SEC_40_1 && it.isTaxableIncome }
            .values
            .sum()
        if (canonical.formType == TaxFormType.PND91 && non401 > 0L) {
            errors.add(
                XmlValidationError(
                    fieldKey = "formType",
                    code = "INELIGIBLE_FORM_PND91",
                    messageTh = "มีเงินได้อื่นนอกจากมาตรา 40(1) ไม่สามารถยื่นแบบ ภ.ง.ด.91 ได้ ต้องใช้ ภ.ง.ด.90"
                )
            )
        }

        val monetaryValues = listOf(
            canonical.totalAssessableIncomeSatang,
            canonical.expenseDeductionSatang,
            canonical.personalAndFamilyAllowanceSatang,
            canonical.userConfirmedDeductionsSatang,
            canonical.netTaxableIncomeSatang,
            canonical.calculatedTaxSatang,
            canonical.withholdingTaxSatang,
            canonical.payableTaxSatang,
            canonical.refundTaxSatang
        )
        if (monetaryValues.any { it < 0L }) {
            errors.add(
                XmlValidationError(
                    fieldKey = "amounts",
                    code = "NEGATIVE_AMOUNT_NOT_ALLOWED",
                    messageTh = "ตรวจพบยอดเงินติดลบในรายการภาษี"
                )
            )
        }

        if (canonical.payableTaxSatang > 0L && canonical.refundTaxSatang > 0L) {
            errors.add(
                XmlValidationError(
                    fieldKey = "settlement",
                    code = "CONFLICTING_PAYABLE_AND_REFUND",
                    messageTh = "ยอดภาษีชำระเพิ่มและขอคืนภาษีไม่สามารถมีค่ามากกว่าศูนย์พร้อมกันได้"
                )
            )
        }

        return errors
    }
}

object RdXmlPublicSerializer {

    fun buildCanonicalReturn(
        taxYearBe: Int,
        formType: TaxFormType,
        filingVersion: Int,
        profile: TaxProfileEntity,
        identity: TaxpayerIdentityEntity,
        report: TaxCalculationReport,
        confirmedDeductions: List<TaxDeductionEntity>
    ): RdCanonicalTaxReturn {
        val itemized = confirmedDeductions
            .filter { it.isUserConfirmed }
            .groupBy { it.deductionType }
            .mapValues { (_, items) -> items.sumOf { it.amountSatang } }

        return RdCanonicalTaxReturn(
            taxYearBe = taxYearBe,
            formType = formType,
            filingVersion = filingVersion,
            filingStatus = profile.filingStatus,
            identity = identity,
            incomeBySectionSatang = report.incomeBySectionSatang,
            totalAssessableIncomeSatang = report.totalAssessableIncomeSatang,
            expenseDeductionSatang = report.standardExpenseDeductionSatang,
            personalAndFamilyAllowanceSatang = report.personalAndFamilyAllowanceSatang,
            itemizedDeductionsSatang = itemized,
            userConfirmedDeductionsSatang = report.userConfirmedDeductionsSatang,
            netTaxableIncomeSatang = report.netTaxableIncomeSatang,
            calculatedTaxSatang = report.grossTaxCalculatedSatang,
            withholdingTaxSatang = report.totalWithholdingTaxSatang,
            payableTaxSatang = report.netTaxPayableSatang,
            refundTaxSatang = report.netTaxRefundSatang,
            rulesetVersion = report.ruleVersion
        )
    }

    /**
     * Generates strict UTF-8 XML ONLY when `spec` is verified and validation passes.
     * Every XML element tag is taken exclusively from `spec.elementTagMap` — zero fabricated tags.
     */
    fun generate(
        canonical: RdCanonicalTaxReturn,
        spec: RdXmlSpecDescriptor
    ): RdXmlGenerationResult {
        val validationErrors = RdXmlValidator.validateCanonicalReturn(canonical, spec)
        val specBlocked = validationErrors.any { it.code == "OFFICIAL_SPEC_NOT_VERIFIED" }
        if (validationErrors.isNotEmpty()) {
            return RdXmlGenerationResult(
                success = false,
                validationErrors = validationErrors,
                isBlockedByMissingOfficialSpec = specBlocked
            )
        }

        val canonicalValues: List<Pair<String, String>> = listOf(
            "taxYearBe" to canonical.taxYearBe.toString(),
            "formType" to canonical.formType.name,
            "filingVersion" to canonical.filingVersion.toString(),
            "rulesetVersion" to canonical.rulesetVersion,
            "tin13" to canonical.identity.tin13.trim(),
            "titleTh" to canonical.identity.titleTh.trim(),
            "firstNameTh" to canonical.identity.firstNameTh.trim(),
            "lastNameTh" to canonical.identity.lastNameTh.trim(),
            "filingStatus" to canonical.filingStatus.name,
            "spouseTin13" to canonical.identity.spouseTin13.trim(),
            "addressLine" to canonical.identity.addressLine.trim(),
            "subDistrict" to canonical.identity.subDistrict.trim(),
            "district" to canonical.identity.district.trim(),
            "province" to canonical.identity.province.trim(),
            "postalCode" to canonical.identity.postalCode.trim(),
            "sec401" to formatSatangPlain(canonical.incomeBySectionSatang[TaxSection.SEC_40_1] ?: 0L),
            "sec402" to formatSatangPlain(canonical.incomeBySectionSatang[TaxSection.SEC_40_2] ?: 0L),
            "sec403" to formatSatangPlain(canonical.incomeBySectionSatang[TaxSection.SEC_40_3] ?: 0L),
            "sec404" to formatSatangPlain(canonical.incomeBySectionSatang[TaxSection.SEC_40_4] ?: 0L),
            "sec405" to formatSatangPlain(canonical.incomeBySectionSatang[TaxSection.SEC_40_5] ?: 0L),
            "sec406" to formatSatangPlain(canonical.incomeBySectionSatang[TaxSection.SEC_40_6] ?: 0L),
            "sec407" to formatSatangPlain(canonical.incomeBySectionSatang[TaxSection.SEC_40_7] ?: 0L),
            "sec408" to formatSatangPlain(canonical.incomeBySectionSatang[TaxSection.SEC_40_8] ?: 0L),
            "totalAssessable" to formatSatangPlain(canonical.totalAssessableIncomeSatang),
            "expenseDeduction" to formatSatangPlain(canonical.expenseDeductionSatang),
            "personalAllowance" to formatSatangPlain(canonical.personalAndFamilyAllowanceSatang),
            "confirmedDeductions" to formatSatangPlain(canonical.userConfirmedDeductionsSatang),
            "netTaxable" to formatSatangPlain(canonical.netTaxableIncomeSatang),
            "calculatedTax" to formatSatangPlain(canonical.calculatedTaxSatang),
            "withholdingTax" to formatSatangPlain(canonical.withholdingTaxSatang),
            "payableTax" to formatSatangPlain(canonical.payableTaxSatang),
            "refundTax" to formatSatangPlain(canonical.refundTaxSatang)
        )

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        val nsAttr = spec.namespaceUri?.takeIf { it.isNotBlank() }?.let { " xmlns=\"${escapeXml(it)}\"" } ?: ""
        sb.append("<${spec.rootElementName}$nsAttr>\n")
        for ((key, value) in canonicalValues) {
            val tagName = spec.elementTagMap[key] ?: continue
            sb.append("  <$tagName>${escapeXml(value)}</$tagName>\n")
        }
        sb.append("</${spec.rootElementName}>\n")

        val xmlText = sb.toString()
        val xmlBytes = xmlText.toByteArray(Charsets.UTF_8)
        val sha256 = ImageHashing.sha256(xmlBytes)
        return RdXmlGenerationResult(
            success = true,
            xmlContent = xmlText,
            xmlSha256 = sha256,
            validationErrors = emptyList(),
            isBlockedByMissingOfficialSpec = false
        )
    }

    /**
     * Generates an offline e-Filing preparation summary PDF that clearly lays out every
     * verified line item so the user can review and enter values on https://efiling.rd.go.th/rd-cms/.
     */
    fun generatePreparationSummaryPdf(
        outputStream: OutputStream,
        canonical: RdCanonicalTaxReturn
    ) {
        val doc = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 40f

        val titlePaint = Paint().apply {
            color = Color.parseColor("#0B1320")
            textSize = 16f
            isFakeBoldText = true
        }
        val headerPaint = Paint().apply {
            color = Color.parseColor("#0F766E")
            textSize = 12f
            isFakeBoldText = true
        }
        val bodyPaint = Paint().apply {
            color = Color.parseColor("#1E293B")
            textSize = 10f
        }
        val notePaint = Paint().apply {
            color = Color.parseColor("#475569")
            textSize = 9f
        }

        val page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
        val canvas = page.canvas
        var y = 46f

        canvas.drawText(
            "OverseerFinance - เอกสารสรุปเตรียมยื่นภาษี ${canonical.formType.code} ปี ${canonical.taxYearBe} (v${canonical.filingVersion})",
            margin,
            y,
            titlePaint
        )
        y += 18f
        canvas.drawText(
            "ชุดกฎ: ${canonical.rulesetVersion} | ช่องทางยื่นทางการ: ${RdXmlSpecificationRegistry.RD_EFILING_PORTAL_URL}",
            margin,
            y,
            notePaint
        )
        y += 24f

        canvas.drawText("ข้อมูลผู้มีเงินได้", margin, y, headerPaint)
        y += 16f
        val identityLines = listOf(
            "เลขประจำตัวผู้เสียภาษี (TIN13): ${canonical.identity.tin13.ifBlank { "-" }}",
            "ชื่อ-สกุล: ${canonical.identity.titleTh} ${canonical.identity.firstNameTh} ${canonical.identity.lastNameTh}".trim(),
            "สถานภาพ: ${canonical.filingStatus.labelTh}",
            "ที่อยู่: ${canonical.identity.addressLine} ${canonical.identity.subDistrict} ${canonical.identity.district} ${canonical.identity.province} ${canonical.identity.postalCode}".trim()
        )
        identityLines.forEach { line ->
            canvas.drawText(line, margin, y, bodyPaint)
            y += 14f
        }

        y += 12f
        canvas.drawText("สรุปเงินได้และภาษีตามมาตรา 40(1)–40(8)", margin, y, headerPaint)
        y += 16f
        TaxSection.entries.filter { it.isTaxableIncome }.forEach { sec ->
            val amt = canonical.incomeBySectionSatang[sec] ?: 0L
            if (amt > 0L) {
                canvas.drawText("มาตรา ${sec.code}: ${MoneyFormatter.formatThb(amt)}", margin, y, bodyPaint)
                y += 14f
            }
        }

        y += 10f
        val summaryLines = listOf(
            "รวมเงินได้พึงประเมิน: ${MoneyFormatter.formatThb(canonical.totalAssessableIncomeSatang)}",
            "หักค่าใช้จ่ายตามกฎหมาย: -${MoneyFormatter.formatThb(canonical.expenseDeductionSatang)}",
            "หักลดหย่อนส่วนตัวและครอบครัว: -${MoneyFormatter.formatThb(canonical.personalAndFamilyAllowanceSatang)}",
            "หักลดหย่อนเพิ่มเติมที่ยืนยันแล้ว: -${MoneyFormatter.formatThb(canonical.userConfirmedDeductionsSatang)}",
            "เงินได้สุทธิ (Net Taxable Income): ${MoneyFormatter.formatThb(canonical.netTaxableIncomeSatang)}",
            "ภาษีที่คำนวณได้: ${MoneyFormatter.formatThb(canonical.calculatedTaxSatang)}",
            "ภาษีหัก ณ ที่จ่าย: ${MoneyFormatter.formatThb(canonical.withholdingTaxSatang)}",
            if (canonical.payableTaxSatang > 0L)
                "ยอดชำระภาษีเพิ่ม: ${MoneyFormatter.formatThb(canonical.payableTaxSatang)}"
            else
                "ยอดภาษีที่ขอคืนได้: ${MoneyFormatter.formatThb(canonical.refundTaxSatang)}"
        )
        summaryLines.forEach { line ->
            canvas.drawText(line, margin, y, bodyPaint)
            y += 15f
        }

        y += 16f
        canvas.drawText(
            "หมายเหตุ: เอกสารนี้จัดทำเพื่อช่วยเตรียมตัวเลขสำหรับยื่นแบบด้วยตนเองผ่านระบบ e-Filing ของกรมสรรพากรเท่านั้น",
            margin,
            y,
            notePaint
        )

        doc.finishPage(page)
        outputStream.use { doc.writeTo(it) }
        doc.close()
    }

    private fun formatSatangPlain(satang: Long): String {
        return BigDecimal.valueOf(satang, 2).toPlainString()
    }

    private fun escapeXml(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
