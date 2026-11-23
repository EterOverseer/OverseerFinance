package com.example.tax.vault

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.local.DeductionType
import com.example.data.local.DocumentVerificationStatus
import com.example.data.local.OcrModelStatus
import com.example.data.local.TaxDeductionEntity
import com.example.data.local.TaxDocumentCategory
import com.example.data.local.TaxDocumentEntity
import com.example.domain.ImageHashing
import com.example.domain.PaddleOcrEngine
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Local private vault manager for Tax Documents (images and PDFs).
 * Files are stored inside Context.filesDir/tax_vault_docs/ with SHA-256 integrity tracking
 * and included in OverseerFinance .efbackup and .sqlite full backups.
 *
 * Strictly enforces mandatory user confirmation: importing a document NEVER auto-claims
 * a tax deduction until the user explicitly reviews and confirms it.
 */
class TaxDocumentVaultManager(private val context: Context) {

    fun vaultDocsDir(): File = File(context.filesDir, "tax_vault_docs").apply { mkdirs() }

    fun importFromUri(
        sourceUri: Uri,
        taxYearBe: Int,
        category: TaxDocumentCategory,
        customTitle: String? = null,
        notes: String = "",
        linkedTransactionId: String? = null,
        linkedDeductionId: String? = null
    ): Result<TaxDocumentEntity> = runCatching {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(sourceUri) ?: "application/octet-stream"
        val originalName = queryDisplayName(sourceUri) ?: "tax_doc_${System.currentTimeMillis()}"
        val input = resolver.openInputStream(sourceUri)
            ?: error("ไม่สามารถเปิดอ่านไฟล์เอกสารภาษีจาก URI ได้")
        input.use { stream ->
            importFromStream(
                inputStream = stream,
                originalFileName = originalName,
                mimeType = mimeType,
                taxYearBe = taxYearBe,
                category = category,
                customTitle = customTitle,
                notes = notes,
                linkedTransactionId = linkedTransactionId,
                linkedDeductionId = linkedDeductionId
            )
        }
    }

    fun importFromStream(
        inputStream: InputStream,
        originalFileName: String,
        mimeType: String,
        taxYearBe: Int,
        category: TaxDocumentCategory,
        customTitle: String? = null,
        notes: String = "",
        linkedTransactionId: String? = null,
        linkedDeductionId: String? = null
    ): TaxDocumentEntity {
        val docId = UUID.randomUUID().toString()
        val safeName = originalFileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val ext = safeName.substringAfterLast('.', "").lowercase()
        val finalName = if (ext.isNotBlank()) "${docId}_$safeName" else {
            val guessedExt = when {
                mimeType.contains("pdf", true) -> "pdf"
                mimeType.contains("png", true) -> "png"
                else -> "jpg"
            }
            "${docId}_${safeName}.$guessedExt"
        }

        val destFile = File(vaultDocsDir(), finalName)
        destFile.outputStream().use { out -> inputStream.copyTo(out) }
        if (!destFile.exists() || destFile.length() == 0L) {
            destFile.delete()
            error("ไฟล์เอกสารว่างเปล่าหรือไม่สามารถคัดลอกลงคลังส่วนตัวได้")
        }

        val sha256 = ImageHashing.sha256(destFile)
        var ocrText = ""
        var extractedAmountSatang: Long? = null

        val isImage = mimeType.startsWith("image/", ignoreCase = true) ||
            ext in setOf("jpg", "jpeg", "png", "webp")

        if (isImage) {
            val outcome = PaddleOcrEngine(context).recognizeSlip(destFile)
            if (outcome.modelStatus == OcrModelStatus.READY) {
                ocrText = outcome.rawText
                extractedAmountSatang = outcome.parsedSlip.amountSatang
            } else {
                ocrText = outcome.diagnosticMessage
            }
        } else if (mimeType.contains("pdf", ignoreCase = true) || ext == "pdf") {
            ocrText = "เอกสาร PDF เก็บในคลังส่วนตัวแล้ว (ตรวจสอบ SHA-256 และยืนยันยอดด้วยตนเอง)"
        }

        return TaxDocumentEntity(
            id = docId,
            taxYearBe = taxYearBe,
            category = category,
            title = customTitle?.takeIf { it.isNotBlank() } ?: originalFileName,
            mimeType = mimeType,
            privateFilePath = destFile.absolutePath,
            sha256 = sha256,
            fileSizeBytes = destFile.length(),
            linkedTransactionId = linkedTransactionId,
            linkedDeductionId = linkedDeductionId,
            ocrExtractedText = ocrText,
            extractedAmountSatang = extractedAmountSatang,
            notes = notes,
            verificationStatus = DocumentVerificationStatus.PENDING_REVIEW
        )
    }

    fun deleteLocalFileSafely(doc: TaxDocumentEntity) {
        if (doc.privateFilePath.isBlank()) return
        runCatching {
            val file = File(doc.privateFilePath)
            if (file.exists() && file.parentFile?.absolutePath == vaultDocsDir().absolutePath) {
                file.delete()
            }
        }
    }

    fun suggestedDeductionType(category: TaxDocumentCategory): DeductionType? = when (category) {
        TaxDocumentCategory.SOCIAL_SECURITY -> DeductionType.SOCIAL_SECURITY
        TaxDocumentCategory.LIFE_INSURANCE -> DeductionType.LIFE_INSURANCE
        TaxDocumentCategory.HEALTH_INSURANCE -> DeductionType.HEALTH_INSURANCE
        TaxDocumentCategory.RETIREMENT_FUND -> DeductionType.RMF
        TaxDocumentCategory.INVESTMENT -> DeductionType.THAI_ESG
        TaxDocumentCategory.DONATION -> DeductionType.DONATION_GENERAL
        TaxDocumentCategory.MORTGAGE_INTEREST -> DeductionType.HOME_LOAN_INTEREST
        else -> null
    }

    fun buildConfirmedDeductionFromDocument(
        doc: TaxDocumentEntity,
        amountSatang: Long,
        deductionType: DeductionType
    ): TaxDeductionEntity {
        return TaxDeductionEntity(
            taxYearBe = doc.taxYearBe,
            deductionType = deductionType,
            label = "${deductionType.labelTh} (${doc.title})",
            amountSatang = amountSatang,
            maxAllowedSatang = deductionType.defaultCapSatang,
            isUserConfirmed = true,
            notes = "เชื่อมโยงจากคลังเอกสารภาษี ID: ${doc.id}"
        )
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }
}
