package com.example.worker

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.example.data.local.AppDatabase
import com.example.data.local.AttachmentEntity
import com.example.data.local.CategorizationRuleEntity
import com.example.data.local.ImageImportState
import com.example.data.local.ImportedImageEntity
import com.example.data.local.OcrResultEntity
import com.example.data.local.ReviewStatus
import com.example.data.local.RuleMatchField
import com.example.data.local.ScanSourceEntity
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionSource
import com.example.data.local.TransactionType
import com.example.domain.ImageHashing
import com.example.domain.PaddleOcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

data class ScanRunSummary(
    val totalFound: Int,
    val newlyImported: Int,
    val duplicatesSkipped: Int,
    val similarFlagged: Int,
    val failedCount: Int,
    val permissionRevoked: Boolean,
    val statusMessage: String
)

sealed class CleanerDeleteResult {
    data object Deleted : CleanerDeleteResult()
    data class RejectedSafety(val reason: String) : CleanerDeleteResult()
    data class ProviderDenied(val reason: String) : CleanerDeleteResult()
}

/**
 * 9-Step Safe Image Import Pipeline + Safe K PLUS Storage Cleaner.
 */
class KPlusScannerPipeline(private val context: Context) {

    private val supportedExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    private val ocrEngine by lazy { PaddleOcrEngine(context) }

    fun vaultImagesDir(): File = File(context.filesDir, "vault_images").apply { mkdirs() }

    suspend fun registerFolderUri(treeUri: Uri): ScanSourceEntity = withContext(Dispatchers.IO) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        }.onFailure {
            // Fallback to read-only persistable permission if provider does not grant write
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        val doc = DocumentFile.fromTreeUri(context, treeUri)
        val displayName = doc?.name ?: "K PLUS Folder"
        val entity = ScanSourceEntity(
            id = "kplus_primary",
            treeUri = treeUri.toString(),
            displayName = displayName,
            lastStatus = "เชื่อมต่อโฟลเดอร์สำเร็จ พร้อมสแกน",
            permissionValid = true
        )
        AppDatabase.getInstance(context).scanSourceDao().upsert(entity)
        entity
    }

    fun hasPersistedPermission(treeUriString: String): Boolean {
        val target = Uri.parse(treeUriString)
        return context.contentResolver.persistedUriPermissions.any { perm ->
            perm.uri == target && perm.isReadPermission
        }
    }

    suspend fun scanConfiguredFolder(pHashThreshold: Int = ImageHashing.DEFAULT_PHASH_SIMILARITY_THRESHOLD): ScanRunSummary =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            val sourceDao = db.scanSourceDao()
            val source = sourceDao.getPrimaryOnce()
                ?: return@withContext ScanRunSummary(
                    0, 0, 0, 0, 0, false,
                    "ยังไม่ได้เลือกโฟลเดอร์ K PLUS กรุณาเลือกโฟลเดอร์ผ่าน Storage Access Framework"
                )

            val treeUri = Uri.parse(source.treeUri)
            if (!hasPersistedPermission(source.treeUri)) {
                sourceDao.upsert(
                    source.copy(
                        permissionValid = false,
                        lastScanEpochMillis = System.currentTimeMillis(),
                        lastStatus = "สิทธิ์เข้าถึงโฟลเดอร์ถูกยกเลิก",
                        lastErrorMessage = "กรุณาเลือกโฟลเดอร์ K PLUS อีกครั้ง"
                    )
                )
                return@withContext ScanRunSummary(
                    0, 0, 0, 0, 0, true,
                    "สิทธิ์เข้าถึงโฟลเดอร์ K PLUS ถูกยกเลิก โปรดเลือกโฟลเดอร์ใหม่"
                )
            }

            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            if (rootDoc == null || !rootDoc.exists() || !rootDoc.canRead()) {
                sourceDao.upsert(
                    source.copy(
                        permissionValid = false,
                        lastScanEpochMillis = System.currentTimeMillis(),
                        lastStatus = "ไม่สามารถอ่านโฟลเดอร์ได้",
                        lastErrorMessage = "ไม่พบโฟลเดอร์หรือสิทธิ์การอ่านไม่สมบูรณ์"
                    )
                )
                return@withContext ScanRunSummary(
                    0, 0, 0, 0, 0, true,
                    "ไม่สามารถเปิดอ่านโฟลเดอร์ที่เลือกไว้ได้ โปรดเชื่อมต่อใหม่"
                )
            }

            val imageFiles = collectImagesRecursively(rootDoc)
            var newlyImported = 0
            var duplicatesSkipped = 0
            var similarFlagged = 0
            var failedCount = 0

            val existingVerified = db.importedImageDao().getVerifiedOnce()

            for (doc in imageFiles) {
                val uriString = doc.uri.toString()
                val fileName = doc.name ?: "slip_${System.currentTimeMillis()}.jpg"
                val mimeType = doc.type ?: guessMimeType(fileName)
                val sizeBytes = doc.length()
                val modifiedAt = doc.lastModified()

                val outcome = importSingleDocument(
                    db = db,
                    sourceUri = doc.uri,
                    sourceFolderUri = source.treeUri,
                    fileName = fileName,
                    mimeType = mimeType,
                    expectedSize = sizeBytes,
                    lastModified = modifiedAt,
                    existingVerified = existingVerified,
                    pHashThreshold = pHashThreshold
                )
                when (outcome) {
                    ImageImportState.IMPORTED_VERIFIED -> newlyImported++
                    ImageImportState.DUPLICATE -> duplicatesSkipped++
                    ImageImportState.SIMILAR_IMAGE -> similarFlagged++
                    ImageImportState.FAILED -> failedCount++
                    ImageImportState.PENDING -> failedCount++
                }
            }

            val now = System.currentTimeMillis()
            val msg = "สแกนเสร็จสิ้น: พบ ${imageFiles.size} ภาพ • นำเข้าใหม่ $newlyImported • ซ้ำข้าม $duplicatesSkipped • คล้ายรอตรวจ $similarFlagged"
            sourceDao.upsert(
                source.copy(
                    permissionValid = true,
                    lastScanEpochMillis = now,
                    lastSuccessEpochMillis = now,
                    lastStatus = msg,
                    lastErrorMessage = if (failedCount > 0) "นำเข้าไม่สำเร็จ $failedCount ไฟล์" else "",
                    scannedCount = imageFiles.size,
                    importedCount = source.importedCount + newlyImported
                )
            )

            ScanRunSummary(
                totalFound = imageFiles.size,
                newlyImported = newlyImported,
                duplicatesSkipped = duplicatesSkipped,
                similarFlagged = similarFlagged,
                failedCount = failedCount,
                permissionRevoked = false,
                statusMessage = msg
            )
        }

    /**
     * Imports a single image via SAF or Photo Picker into private storage following all 9 verification steps.
     */
    suspend fun importSingleUri(
        sourceUri: Uri,
        sourceFolderUri: String = "manual_picker",
        fileName: String = "slip_${System.currentTimeMillis()}.jpg",
        mimeType: String = "image/jpeg",
        expectedSize: Long = -1L,
        pHashThreshold: Int = ImageHashing.DEFAULT_PHASH_SIMILARITY_THRESHOLD
    ): ImageImportState = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val existingVerified = db.importedImageDao().getVerifiedOnce()
        importSingleDocument(
            db = db,
            sourceUri = sourceUri,
            sourceFolderUri = sourceFolderUri,
            fileName = fileName,
            mimeType = mimeType,
            expectedSize = expectedSize,
            lastModified = System.currentTimeMillis(),
            existingVerified = existingVerified,
            pHashThreshold = pHashThreshold
        )
    }

    private suspend fun importSingleDocument(
        db: AppDatabase,
        sourceUri: Uri,
        sourceFolderUri: String,
        fileName: String,
        mimeType: String,
        expectedSize: Long,
        lastModified: Long,
        existingVerified: List<ImportedImageEntity>,
        pHashThreshold: Int
    ): ImageImportState {
        val imageId = UUID.randomUUID().toString()
        val ext = fileName.substringAfterLast('.', "jpg").lowercase(Locale.US)
        val vaultDir = vaultImagesDir()
        val tempFile = File(vaultDir, "$imageId.$ext.tmp")
        val finalFile = File(vaultDir, "$imageId.$ext")

        return try {
            // STEP 2: Calculate SHA-256 of source image
            val sourceSha256 = context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                ImageHashing.sha256(stream)
            } ?: throw IllegalStateException("ไม่สามารถเปิดอ่านไฟล์ภาพต้นทางได้")

            // STEP 3: Exact duplicate prevention via SHA-256
            val existingExact = db.importedImageDao().findBySha256(sourceSha256)
            if (existingExact != null && existingExact.importState == ImageImportState.IMPORTED_VERIFIED) {
                return ImageImportState.DUPLICATE
            }

            // STEP 5: Copy original image to temp file in OverseerFinance private storage
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            } ?: throw IllegalStateException("การคัดลอกไฟล์ถูกขัดจังหวะ")

            // STEP 6: Verify copied file size, readability, and SHA-256 match
            if (!tempFile.exists() || tempFile.length() <= 0L) {
                throw IllegalStateException("ไฟล์คัดลอกมีขนาด 0 ไบต์")
            }
            if (expectedSize > 0L && tempFile.length() != expectedSize) {
                throw IllegalStateException("ขนาดไฟล์ที่คัดลอกไม่ตรงกับต้นฉบับ")
            }
            val copiedSha256 = ImageHashing.sha256(tempFile)
            if (copiedSha256 != sourceSha256) {
                throw IllegalStateException("ค่า SHA-256 ของไฟล์สำเนาไม่ตรงกับต้นฉบับ")
            }
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tempFile.absolutePath, boundsOpts)
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
                throw IllegalStateException("ไฟล์ภาพเสียหายหรือไม่สามารถถอดรหัสภาพได้")
            }

            // Atomic rename temp -> final private file
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            // STEP 4: Calculate perceptual hash (pHash) on normalized downsampled bitmap
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = computeSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, 256)
            }
            val bitmap = BitmapFactory.decodeFile(finalFile.absolutePath, decodeOpts)
            val pHash = if (bitmap != null) {
                try {
                    ImageHashing.computePerceptualHash(bitmap)
                } finally {
                    bitmap.recycle()
                }
            } else ""

            val similarMatch = if (pHash.isNotBlank()) {
                existingVerified.firstOrNull { prev ->
                    ImageHashing.isVisuallySimilar(prev.pHash, pHash, pHashThreshold)
                }
            } else null

            val finalState = if (similarMatch != null) {
                ImageImportState.SIMILAR_IMAGE
            } else {
                ImageImportState.IMPORTED_VERIFIED
            }

            // STEP 7: Save verified image metadata in SQLite
            val imageEntity = ImportedImageEntity(
                id = imageId,
                sourceUri = sourceUri.toString(),
                sourceFolderUri = sourceFolderUri,
                originalFileName = fileName,
                mimeType = mimeType,
                fileSizeBytes = finalFile.length(),
                sourceLastModifiedEpochMillis = lastModified,
                sha256 = copiedSha256,
                pHash = pHash,
                privateFilePath = finalFile.absolutePath,
                importState = finalState,
                similarToImageId = similarMatch?.id
            )
            db.importedImageDao().upsert(imageEntity)

            // STEP 8: Perform local offline OCR
            val ocrOutcome = ocrEngine.recognizeSlip(finalFile)
            val parsed = ocrOutcome.parsedSlip
            val ocrEntity = OcrResultEntity(
                importedImageId = imageId,
                rawText = ocrOutcome.rawText,
                engineName = ocrOutcome.engineName,
                modelVersion = ocrOutcome.modelVersion,
                modelStatus = ocrOutcome.modelStatus,
                detectedBank = parsed.detectedBank,
                parsedAmountSatang = parsed.amountSatang,
                amountConfidence = parsed.amountConfidence,
                parsedDateEpochMillis = parsed.dateEpochMillis,
                dateConfidence = parsed.dateConfidence,
                parsedTimeText = parsed.timeText,
                timeConfidence = parsed.timeConfidence,
                parsedSender = parsed.senderName,
                senderConfidence = parsed.senderConfidence,
                parsedRecipient = parsed.recipientName,
                recipientConfidence = parsed.recipientConfidence,
                parsedReference = parsed.paymentReference,
                referenceConfidence = parsed.referenceConfidence,
                parsedAccountHint = parsed.accountHint,
                overallConfidence = parsed.overallConfidence,
                needsReview = true
            )
            db.ocrResultDao().upsert(ocrEntity)

            // STEP 9: Create Transaction draft (never assumes incoming payment is taxable income)
            val rules = db.categorizationRuleDao().getEnabledOnce()
            val accounts = db.accountDao().getActiveOnce()
            val defaultAccountId = accounts.firstOrNull()?.id ?: "acc_kbank_main"
            val matchedRule = findMatchingRule(
                rules = rules,
                sender = parsed.senderName,
                recipient = parsed.recipientName,
                description = fileName
            )

            val txId = UUID.randomUUID().toString()
            val draftTx = TransactionEntity(
                id = txId,
                amountSatang = parsed.amountSatang ?: 0L,
                currency = "THB",
                type = matchedRule?.transactionType ?: TransactionType.OTHER,
                timestampEpochMillis = parsed.dateEpochMillis ?: lastModified.takeIf { it > 0L } ?: System.currentTimeMillis(),
                description = buildDraftDescription(parsed.detectedBank, parsed.recipientName, fileName),
                categoryId = matchedRule?.categoryId,
                accountId = defaultAccountId,
                senderName = parsed.senderName,
                recipientName = parsed.recipientName,
                paymentReference = parsed.paymentReference,
                notes = ocrOutcome.diagnosticMessage,
                source = TransactionSource.K_PLUS_OCR,
                taxSection = matchedRule?.taxSection ?: com.example.data.local.TaxSection.NONE,
                isBusiness = matchedRule?.isBusiness ?: false,
                reviewStatus = ReviewStatus.NEEDS_REVIEW
            )
            db.transactionDao().upsert(draftTx)
            db.attachmentDao().upsert(
                AttachmentEntity(
                    transactionId = txId,
                    importedImageId = imageId,
                    localFilePath = finalFile.absolutePath,
                    originalFileName = fileName,
                    mimeType = mimeType,
                    fileSizeBytes = finalFile.length(),
                    sha256 = copiedSha256
                )
            )
            finalState
        } catch (t: Throwable) {
            tempFile.delete()
            finalFile.delete()
            // Record FAILED state for audit & retry without marking as imported
            runCatching {
                val failId = UUID.randomUUID().toString()
                db.importedImageDao().upsert(
                    ImportedImageEntity(
                        id = failId,
                        sourceUri = sourceUri.toString(),
                        sourceFolderUri = sourceFolderUri,
                        originalFileName = fileName,
                        mimeType = mimeType,
                        fileSizeBytes = expectedSize.coerceAtLeast(0L),
                        sourceLastModifiedEpochMillis = lastModified,
                        sha256 = "failed_$failId",
                        privateFilePath = "",
                        importState = ImageImportState.FAILED,
                        errorMessage = t.message ?: "นำเข้าไม่สำเร็จ"
                    )
                )
            }
            ImageImportState.FAILED
        }
    }

    /**
     * K Plus Storage Cleaner: Deletes ONLY the original file in the SAF folder after
     * strict verification that the OverseerFinance private copy exists and matches SHA-256.
     */
    suspend fun deleteOriginalSafImageSafely(image: ImportedImageEntity): CleanerDeleteResult =
        withContext(Dispatchers.IO) {
            if (image.importState == ImageImportState.PENDING || image.importState == ImageImportState.FAILED) {
                return@withContext CleanerDeleteResult.RejectedSafety(
                    "ไม่อนุญาตให้ลบต้นฉบับที่ยังไม่ผ่านการนำเข้าและตรวจสอบแฮช (PENDING/FAILED)"
                )
            }
            if (image.importState != ImageImportState.IMPORTED_VERIFIED) {
                return@withContext CleanerDeleteResult.RejectedSafety(
                    "ลบได้เฉพาะไฟล์สถานะ IMPORTED_VERIFIED เท่านั้น"
                )
            }
            val privateCopy = File(image.privateFilePath)
            if (image.privateFilePath.isBlank() || !privateCopy.exists() || !privateCopy.isFile) {
                return@withContext CleanerDeleteResult.RejectedSafety(
                    "ไม่พบไฟล์สำเนาในคลังของ OverseerFinance จึงระงับการลบต้นฉบับ"
                )
            }
            val verifiedCopySha = runCatching { ImageHashing.sha256(privateCopy) }.getOrNull()
            if (verifiedCopySha != image.sha256) {
                return@withContext CleanerDeleteResult.RejectedSafety(
                    "ค่า SHA-256 ของไฟล์สำเนาไม่ตรงกัน จึงไม่อนุญาตให้ลบต้นฉบับ"
                )
            }
            if (image.sourceUri.startsWith("manual_picker") || !image.sourceUri.startsWith("content://")) {
                return@withContext CleanerDeleteResult.ProviderDenied(
                    "ไฟล์นี้ไม่ได้มาจากโฟลเดอร์ SAF ของ K PLUS กรุณาลบจากแกลเลอรีด้วยตนเอง"
                )
            }

            val uri = Uri.parse(image.sourceUri)
            val doc = DocumentFile.fromSingleUri(context, uri)
            if (doc == null || !doc.exists()) {
                AppDatabase.getInstance(context).importedImageDao()
                    .markSourceDeleted(image.id, System.currentTimeMillis())
                return@withContext CleanerDeleteResult.Deleted
            }
            if (!doc.canWrite()) {
                return@withContext CleanerDeleteResult.ProviderDenied(
                    "ผู้ให้บริการจัดเก็บไฟล์ไม่อนุญาตให้แอปอื่นลบไฟล์นี้ โปรดลบภาพต้นฉบับใน K PLUS ด้วยตนเอง"
                )
            }

            val deleted = runCatching {
                doc.delete() || DocumentsContract.deleteDocument(context.contentResolver, uri)
            }.getOrDefault(false)

            if (deleted) {
                AppDatabase.getInstance(context).importedImageDao()
                    .markSourceDeleted(image.id, System.currentTimeMillis())
                CleanerDeleteResult.Deleted
            } else {
                CleanerDeleteResult.ProviderDenied(
                    "Android ปฏิเสธคำขอลบไฟล์ต้นฉบับจากผู้ให้บริการนี้ กรุณาลบภาพต้นฉบับในเครื่องด้วยตนเอง (สำเนาใน OverseerFinance ยังอยู่ครบถ้วน)"
                )
            }
        }

    /**
     * Re-runs local PaddleOCR inference on NEEDS_REVIEW slip drafts whose original private image exists.
     * Preserves any manual edits the user already filled while updating blank OCR fields.
     */
    suspend fun reprocessPendingOcrSlips(): Int = withContext(Dispatchers.IO) {
        if (ocrEngine.inspectModelAvailability() != com.example.data.local.OcrModelStatus.READY) {
            return@withContext 0
        }
        val db = AppDatabase.getInstance(context)
        val pendingTxs = db.transactionDao().getAllOnce().filter { it.reviewStatus == ReviewStatus.NEEDS_REVIEW }
        if (pendingTxs.isEmpty()) return@withContext 0

        val images = db.importedImageDao().getAllOnce().associateBy { it.id }
        var updatedCount = 0

        for (tx in pendingTxs) {
            val att = db.attachmentDao().getForTransactionOnce(tx.id).firstOrNull() ?: continue
            val img = att.importedImageId?.let { images[it] } ?: continue
            val localFile = File(att.localFilePath.ifBlank { img.privateFilePath })
            if (!localFile.exists()) continue

            val ocrOutcome = ocrEngine.recognizeSlip(localFile)
            val parsed = ocrOutcome.parsedSlip
            val ocrEntity = OcrResultEntity(
                importedImageId = img.id,
                rawText = ocrOutcome.rawText,
                engineName = ocrOutcome.engineName,
                modelVersion = ocrOutcome.modelVersion,
                modelStatus = ocrOutcome.modelStatus,
                detectedBank = parsed.detectedBank,
                parsedAmountSatang = parsed.amountSatang,
                amountConfidence = parsed.amountConfidence,
                parsedDateEpochMillis = parsed.dateEpochMillis,
                dateConfidence = parsed.dateConfidence,
                parsedTimeText = parsed.timeText,
                timeConfidence = parsed.timeConfidence,
                parsedSender = parsed.senderName,
                senderConfidence = parsed.senderConfidence,
                parsedRecipient = parsed.recipientName,
                recipientConfidence = parsed.recipientConfidence,
                parsedReference = parsed.paymentReference,
                referenceConfidence = parsed.referenceConfidence,
                parsedAccountHint = parsed.accountHint,
                overallConfidence = parsed.overallConfidence,
                needsReview = true
            )
            db.ocrResultDao().upsert(ocrEntity)

            val updatedTx = tx.copy(
                amountSatang = if (tx.amountSatang <= 0L && (parsed.amountSatang ?: 0L) > 0L) parsed.amountSatang!! else tx.amountSatang,
                senderName = tx.senderName.ifBlank { parsed.senderName },
                recipientName = tx.recipientName.ifBlank { parsed.recipientName },
                paymentReference = tx.paymentReference.ifBlank { parsed.paymentReference },
                notes = ocrOutcome.diagnosticMessage,
                updatedAtEpochMillis = System.currentTimeMillis()
            )
            db.transactionDao().upsert(updatedTx)
            updatedCount++
        }
        updatedCount
    }

    private fun collectImagesRecursively(dir: DocumentFile): List<DocumentFile> {
        val result = mutableListOf<DocumentFile>()
        val children = runCatching { dir.listFiles() }.getOrDefault(emptyArray())
        for (child in children) {
            if (child.isDirectory) {
                result.addAll(collectImagesRecursively(child))
            } else if (child.isFile) {
                val name = child.name.orEmpty()
                val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
                if (ext in supportedExtensions || (child.type?.startsWith("image/") == true)) {
                    result.add(child)
                }
            }
        }
        return result
    }

    private fun findMatchingRule(
        rules: List<CategorizationRuleEntity>,
        sender: String,
        recipient: String,
        description: String
    ): CategorizationRuleEntity? {
        for (rule in rules) {
            val kw = rule.keywordOrRecipient.trim()
            if (kw.isEmpty()) continue
            val matched = when (rule.matchField) {
                RuleMatchField.RECIPIENT -> recipient.contains(kw, ignoreCase = true)
                RuleMatchField.SENDER -> sender.contains(kw, ignoreCase = true)
                RuleMatchField.DESCRIPTION -> description.contains(kw, ignoreCase = true)
                RuleMatchField.ANY -> recipient.contains(kw, true) ||
                    sender.contains(kw, true) ||
                    description.contains(kw, true)
            }
            if (matched) return rule
        }
        return null
    }

    private fun buildDraftDescription(bank: String, recipient: String, fileName: String): String {
        return when {
            bank.isNotBlank() && recipient.isNotBlank() -> "$bank → $recipient"
            recipient.isNotBlank() -> "สลิปโอนไปยัง $recipient"
            bank.isNotBlank() -> "สลิป $bank ($fileName)"
            else -> "สลิปรอตรวจสอบ ($fileName)"
        }
    }

    private fun guessMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase(Locale.US)) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            else -> "image/jpeg"
        }
    }

    private fun computeSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        while (width / sample > maxDim || height / sample > maxDim) {
            sample *= 2
        }
        return sample
    }
}
