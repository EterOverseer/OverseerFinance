package com.example.tax.api

import com.example.data.local.OpenApiSettingsEntity
import com.example.data.local.RdOpenApiProviderModel
import com.example.data.local.TaxFilingState
import com.example.domain.ImageHashing
import com.example.tax.xml.RdCanonicalTaxReturn
import com.example.tax.xml.RdXmlPublicSerializer
import com.example.tax.xml.RdXmlSpecDescriptor
import com.example.tax.xml.RdXmlSpecificationRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RdAccessToken(
    val tokenType: String = "Bearer",
    val accessToken: String,
    val expiresAtEpochMillis: Long
) {
    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        accessToken.isBlank() || expiresAtEpochMillis <= nowMillis

    override fun toString(): String = "RdAccessToken(tokenType=$tokenType, redacted=***)"
}

data class TaxSubmissionRequest(
    val canonicalReturn: RdCanonicalTaxReturn,
    val idempotencyKey: String,
    val xmlSpec: RdXmlSpecDescriptor = RdXmlSpecificationRegistry.getSpecFor(
        canonicalReturn.formType,
        canonicalReturn.taxYearBe
    )
)

enum class SubmissionErrorCode {
    PROVIDER_DISABLED,
    USER_CONSENT_REQUIRED,
    SPEC_NOT_VERIFIED,
    INSECURE_TRANSPORT,
    MISSING_OR_EXPIRED_TOKEN,
    DUPLICATE_SUBMISSION,
    VALIDATION_REJECTED,
    UNAUTHORIZED,
    FORBIDDEN,
    RATE_LIMITED,
    GATEWAY_ERROR,
    NETWORK_UNAVAILABLE
}

data class TaxSubmissionResponse(
    val accepted: Boolean,
    val resultingState: TaxFilingState,
    val submissionRef: String = "",
    val confirmationRef: String = "",
    val xmlContent: String = "",
    val xmlSha256: String = "",
    val errorCode: SubmissionErrorCode? = null,
    val messageTh: String
)

/**
 * Pure network transport contract implemented only by the optional isolated `:rd-connector` module
 * (never linked into the strictly offline `:app` build).
 */
interface RdApiTransport {
    suspend fun postTaxReturn(
        baseUrl: String,
        endpointPath: String,
        bearerToken: String,
        idempotencyKey: String,
        providerModel: RdOpenApiProviderModel,
        jsonBody: String
    ): RdTransportHttpResult
}

data class RdTransportHttpResult(
    val statusCode: Int,
    val submissionRef: String = "",
    val confirmationRef: String = "",
    val errorMessage: String = ""
)

interface TaxSubmissionProvider {
    val providerId: String
    val displayNameTh: String
    val isNetworkCapable: Boolean
    val officialDocumentationUrl: String

    suspend fun submit(request: TaxSubmissionRequest): TaxSubmissionResponse
}

/**
 * Active offline submission provider: validates canonical data, requires verified XSD spec for XML
 * generation, and guides manual submission to https://efiling.rd.go.th/rd-cms/ without any network calls.
 */
class LocalXmlExportProvider : TaxSubmissionProvider {
    override val providerId: String = "local_rd_xml_public"
    override val displayNameTh: String = "ส่งออก RD XML Public / PDF สรุป (ออฟไลน์)"
    override val isNetworkCapable: Boolean = false
    override val officialDocumentationUrl: String = RdXmlSpecificationRegistry.RD_XML_PUBLIC_SPEC_URL

    override suspend fun submit(request: TaxSubmissionRequest): TaxSubmissionResponse {
        val gen = RdXmlPublicSerializer.generate(request.canonicalReturn, request.xmlSpec)
        if (!gen.success) {
            val code = if (gen.isBlockedByMissingOfficialSpec) {
                SubmissionErrorCode.SPEC_NOT_VERIFIED
            } else {
                SubmissionErrorCode.VALIDATION_REJECTED
            }
            val msg = gen.validationErrors.joinToString("\n") { "• ${it.messageTh}" }
            return TaxSubmissionResponse(
                accepted = false,
                resultingState = if (gen.isBlockedByMissingOfficialSpec) TaxFilingState.READY else TaxFilingState.NEEDS_REVIEW,
                errorCode = code,
                messageTh = msg
            )
        }

        return TaxSubmissionResponse(
            accepted = true,
            resultingState = TaxFilingState.XML_GENERATED,
            submissionRef = "LOCAL-${request.canonicalReturn.formType.name}-${request.canonicalReturn.taxYearBe}-v${request.canonicalReturn.filingVersion}",
            xmlContent = gen.xmlContent,
            xmlSha256 = gen.xmlSha256,
            messageTh = "สร้างไฟล์ RD XML ตรวจสอบผ่านสมบูรณ์ พร้อมนำเข้าสู่ e-Filing กรมสรรพากร"
        )
    }
}

/**
 * Future RD Open API provider abstraction (disabled by default in offline OverseerFinance).
 * Enforces explicit user consent, HTTPS-only URLs, token expiration checks, idempotency keys,
 * and strict zero-credential logging.
 */
class FutureRdOpenApiProvider(
    private val settingsProvider: () -> OpenApiSettingsEntity,
    private val tokenProvider: () -> RdAccessToken?,
    private val transport: RdApiTransport? = null,
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() }
) : TaxSubmissionProvider {

    companion object {
        const val RD_OPEN_API_DOC_URL = "https://efiling.rd.go.th/rd-cms/openapi"
        const val RD_API_DOC_URL = "https://efiling.rd.go.th/rd-cms/api"
    }

    override val providerId: String = "future_rd_open_api"
    override val displayNameTh: String = "RD Open API Connector (ปิดใช้งานโดยค่าเริ่มต้น)"
    override val isNetworkCapable: Boolean = true
    override val officialDocumentationUrl: String = RD_OPEN_API_DOC_URL

    private val processedIdempotencyKeys = mutableSetOf<String>()
    private val json = Json { encodeDefaults = true }

    override suspend fun submit(request: TaxSubmissionRequest): TaxSubmissionResponse {
        val settings = settingsProvider()
        if (!settings.isEnabled || settings.providerModel == RdOpenApiProviderModel.NONE) {
            return TaxSubmissionResponse(
                accepted = false,
                resultingState = TaxFilingState.READY,
                errorCode = SubmissionErrorCode.PROVIDER_DISABLED,
                messageTh = "RD Open API ถูกปิดใช้งานอยู่ (โหมดออฟไลน์เท่านั้น)"
            )
        }

        if (!settings.userConsented) {
            return TaxSubmissionResponse(
                accepted = false,
                resultingState = TaxFilingState.READY,
                errorCode = SubmissionErrorCode.USER_CONSENT_REQUIRED,
                messageTh = "กรุณายืนยันความยินยอมจากผู้ใช้ก่อนส่งข้อมูลผ่าน RD Open API"
            )
        }

        if (!settings.baseUrl.trim().startsWith("https://", ignoreCase = true)) {
            return TaxSubmissionResponse(
                accepted = false,
                resultingState = TaxFilingState.READY,
                errorCode = SubmissionErrorCode.INSECURE_TRANSPORT,
                messageTh = "ไม่อนุญาตให้เชื่อมต่อ Endpoint ที่ไม่ใช่ HTTPS เพื่อความปลอดภัยข้อมูลภาษี"
            )
        }

        val token = tokenProvider()
        val now = nowMillisProvider()
        if (token == null || token.isExpired(now)) {
            return TaxSubmissionResponse(
                accepted = false,
                resultingState = TaxFilingState.READY,
                errorCode = SubmissionErrorCode.MISSING_OR_EXPIRED_TOKEN,
                messageTh = "Access Token ไม่มีอยู่หรือหมดอายุแล้ว กรุณายืนยันตัวตนกับ RD Open API ใหม่"
            )
        }

        val idempotency = request.idempotencyKey.trim()
        if (idempotency.isBlank() || processedIdempotencyKeys.contains(idempotency)) {
            return TaxSubmissionResponse(
                accepted = false,
                resultingState = TaxFilingState.READY,
                errorCode = SubmissionErrorCode.DUPLICATE_SUBMISSION,
                messageTh = "ตรวจพบคำขอส่งซ้ำ (Idempotency Key: $idempotency) ระบบระงับการส่งซ้ำอัตโนมัติ"
            )
        }

        val activeTransport = transport
            ?: return TaxSubmissionResponse(
                accepted = false,
                resultingState = TaxFilingState.READY,
                errorCode = SubmissionErrorCode.NETWORK_UNAVAILABLE,
                messageTh = "บิลด์ออฟไลน์นี้ไม่ได้ติดตั้งโมดูลเชื่อมต่อเครือข่าย (:rd-connector) — ใช้ Local XML Export แทน"
            )

        val endpointPath = when (settings.providerModel) {
            RdOpenApiProviderModel.TAX_PREPARATION_PROVIDER -> "/tax-preparation/drafts"
            RdOpenApiProviderModel.TAX_FILING_PROVIDER -> "/tax-filing/submissions"
            RdOpenApiProviderModel.NONE -> ""
        }

        val canonical = request.canonicalReturn
        val summaryPayload = json.encodeToString(
            mapOf(
                "taxYearBe" to canonical.taxYearBe.toString(),
                "formType" to canonical.formType.name,
                "filingVersion" to canonical.filingVersion.toString(),
                "rulesetVersion" to canonical.rulesetVersion,
                "totalAssessableSatang" to canonical.totalAssessableIncomeSatang.toString(),
                "netTaxableSatang" to canonical.netTaxableIncomeSatang.toString(),
                "calculatedTaxSatang" to canonical.calculatedTaxSatang.toString(),
                "withholdingSatang" to canonical.withholdingTaxSatang.toString()
            )
        )

        val result = try {
            activeTransport.postTaxReturn(
                baseUrl = settings.baseUrl.trim(),
                endpointPath = endpointPath,
                bearerToken = token.accessToken,
                idempotencyKey = idempotency,
                providerModel = settings.providerModel,
                jsonBody = summaryPayload
            )
        } catch (t: Throwable) {
            return TaxSubmissionResponse(
                accepted = false,
                resultingState = TaxFilingState.READY,
                errorCode = SubmissionErrorCode.NETWORK_UNAVAILABLE,
                messageTh = "ไม่สามารถติดต่อเกตเวย์ RD Open API ได้: ${t.javaClass.simpleName}"
            )
        }

        return when (result.statusCode) {
            in 200..299 -> {
                processedIdempotencyKeys.add(idempotency)
                val nextState = if (settings.providerModel == RdOpenApiProviderModel.TAX_PREPARATION_PROVIDER) {
                    TaxFilingState.READY
                } else {
                    TaxFilingState.SUBMISSION_CONFIRMED
                }
                TaxSubmissionResponse(
                    accepted = true,
                    resultingState = nextState,
                    submissionRef = result.submissionRef,
                    confirmationRef = result.confirmationRef,
                    errorCode = null,
                    messageTh = if (nextState == TaxFilingState.SUBMISSION_CONFIRMED) {
                        "ส่งแบบและได้รับเลขยืนยันจาก RD Open API แล้ว (${result.confirmationRef})"
                    } else {
                        "จัดเตรียมข้อมูลร่างไปยังระบบ e-Filing แล้ว โปรดยืนยันการยื่นบน RD Smart Tax / e-Filing"
                    }
                )
            }
            401 -> TaxSubmissionResponse(
                accepted = false,
                resultingState = TaxFilingState.READY,
                errorCode = SubmissionErrorCode.UNAUTHORIZED,
                messageTh = "การยืนยันตัวตนกับ RD Open API ล้มเหลว (401 Unauthorized)"
            )
            403 -> TaxSubmissionResponse(
                accepted = false,
                resultingState = TaxFilingState.REJECTED,
                errorCode = SubmissionErrorCode.FORBIDDEN,
                messageTh = "ไม่มีสิทธิ์เรียกใช้งานบริการ RD Open API นี้ (403 Forbidden)"
            )
            409 -> {
                processedIdempotencyKeys.add(idempotency)
                TaxSubmissionResponse(
                    accepted = false,
                    resultingState = TaxFilingState.ADDITIONAL_FILING_REQUIRED,
                    errorCode = SubmissionErrorCode.DUPLICATE_SUBMISSION,
                    messageTh = "รายการนี้เคยถูกส่งแล้วในระบบกรมสรรพากร (409 Conflict) — หากต้องการแก้ไขโปรดยื่นเพิ่มเติม"
                )
            }
            422, 400 -> TaxSubmissionResponse(
                accepted = false,
                resultingState = TaxFilingState.REJECTED,
                errorCode = SubmissionErrorCode.VALIDATION_REJECTED,
                messageTh = "ข้อมูลไม่ผ่านการตรวจสอบของ RD Open API: ${result.errorMessage.ifBlank { "HTTP ${result.statusCode}" }}"
            )
            429 -> TaxSubmissionResponse(
                accepted = false,
                resultingState = TaxFilingState.READY,
                errorCode = SubmissionErrorCode.RATE_LIMITED,
                messageTh = "เรียกใช้งาน RD Open API ถี่เกินกำหนด (429 Too Many Requests) โปรดรอสักครู่"
            )
            else -> TaxSubmissionResponse(
                accepted = false,
                resultingState = TaxFilingState.READY,
                errorCode = SubmissionErrorCode.GATEWAY_ERROR,
                messageTh = "เซิร์ฟเวอร์ RD Open API ตอบกลับสถานะ HTTP ${result.statusCode}"
            )
        }
    }

    fun buildIdempotencyKey(canonical: RdCanonicalTaxReturn): String {
        val raw = "${canonical.taxYearBe}:${canonical.formType.name}:${canonical.filingVersion}:${canonical.totalAssessableIncomeSatang}:${canonical.netTaxableIncomeSatang}"
        return ImageHashing.sha256(raw.toByteArray(Charsets.UTF_8)).take(24)
    }
}
