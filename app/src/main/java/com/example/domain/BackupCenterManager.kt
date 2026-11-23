package com.example.domain

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.StatFs
import com.example.data.local.AppDatabase
import com.example.data.local.BackupFormat
import com.example.data.local.BackupHistoryEntity
import com.example.data.local.BackupStatus
import com.example.data.local.DatabaseMigrations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class BackupImageManifestEntry(
    val imageId: String,
    val archivePath: String,
    val originalFileName: String,
    val mimeType: String,
    val sha256: String,
    val fileSizeBytes: Long
)

@Serializable
data class BackupManifest(
    val appName: String = "OverseerFinance",
    val formatVersion: Int = 1,
    val appVersion: String,
    val schemaVersion: Int,
    val createdAtEpochMillis: Long,
    val transactionCount: Int,
    val imageCount: Int,
    val dbSha256: String,
    val dbSizeBytes: Long,
    val images: List<BackupImageManifestEntry>
)

data class BackupPreviewResult(
    val format: BackupFormat,
    val appVersion: String,
    val schemaVersion: Int,
    val createdAtEpochMillis: Long,
    val transactionCount: Int,
    val imageCount: Int,
    val estimatedRequiredBytes: Long,
    val integrityPassed: Boolean,
    val verificationDetails: List<String>
)

class BackupManagerException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Core Backup & Recovery Engine for OverseerFinance Backup Center.
 *
 * Supports:
 * 1. Encrypted Full Recovery (.efbackup) via AES-256-GCM + ZIP + manifest + SHA-256 checks
 * 2. Portable Self-Contained SQLite (.sqlite) including embedded portable_image_blobs
 * 3. Pre-restore Integrity Verification & Preview
 * 4. Staged Atomic Full Restore (Option A: Replace Current Data) with automatic rollback on any failure
 */
class BackupCenterManager(
    private val context: Context,
    private val appVersion: String = "1.0.0"
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun vaultImagesDir(): File = File(context.filesDir, "vault_images").apply { mkdirs() }

    suspend fun exportEncryptedEfBackup(
        outputStream: OutputStream,
        fileName: String,
        password: String
    ): Result<BackupHistoryEntity> = withContext(Dispatchers.IO) {
        val stagingDir = File(context.cacheDir, "efbackup_export_${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val db = AppDatabase.getInstance(context)
            val snapshotFile = createConsistentDbSnapshot(db, File(stagingDir, "overseer_snapshot.sqlite"))
            val dbSha256 = ImageHashing.sha256(snapshotFile)

            val txCount = db.transactionDao().getAllOnce().size
            val storedImages = db.importedImageDao().getAllOnce()
            val imageEntries = mutableListOf<BackupImageManifestEntry>()

            val zipBuffer = ByteArrayOutputStream()
            ZipOutputStream(zipBuffer).use { zos ->
                // Add SQLite snapshot
                zos.putNextEntry(ZipEntry("overseer_snapshot.sqlite"))
                FileInputStream(snapshotFile).use { it.copyTo(zos) }
                zos.closeEntry()

                // Add every imported private image
                for (img in storedImages) {
                    if (img.privateFilePath.isBlank()) continue
                    val file = File(img.privateFilePath)
                    if (!file.exists() || !file.isFile) continue
                    val actualSha = ImageHashing.sha256(file)
                    if (actualSha != img.sha256) {
                        throw BackupManagerException("ตรวจพบไฟล์ภาพหลักฐานไม่ตรงกับ SHA-256: ${img.originalFileName}")
                    }
                    val archivePath = "images/${img.id}_${sanitizeFileName(img.originalFileName)}"
                    zos.putNextEntry(ZipEntry(archivePath))
                    FileInputStream(file).use { it.copyTo(zos) }
                    zos.closeEntry()

                    imageEntries.add(
                        BackupImageManifestEntry(
                            imageId = img.id,
                            archivePath = archivePath,
                            originalFileName = img.originalFileName,
                            mimeType = img.mimeType,
                            sha256 = actualSha,
                            fileSizeBytes = file.length()
                        )
                    )
                }

                val manifest = BackupManifest(
                    appVersion = appVersion,
                    schemaVersion = DatabaseMigrations.CURRENT_SCHEMA_VERSION,
                    createdAtEpochMillis = System.currentTimeMillis(),
                    transactionCount = txCount,
                    imageCount = imageEntries.size,
                    dbSha256 = dbSha256,
                    dbSizeBytes = snapshotFile.length(),
                    images = imageEntries
                )
                val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
                zos.putNextEntry(ZipEntry("manifest.json"))
                zos.write(manifestBytes)
                zos.closeEntry()
            }

            val payloadBytes = zipBuffer.toByteArray()
            val tempEncryptedFile = File(stagingDir, "package.efbackup.tmp")
            FileOutputStream(tempEncryptedFile).use { fos ->
                BackupCrypto.encryptToStream(
                    plaintextPayload = payloadBytes,
                    password = password,
                    schemaVersion = DatabaseMigrations.CURRENT_SCHEMA_VERSION,
                    appVersion = appVersion,
                    outputStream = fos
                )
            }

            // Self-verify written encrypted file before finalizing to destination stream
            FileInputStream(tempEncryptedFile).use { fis ->
                BackupCrypto.decryptFromStream(fis, password)
            }

            val encryptedSha = ImageHashing.sha256(tempEncryptedFile)
            val encryptedSize = tempEncryptedFile.length()

            FileInputStream(tempEncryptedFile).use { fis ->
                fis.copyTo(outputStream)
            }
            outputStream.flush()

            val history = BackupHistoryEntity(
                fileName = fileName,
                format = BackupFormat.EFBACKUP,
                fileSizeBytes = encryptedSize,
                sha256 = encryptedSha,
                transactionCount = txCount,
                imageCount = imageEntries.size,
                status = BackupStatus.VERIFIED
            )
            db.backupHistoryDao().insert(history)
            Result.success(history)
        } catch (t: Throwable) {
            runCatching {
                AppDatabase.getInstance(context).backupHistoryDao().insert(
                    BackupHistoryEntity(
                        fileName = fileName,
                        format = BackupFormat.EFBACKUP,
                        fileSizeBytes = 0L,
                        sha256 = "",
                        transactionCount = 0,
                        imageCount = 0,
                        status = BackupStatus.FAILED,
                        errorMessage = t.message ?: "Export failed"
                    )
                )
            }
            Result.failure(t)
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    suspend fun exportPortableSqlite(
        outputStream: OutputStream,
        fileName: String
    ): Result<BackupHistoryEntity> = withContext(Dispatchers.IO) {
        val stagingDir = File(context.cacheDir, "sqlite_export_${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val db = AppDatabase.getInstance(context)
            val portableDbFile = createConsistentDbSnapshot(db, File(stagingDir, "portable.sqlite"))
            val txCount = db.transactionDao().getAllOnce().size
            val storedImages = db.importedImageDao().getAllOnce()
            val imageEntries = mutableListOf<BackupImageManifestEntry>()

            val rawSqlite = SQLiteDatabase.openDatabase(
                portableDbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            )
            try {
                rawSqlite.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS portable_image_blobs (
                        image_id TEXT PRIMARY KEY,
                        file_name TEXT NOT NULL,
                        mime_type TEXT NOT NULL,
                        sha256 TEXT NOT NULL,
                        file_size INTEGER NOT NULL,
                        data BLOB NOT NULL
                    )
                    """.trimIndent()
                )
                rawSqlite.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS portable_backup_manifest (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """.trimIndent()
                )

                rawSqlite.beginTransaction()
                try {
                    for (img in storedImages) {
                        if (img.privateFilePath.isBlank()) continue
                        val file = File(img.privateFilePath)
                        if (!file.exists() || !file.isFile) continue
                        val bytes = file.readBytes()
                        val sha = ImageHashing.sha256(bytes)
                        val cv = ContentValues().apply {
                            put("image_id", img.id)
                            put("file_name", img.originalFileName)
                            put("mime_type", img.mimeType)
                            put("sha256", sha)
                            put("file_size", bytes.size.toLong())
                            put("data", bytes)
                        }
                        rawSqlite.insertWithOnConflict(
                            "portable_image_blobs",
                            null,
                            cv,
                            SQLiteDatabase.CONFLICT_REPLACE
                        )
                        imageEntries.add(
                            BackupImageManifestEntry(
                                imageId = img.id,
                                archivePath = "blob:${img.id}",
                                originalFileName = img.originalFileName,
                                mimeType = img.mimeType,
                                sha256 = sha,
                                fileSizeBytes = bytes.size.toLong()
                            )
                        )
                    }

                    val manifest = BackupManifest(
                        appVersion = appVersion,
                        schemaVersion = DatabaseMigrations.CURRENT_SCHEMA_VERSION,
                        createdAtEpochMillis = System.currentTimeMillis(),
                        transactionCount = txCount,
                        imageCount = imageEntries.size,
                        dbSha256 = "",
                        dbSizeBytes = portableDbFile.length(),
                        images = imageEntries
                    )
                    val manifestCv = ContentValues().apply {
                        put("key", "manifest_json")
                        put("value", json.encodeToString(manifest))
                    }
                    rawSqlite.insertWithOnConflict(
                        "portable_backup_manifest",
                        null,
                        manifestCv,
                        SQLiteDatabase.CONFLICT_REPLACE
                    )
                    rawSqlite.setTransactionSuccessful()
                } finally {
                    rawSqlite.endTransaction()
                }
                verifySqliteIntegrity(rawSqlite)
            } finally {
                rawSqlite.close()
            }

            val sha = ImageHashing.sha256(portableDbFile)
            val size = portableDbFile.length()
            FileInputStream(portableDbFile).use { it.copyTo(outputStream) }
            outputStream.flush()

            val history = BackupHistoryEntity(
                fileName = fileName,
                format = BackupFormat.SQLITE,
                fileSizeBytes = size,
                sha256 = sha,
                transactionCount = txCount,
                imageCount = imageEntries.size,
                status = BackupStatus.VERIFIED
            )
            db.backupHistoryDao().insert(history)
            Result.success(history)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    suspend fun verifyAndPreviewEfBackup(
        inputStream: InputStream,
        password: String
    ): Result<BackupPreviewResult> = withContext(Dispatchers.IO) {
        val stagingDir = File(context.cacheDir, "ef_preview_${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val (_, plaintextZip) = BackupCrypto.decryptFromStream(inputStream, password)
            val staged = extractAndVerifyEfZip(plaintextZip, stagingDir)
            Result.success(staged.preview)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    suspend fun verifyAndPreviewSqlite(inputStream: InputStream): Result<BackupPreviewResult> =
        withContext(Dispatchers.IO) {
            val stagingDir = File(context.cacheDir, "sq_preview_${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val stagedFile = File(stagingDir, "candidate.sqlite")
                FileOutputStream(stagedFile).use { fos -> inputStream.copyTo(fos) }
                val staged = inspectAndStagePortableSqlite(stagedFile, stagingDir)
                Result.success(staged.preview)
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                stagingDir.deleteRecursively()
            }
        }

    /**
     * Option A: FULL RESTORE / REPLACE CURRENT DATA.
     * Stages all DB & images, verifies integrity + storage space, creates a rollback snapshot,
     * and atomically swaps live data. Rolls back untouched original data on any error.
     */
    suspend fun restoreEfBackupFullReplace(
        inputStream: InputStream,
        password: String
    ): Result<BackupPreviewResult> = withContext(Dispatchers.IO) {
        val stagingDir = File(context.cacheDir, "restore_ef_${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val (_, plaintextZip) = BackupCrypto.decryptFromStream(inputStream, password)
            val staged = extractAndVerifyEfZip(plaintextZip, stagingDir)
            performAtomicFullSwap(staged.stagedDbFile, staged.stagedImagesDir, staged.imagePathRemap)
            Result.success(staged.preview)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    suspend fun restoreSqliteFullReplace(inputStream: InputStream): Result<BackupPreviewResult> =
        withContext(Dispatchers.IO) {
            val stagingDir = File(context.cacheDir, "restore_sq_${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val candidate = File(stagingDir, "candidate.sqlite")
                FileOutputStream(candidate).use { fos -> inputStream.copyTo(fos) }
                val staged = inspectAndStagePortableSqlite(candidate, stagingDir)
                performAtomicFullSwap(staged.stagedDbFile, staged.stagedImagesDir, staged.imagePathRemap)
                Result.success(staged.preview)
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                stagingDir.deleteRecursively()
            }
        }

    private data class StagedBackupBundle(
        val stagedDbFile: File,
        val stagedImagesDir: File,
        val imagePathRemap: Map<String, String>,
        val preview: BackupPreviewResult
    )

    private fun extractAndVerifyEfZip(
        zipBytes: ByteArray,
        stagingDir: File
    ): StagedBackupBundle {
        val details = mutableListOf<String>()
        val stagedImagesDir = File(stagingDir, "images").apply { mkdirs() }
        var manifest: BackupManifest? = null
        var dbFile: File? = null
        val extractedEntries = mutableMapOf<String, File>()

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val safeName = entry.name.replace("..", "")
                    val target = when {
                        safeName == "manifest.json" -> {
                            val bytes = zis.readBytes()
                            manifest = json.decodeFromString<BackupManifest>(String(bytes, Charsets.UTF_8))
                            null
                        }
                        safeName == "overseer_snapshot.sqlite" -> {
                            File(stagingDir, "overseer_snapshot.sqlite").also { dbFile = it }
                        }
                        safeName.startsWith("images/") -> {
                            File(stagedImagesDir, File(safeName).name)
                        }
                        else -> null
                    }
                    if (target != null) {
                        FileOutputStream(target).use { fos -> zis.copyTo(fos) }
                        extractedEntries[safeName] = target
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val parsedManifest = manifest
            ?: throw BackupManagerException("ไม่พบ manifest.json ในไฟล์สำรองข้อมูล")
        val stagedDb = dbFile
            ?: throw BackupManagerException("ไม่พบไฟล์ฐานข้อมูล SQLite ในไฟล์สำรองข้อมูล")

        if (parsedManifest.schemaVersion > DatabaseMigrations.CURRENT_SCHEMA_VERSION) {
            throw BackupManagerException("เวอร์ชันฐานข้อมูลในไฟล์สำรอง (${parsedManifest.schemaVersion}) ใหม่กว่าแอปปัจจุบัน")
        }
        details.add("ตรวจสอบ Manifest ผ่าน (เวอร์ชันแอป ${parsedManifest.appVersion}, Schema v${parsedManifest.schemaVersion})")

        val actualDbSha = ImageHashing.sha256(stagedDb)
        if (actualDbSha != parsedManifest.dbSha256) {
            throw BackupManagerException("ค่า SHA-256 ของฐานข้อมูลไม่ตรงกัน (ไฟล์อาจเสียหาย)")
        }
        details.add("ตรวจสอบ SHA-256 ฐานข้อมูลผ่าน")

        val checkDb = SQLiteDatabase.openDatabase(stagedDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            verifySqliteIntegrity(checkDb)
        } finally {
            checkDb.close()
        }
        details.add("ตรวจสอบ SQLite PRAGMA integrity_check ผ่าน")

        val liveVault = vaultImagesDir()
        val remap = mutableMapOf<String, String>()
        var totalImageBytes = 0L
        for (img in parsedManifest.images) {
            val extracted = extractedEntries[img.archivePath]
                ?: throw BackupManagerException("ไฟล์ภาพหายไปจากชุดสำรอง: ${img.originalFileName}")
            val sha = ImageHashing.sha256(extracted)
            if (sha != img.sha256) {
                throw BackupManagerException("SHA-256 ของภาพไม่ตรงกัน: ${img.originalFileName}")
            }
            totalImageBytes += extracted.length()
            remap[img.imageId] = File(liveVault, extracted.name).absolutePath
        }
        details.add("ตรวจสอบภาพหลักฐานครบ ${parsedManifest.images.size} ไฟล์")

        val estimated = stagedDb.length() + totalImageBytes
        return StagedBackupBundle(
            stagedDbFile = stagedDb,
            stagedImagesDir = stagedImagesDir,
            imagePathRemap = remap,
            preview = BackupPreviewResult(
                format = BackupFormat.EFBACKUP,
                appVersion = parsedManifest.appVersion,
                schemaVersion = parsedManifest.schemaVersion,
                createdAtEpochMillis = parsedManifest.createdAtEpochMillis,
                transactionCount = parsedManifest.transactionCount,
                imageCount = parsedManifest.imageCount,
                estimatedRequiredBytes = estimated,
                integrityPassed = true,
                verificationDetails = details
            )
        )
    }

    private fun inspectAndStagePortableSqlite(
        candidateDbFile: File,
        stagingDir: File
    ): StagedBackupBundle {
        val details = mutableListOf<String>()
        val stagedImagesDir = File(stagingDir, "images").apply { mkdirs() }
        val db = try {
            SQLiteDatabase.openDatabase(candidateDbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        } catch (e: Exception) {
            throw BackupManagerException("ไฟล์ .sqlite ไม่สามารถเปิดใช้งานได้ หรือเสียหาย", e)
        }

        val liveVault = vaultImagesDir()
        val remap = mutableMapOf<String, String>()
        var txCount = 0
        var imageCount = 0
        var createdAt = candidateDbFile.lastModified()
        var appVer = appVersion
        var schemaVer = DatabaseMigrations.CURRENT_SCHEMA_VERSION
        var totalImageBytes = 0L

        try {
            verifySqliteIntegrity(db)
            details.add("ตรวจสอบ SQLite PRAGMA integrity_check ผ่าน")

            // Confirm core OverseerFinance transactions table exists
            db.rawQuery("SELECT COUNT(*) FROM transactions", null).use { cursor ->
                if (!cursor.moveToFirst()) throw BackupManagerException("ไม่พบตาราง transactions ในไฟล์ .sqlite")
                txCount = cursor.getInt(0)
            }

            // Read manifest if present
            val hasManifest = tableExists(db, "portable_backup_manifest")
            if (hasManifest) {
                db.rawQuery("SELECT value FROM portable_backup_manifest WHERE key = 'manifest_json'", null).use { c ->
                    if (c.moveToFirst()) {
                        val m = json.decodeFromString<BackupManifest>(c.getString(0))
                        appVer = m.appVersion
                        schemaVer = m.schemaVersion
                        createdAt = m.createdAtEpochMillis
                    }
                }
            }

            // Extract embedded image BLOBs if present
            if (tableExists(db, "portable_image_blobs")) {
                db.rawQuery(
                    "SELECT image_id, file_name, sha256, data FROM portable_image_blobs",
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        val imageId = c.getString(0)
                        val fileName = c.getString(1)
                        val expectedSha = c.getString(2)
                        val bytes = c.getBlob(3)
                        val actualSha = ImageHashing.sha256(bytes)
                        if (actualSha != expectedSha) {
                            throw BackupManagerException("ภาพใน BLOB เสียหาย (SHA-256 ไม่ตรง): $fileName")
                        }
                        val outFileName = "${imageId}_${sanitizeFileName(fileName)}"
                        File(stagedImagesDir, outFileName).writeBytes(bytes)
                        remap[imageId] = File(liveVault, outFileName).absolutePath
                        totalImageBytes += bytes.size
                        imageCount++
                    }
                }
                details.add("ตรวจสอบภาพที่ฝังใน SQLite ครบ $imageCount ไฟล์")
                db.execSQL("DROP TABLE IF EXISTS portable_image_blobs")
                db.execSQL("DROP TABLE IF EXISTS portable_backup_manifest")
            }
        } finally {
            db.close()
        }

        if (schemaVer > DatabaseMigrations.CURRENT_SCHEMA_VERSION) {
            throw BackupManagerException("เวอร์ชันฐานข้อมูล ($schemaVer) ใหม่กว่าแอปปัจจุบัน")
        }

        return StagedBackupBundle(
            stagedDbFile = candidateDbFile,
            stagedImagesDir = stagedImagesDir,
            imagePathRemap = remap,
            preview = BackupPreviewResult(
                format = BackupFormat.SQLITE,
                appVersion = appVer,
                schemaVersion = schemaVer,
                createdAtEpochMillis = createdAt,
                transactionCount = txCount,
                imageCount = imageCount,
                estimatedRequiredBytes = candidateDbFile.length() + totalImageBytes,
                integrityPassed = true,
                verificationDetails = details
            )
        )
    }

    private fun performAtomicFullSwap(
        stagedDbFile: File,
        stagedImagesDir: File,
        imagePathRemap: Map<String, String>
    ) {
        val neededBytes = (stagedDbFile.length() + directorySize(stagedImagesDir)) * 2L + 2_000_000L
        val availableBytes = availableInternalStorageBytes()
        if (availableBytes < neededBytes) {
            throw BackupManagerException(
                "พื้นที่เก็บข้อมูลบนเครื่องไม่เพียงพอ (ต้องการ ~${neededBytes / 1024 / 1024} MB, คงเหลือ ${availableBytes / 1024 / 1024} MB)"
            )
        }

        // Update privateFilePath references inside staged SQLite DB so paths are portable across devices
        if (imagePathRemap.isNotEmpty()) {
            val stagedDb = SQLiteDatabase.openDatabase(
                stagedDbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            )
            try {
                stagedDb.beginTransaction()
                try {
                    for ((imageId, newLivePath) in imagePathRemap) {
                        stagedDb.execSQL(
                            "UPDATE imported_images SET privateFilePath = ? WHERE id = ?",
                            arrayOf(newLivePath, imageId)
                        )
                        stagedDb.execSQL(
                            "UPDATE attachments SET localFilePath = ? WHERE importedImageId = ?",
                            arrayOf(newLivePath, imageId)
                        )
                    }
                    // Device-specific SAF URI permissions are NOT portable across phones
                    stagedDb.execSQL(
                        "UPDATE scan_sources SET permissionValid = 0, lastStatus = 'กู้คืนข้อมูลแล้ว — โปรดเลือกโฟลเดอร์ K PLUS ใหม่'"
                    )
                    stagedDb.setTransactionSuccessful()
                } finally {
                    stagedDb.endTransaction()
                }
            } finally {
                stagedDb.close()
            }
        } else {
            val stagedDb = SQLiteDatabase.openDatabase(
                stagedDbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            )
            try {
                stagedDb.execSQL(
                    "UPDATE scan_sources SET permissionValid = 0, lastStatus = 'กู้คืนข้อมูลแล้ว — โปรดเลือกโฟลเดอร์ K PLUS ใหม่'"
                )
            } finally {
                stagedDb.close()
            }
        }

        val liveDbFile = context.getDatabasePath(AppDatabase.DB_NAME)
        val liveWal = File("${liveDbFile.absolutePath}-wal")
        val liveShm = File("${liveDbFile.absolutePath}-shm")
        val liveVault = vaultImagesDir()

        val rollbackDir = File(context.cacheDir, "restore_rollback_${UUID.randomUUID()}").apply { mkdirs() }
        val rollbackDb = File(rollbackDir, "rollback.db")
        val rollbackVault = File(rollbackDir, "vault_images").apply { mkdirs() }

        // Save rollback copy of current live state before touching anything
        AppDatabase.closeAndClearInstance()
        if (liveDbFile.exists()) liveDbFile.copyTo(rollbackDb, overwrite = true)
        if (liveVault.exists()) liveVault.copyRecursively(rollbackVault, overwrite = true)

        try {
            liveDbFile.parentFile?.mkdirs()
            liveWal.delete()
            liveShm.delete()
            stagedDbFile.copyTo(liveDbFile, overwrite = true)

            liveVault.deleteRecursively()
            liveVault.mkdirs()
            if (stagedImagesDir.exists()) {
                stagedImagesDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        file.copyTo(File(liveVault, file.name), overwrite = true)
                    }
                }
            }

            // Verify reopened Room database succeeds
            val reopened = AppDatabase.getInstance(context)
            reopened.openHelper.readableDatabase.query("PRAGMA integrity_check").use { c ->
                if (!c.moveToFirst() || c.getString(0).lowercase() != "ok") {
                    throw BackupManagerException("ฐานข้อมูลหลังการกู้คืนไม่ผ่านการตรวจสอบ")
                }
            }
        } catch (t: Throwable) {
            // Roll back safely to preserve original state untouched
            AppDatabase.closeAndClearInstance()
            liveWal.delete()
            liveShm.delete()
            if (rollbackDb.exists()) rollbackDb.copyTo(liveDbFile, overwrite = true)
            liveVault.deleteRecursively()
            liveVault.mkdirs()
            rollbackVault.copyRecursively(liveVault, overwrite = true)
            AppDatabase.getInstance(context)
            throw BackupManagerException("กู้คืนข้อมูลล้มเหลว ระบบได้ย้อนกลับข้อมูลเดิมอย่างปลอดภัย: ${t.message}", t)
        } finally {
            rollbackDir.deleteRecursively()
        }
    }

    private fun createConsistentDbSnapshot(db: AppDatabase, destination: File): File {
        val writable = db.openHelper.writableDatabase
        writable.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val liveDbFile = context.getDatabasePath(AppDatabase.DB_NAME)
        if (!liveDbFile.exists()) {
            throw BackupManagerException("ไม่พบไฟล์ฐานข้อมูลต้นทาง")
        }
        liveDbFile.copyTo(destination, overwrite = true)
        return destination
    }

    private fun verifySqliteIntegrity(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getString(0).lowercase() != "ok") {
                throw BackupManagerException("ฐานข้อมูลไม่ผ่านการตรวจสอบ PRAGMA integrity_check")
            }
        }
    }

    private fun tableExists(db: SQLiteDatabase, tableName: String): Boolean {
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(tableName)
        ).use { c ->
            return c.moveToFirst()
        }
    }

    private fun availableInternalStorageBytes(): Long {
        val stat = StatFs(context.filesDir.absolutePath)
        val statBytes = stat.availableBlocksLong * stat.blockSizeLong
        return if (statBytes > 0L) statBytes else context.filesDir.usableSpace
    }

    private fun directorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
