package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class TaxFormType(val code: String, val labelTh: String) {
    PND90("ภ.ง.ด.90", "ภ.ง.ด.90 (ผู้มีเงินได้หลายประเภท / 40(2)-40(8))"),
    PND91("ภ.ง.ด.91", "ภ.ง.ด.91 (เฉพาะเงินได้มาตรา 40(1) เงินเดือน)"),
    PND94("ภ.ง.ด.94", "ภ.ง.ด.94 (ภาษีเงินได้ครึ่งปี มาตรา 40(5)-40(8))")
}

@Serializable
enum class TaxFilingState(val labelTh: String) {
    DRAFT("แบบร่าง"),
    NEEDS_REVIEW("รอตรวจสอบ"),
    READY("พร้อมเตรียมยื่น"),
    XML_GENERATED("สร้างไฟล์ XML แล้ว"),
    MANUALLY_SUBMITTED("ยื่นผ่าน e-Filing ด้วยตนเองแล้ว"),
    SUBMISSION_CONFIRMED("ยืนยันการยื่นสำเร็จ"),
    REJECTED("ไม่ผ่าน / ถูกปฏิเสธ"),
    ADDITIONAL_FILING_REQUIRED("ต้องยื่นเพิ่มเติม")
}

@Serializable
enum class TaxDocumentCategory(val labelTh: String) {
    WITHHOLDING_50_TAVI("หนังสือรับรองหัก ณ ที่จ่าย (50 ทวิ)"),
    SALARY_CERT("หนังสือรับรองเงินเดือน"),
    SOCIAL_SECURITY("หลักฐานจ่ายเงินสมทบประกันสังคม"),
    LIFE_INSURANCE("หนังสือรับรองเบี้ยประกันชีวิต"),
    HEALTH_INSURANCE("หนังสือรับรองเบี้ยประกันสุขภาพ"),
    RETIREMENT_FUND("เอกสารกองทุนสำรองเลี้ยงชีพ / กบข. / RMF"),
    INVESTMENT("เอกสารลงทุน SSF / Thai ESG"),
    DONATION("ใบอนุโมทนาบัตร / ใบเสร็จบริจาค"),
    MORTGAGE_INTEREST("หนังสือรับรองดอกเบี้ยเงินกู้ที่อยู่อาศัย"),
    OTHER("เอกสารลดหย่อนอื่นๆ")
}

@Serializable
enum class DocumentVerificationStatus(val labelTh: String) {
    PENDING_REVIEW("รอผู้ใช้ยืนยันสิทธิ"),
    USER_CONFIRMED("ผู้ใช้ยืนยันแล้ว"),
    REJECTED("ไม่ใช้สิทธิ / ปฏิเสธ")
}

@Serializable
enum class RdOpenApiProviderModel(val labelTh: String) {
    NONE("ปิดใช้งาน (ออฟไลน์เท่านั้น)"),
    TAX_PREPARATION_PROVIDER("ผู้ให้บริการจัดเตรียมข้อมูลแบบ (Tax Preparation Service Provider)"),
    TAX_FILING_PROVIDER("ผู้ให้บริการตัวแทนยื่นแบบ (Tax Filing Service Provider)")
}

@Entity(tableName = "taxpayer_identities")
data class TaxpayerIdentityEntity(
    @PrimaryKey val taxYearBe: Int,
    val tin13: String = "",
    val titleTh: String = "",
    val firstNameTh: String = "",
    val lastNameTh: String = "",
    val birthDateIso: String = "",
    val spouseTin13: String = "",
    val spouseFirstNameTh: String = "",
    val spouseLastNameTh: String = "",
    val addressLine: String = "",
    val subDistrict: String = "",
    val district: String = "",
    val province: String = "",
    val postalCode: String = "",
    val phone: String = "",
    val email: String = "",
    val updatedAtEpochMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "statement_import_batches",
    indices = [Index(value = ["sha256"], unique = true)]
)
data class StatementImportBatchEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val bankName: String,
    val fileName: String,
    val sha256: String,
    val rowCount: Int,
    val importedCount: Int,
    val duplicateCount: Int,
    val importedAtEpochMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "reconciliation_audits",
    indices = [Index(value = ["taxYearBe"]), Index(value = ["transactionId"])]
)
data class ReconciliationAuditEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val taxYearBe: Int,
    val transactionId: String,
    val previousType: TransactionType,
    val newType: TransactionType,
    val previousTaxSection: TaxSection,
    val newTaxSection: TaxSection,
    val reason: String = "",
    val adjustedAtEpochMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "tax_documents",
    indices = [Index(value = ["taxYearBe"]), Index(value = ["sha256"])]
)
data class TaxDocumentEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val taxYearBe: Int,
    val category: TaxDocumentCategory,
    val title: String,
    val mimeType: String,
    val privateFilePath: String,
    val sha256: String,
    val fileSizeBytes: Long,
    val linkedTransactionId: String? = null,
    val linkedDeductionId: String? = null,
    val ocrExtractedText: String = "",
    val extractedAmountSatang: Long? = null,
    val notes: String = "",
    val verificationStatus: DocumentVerificationStatus = DocumentVerificationStatus.PENDING_REVIEW,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "tax_filing_history",
    indices = [Index(value = ["taxYearBe"]), Index(value = ["filingVersion"])]
)
data class TaxFilingHistoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val taxYearBe: Int,
    val formType: TaxFormType,
    val filingVersion: Int,
    val xmlFileName: String = "",
    val xmlSha256: String = "",
    val generatedAtEpochMillis: Long = System.currentTimeMillis(),
    val totalAssessableSatang: Long,
    val totalDeductionSatang: Long,
    val netTaxableSatang: Long,
    val calculatedTaxSatang: Long,
    val withholdingSatang: Long,
    val payableSatang: Long,
    val refundSatang: Long,
    val rulesetVersion: String,
    val filingState: TaxFilingState,
    val submissionRef: String = "",
    val confirmationRef: String = "",
    val associatedDocumentIdsJson: String = "[]",
    val isOutdated: Boolean = false,
    val lastDataMutationEpochMillis: Long = System.currentTimeMillis(),
    val notes: String = ""
)

@Entity(tableName = "open_api_settings")
data class OpenApiSettingsEntity(
    @PrimaryKey val id: String = "rd_open_api",
    val isEnabled: Boolean = false,
    val providerModel: RdOpenApiProviderModel = RdOpenApiProviderModel.NONE,
    val environment: String = "DISABLED",
    val clientId: String = "",
    val baseUrl: String = "",
    val userConsented: Boolean = false,
    val lastAuthEpochMillis: Long? = null,
    val updatedAtEpochMillis: Long = System.currentTimeMillis()
)
