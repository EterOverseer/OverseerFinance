package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.ImageImportState
import com.example.data.local.ImportedImageEntity
import com.example.data.local.ScanSourceEntity
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import com.example.data.repository.FinanceRepository
import com.example.domain.BackupCenterManager
import com.example.domain.BackupCrypto
import com.example.domain.BackupWrongPasswordOrCorruptException
import com.example.domain.ImageHashing
import com.example.worker.CleanerDeleteResult
import com.example.worker.KPlusScannerPipeline
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupAndPipelineRobolectricTest {

    private lateinit var context: Context
    private lateinit var repo: FinanceRepository
    private lateinit var backupManager: BackupCenterManager
    private lateinit var pipeline: KPlusScannerPipeline

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        AppDatabase.closeAndClearInstance()
        context.getDatabasePath(AppDatabase.DB_NAME).delete()
        File(context.filesDir, "vault_images").deleteRecursively()
        repo = FinanceRepository(context)
        repo.ensureInitialized()
        backupManager = BackupCenterManager(context)
        pipeline = KPlusScannerPipeline(context)
    }

    @After
    fun teardown() {
        AppDatabase.closeAndClearInstance()
    }

    @Test
    fun `efbackup crypto detects wrong password and tampered ciphertext`() {
        val secret = "overseer-test-payload".toByteArray()
        val out = ByteArrayOutputStream()
        BackupCrypto.encryptToStream(secret, "StrongPass123", 1, "1.0.0", out)
        val encrypted = out.toByteArray()

        // Valid decrypt
        val (_, decrypted) = BackupCrypto.decryptFromStream(ByteArrayInputStream(encrypted), "StrongPass123")
        assertEquals("overseer-test-payload", String(decrypted))

        // Wrong password fails
        var wrongPwThrown = false
        try {
            BackupCrypto.decryptFromStream(ByteArrayInputStream(encrypted), "WrongPass999")
        } catch (_: BackupWrongPasswordOrCorruptException) {
            wrongPwThrown = true
        }
        assertTrue(wrongPwThrown)

        // Tampered byte fails GCM tag
        val tampered = encrypted.copyOf()
        tampered[tampered.size - 5] = (tampered[tampered.size - 5].toInt() xor 0xFF).toByte()
        var tamperedThrown = false
        try {
            BackupCrypto.decryptFromStream(ByteArrayInputStream(tampered), "StrongPass123")
        } catch (_: BackupWrongPasswordOrCorruptException) {
            tamperedThrown = true
        }
        assertTrue(tamperedThrown)
    }

    @Test
    fun `full restore from efbackup replaces existing data completely and resets SAF permission`() = runBlocking {
        val db = AppDatabase.getInstance(context)
        val imgBytes = "fake-png-bytes-verified".toByteArray()
        val vaultDir = backupManager.vaultImagesDir()
        val vaultFile = File(vaultDir, "img1_slip.png").apply { writeBytes(imgBytes) }
        val sha = ImageHashing.sha256(vaultFile)

        db.importedImageDao().upsert(
            ImportedImageEntity(
                id = "img1",
                sourceUri = "content://com.android.externalstorage/slip.png",
                sourceFolderUri = "content://com.android.externalstorage/kplus",
                originalFileName = "slip.png",
                mimeType = "image/png",
                fileSizeBytes = imgBytes.size.toLong(),
                sourceLastModifiedEpochMillis = 1000L,
                sha256 = sha,
                privateFilePath = vaultFile.absolutePath,
                importState = ImageImportState.IMPORTED_VERIFIED
            )
        )
        db.scanSourceDao().upsert(
            ScanSourceEntity(
                treeUri = "content://old-device/kplus",
                displayName = "K Plus",
                permissionValid = true
            )
        )
        db.transactionDao().upsert(
            TransactionEntity(
                id = "tx_before_backup",
                amountSatang = 99_900L,
                type = TransactionType.INCOME,
                timestampEpochMillis = 10_000L,
                description = "Original Backup Tx",
                accountId = "acc_kbank_main"
            )
        )

        val backupOut = ByteArrayOutputStream()
        val exportRes = backupManager.exportEncryptedEfBackup(backupOut, "test.efbackup", "BackupPass99")
        assertTrue(exportRes.isSuccess)
        val backupBytes = backupOut.toByteArray()

        // Mutate live DB after backup: add extra tx and delete original tx
        db.transactionDao().upsert(
            TransactionEntity(
                id = "tx_must_be_wiped",
                amountSatang = 12_300L,
                type = TransactionType.EXPENSE,
                timestampEpochMillis = 20_000L,
                description = "Post Backup Noise",
                accountId = "acc_kbank_main"
            )
        )
        db.transactionDao().deleteById("tx_before_backup")

        // Execute Full Restore
        val restoreRes = backupManager.restoreEfBackupFullReplace(ByteArrayInputStream(backupBytes), "BackupPass99")
        assertTrue(restoreRes.isSuccess)

        val restoredDb = AppDatabase.getInstance(context)
        val restoredTxs = restoredDb.transactionDao().getAllOnce()
        assertEquals(1, restoredTxs.size)
        assertEquals("tx_before_backup", restoredTxs.first().id)

        // Device-specific SAF permission must be marked invalid after restore
        val restoredSource = restoredDb.scanSourceDao().getPrimaryOnce()
        assertFalse(restoredSource?.permissionValid ?: true)

        // Private image restored & verified
        val restoredImg = restoredDb.importedImageDao().getById("img1")!!
        assertTrue(File(restoredImg.privateFilePath).exists())
        assertEquals(sha, ImageHashing.sha256(File(restoredImg.privateFilePath)))
    }

    @Test
    fun `portable sqlite export embeds image blobs and restores cleanly`() = runBlocking {
        val db = AppDatabase.getInstance(context)
        val imgBytes = "sqlite-blob-image-content".toByteArray()
        val vaultFile = File(backupManager.vaultImagesDir(), "img_sq.jpg").apply { writeBytes(imgBytes) }
        val sha = ImageHashing.sha256(vaultFile)

        db.importedImageDao().upsert(
            ImportedImageEntity(
                id = "img_sq",
                sourceUri = "content://test/sq.jpg",
                sourceFolderUri = "content://test",
                originalFileName = "sq.jpg",
                mimeType = "image/jpeg",
                fileSizeBytes = imgBytes.size.toLong(),
                sourceLastModifiedEpochMillis = 1000L,
                sha256 = sha,
                privateFilePath = vaultFile.absolutePath,
                importState = ImageImportState.IMPORTED_VERIFIED
            )
        )
        db.transactionDao().upsert(
            TransactionEntity(
                id = "tx_sq_1",
                amountSatang = 45_000L,
                type = TransactionType.EXPENSE,
                timestampEpochMillis = 5000L,
                description = "SQLite Tx",
                accountId = "acc_kbank_main"
            )
        )

        val sqliteOut = ByteArrayOutputStream()
        val exportRes = backupManager.exportPortableSqlite(sqliteOut, "portable.sqlite")
        assertTrue(exportRes.isSuccess)
        val sqliteBytes = sqliteOut.toByteArray()

        // Wipe and verify restore brings back both tx and image BLOB
        db.transactionDao().upsert(
            TransactionEntity(
                id = "tx_noise",
                amountSatang = 1_000L,
                type = TransactionType.EXPENSE,
                timestampEpochMillis = 6000L,
                description = "Noise",
                accountId = "acc_kbank_main"
            )
        )

        val restoreRes = backupManager.restoreSqliteFullReplace(ByteArrayInputStream(sqliteBytes))
        assertTrue(restoreRes.isSuccess)

        val restoredDb = AppDatabase.getInstance(context)
        val txs = restoredDb.transactionDao().getAllOnce()
        assertEquals(1, txs.size)
        assertEquals("tx_sq_1", txs.first().id)
        val img = restoredDb.importedImageDao().getById("img_sq")!!
        assertEquals(sha, ImageHashing.sha256(File(img.privateFilePath)))
    }

    @Test
    fun `corrupted backup restore fails safely and leaves existing data untouched`() = runBlocking {
        val db = AppDatabase.getInstance(context)
        db.transactionDao().upsert(
            TransactionEntity(
                id = "tx_live_safe",
                amountSatang = 77_700L,
                type = TransactionType.INCOME,
                timestampEpochMillis = 9000L,
                description = "Must Survive",
                accountId = "acc_kbank_main"
            )
        )

        val corruptRes = backupManager.restoreEfBackupFullReplace(
            ByteArrayInputStream("NOT_A_VALID_EFBACKUP".toByteArray()),
            "AnyPassword"
        )
        assertTrue(corruptRes.isFailure)

        val txs = AppDatabase.getInstance(context).transactionDao().getAllOnce()
        assertEquals(1, txs.size)
        assertEquals("tx_live_safe", txs.first().id)
    }

    @Test
    fun `storage cleaner rejects pending, failed, and unverified private copies`() = runBlocking {
        val pendingImg = ImportedImageEntity(
            id = "p1",
            sourceUri = "content://kplus/p1.jpg",
            sourceFolderUri = "content://kplus",
            originalFileName = "p1.jpg",
            mimeType = "image/jpeg",
            fileSizeBytes = 100L,
            sourceLastModifiedEpochMillis = 1L,
            sha256 = "abc",
            privateFilePath = "",
            importState = ImageImportState.PENDING
        )
        val failedImg = pendingImg.copy(id = "f1", importState = ImageImportState.FAILED)
        val missingCopyImg = pendingImg.copy(
            id = "m1",
            importState = ImageImportState.IMPORTED_VERIFIED,
            privateFilePath = "/nonexistent/path/m1.jpg"
        )

        assertTrue(pipeline.deleteOriginalSafImageSafely(pendingImg) is CleanerDeleteResult.RejectedSafety)
        assertTrue(pipeline.deleteOriginalSafImageSafely(failedImg) is CleanerDeleteResult.RejectedSafety)
        assertTrue(pipeline.deleteOriginalSafImageSafely(missingCopyImg) is CleanerDeleteResult.RejectedSafety)
    }
}
