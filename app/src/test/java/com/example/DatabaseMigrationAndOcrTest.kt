package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AccountEntity
import com.example.data.local.AccountType
import com.example.data.local.DatabaseMigrations
import com.example.data.local.DateDisplayFormat
import com.example.data.local.OcrModelStatus
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import com.example.domain.OcrTextLine
import com.example.domain.PaddleOcrEngine
import com.example.domain.ReportExporter
import com.example.domain.ThaiBankSlipParser
import com.example.domain.ThaiDateFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabaseMigrationAndOcrTest {

    @Test
    fun `paddle ocr engine bundles pinned pp-ocrv5 onnx models with verified sha256 and thai ctc dict`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = PaddleOcrEngine(context)
        val diag = engine.inspectDiagnostics()

        assertEquals(OcrModelStatus.READY, diag.status)
        assertEquals(PaddleOcrEngine.PINNED_MODEL_VERSION, diag.pinnedVersion)
        assertTrue(diag.hasDetectionModel)
        assertTrue(diag.detectionSha256Verified)
        assertEquals(PaddleOcrEngine.DET_MODEL_BYTES, diag.detectionSizeBytes)
        assertTrue(diag.hasRecognitionModel)
        assertTrue(diag.recognitionSha256Verified)
        assertEquals(PaddleOcrEngine.REC_MODEL_BYTES, diag.recognitionSizeBytes)
        assertTrue(diag.hasDictionary)
        assertTrue(diag.dictionarySha256Verified)
        assertTrue(diag.allIntegrityVerified)

        val dict = engine.loadDictionary()
        // 524 official characters + 1 appended space character (' ') = 525 non-blank CTC symbols (+ blank index 0 = 526 classes)
        assertEquals(PaddleOcrEngine.DICT_CHAR_COUNT + 1, dict.size)
        assertEquals(" ", dict.last())
        assertTrue(dict.contains("ก"))
        assertTrue(dict.contains("฿"))
    }

    @Test
    fun `slip parser supports gregorian CE slash dates and PromptPay slips`() {
        val lines = listOf(
            OcrTextLine("PromptPay", 0.96f),
            OcrTextLine("15/08/2026 09:12", 0.94f),
            OcrTextLine("จาก: บจก. เอ บี ซี", 0.91f),
            OcrTextLine("ไปยัง: คุณสมชาย ใจดี", 0.93f),
            OcrTextLine("25,000.00 THB", 0.99f),
            OcrTextLine("Ref: PP20260815998877", 0.90f)
        )
        val parsed = ThaiBankSlipParser.parse(lines)
        assertEquals("PromptPay", parsed.detectedBank)
        assertEquals(2_500_000L, parsed.amountSatang)
        assertEquals("09:12", parsed.timeText)
        assertEquals("PP20260815998877", parsed.paymentReference)
        assertEquals(LocalDate.of(2026, 8, 15), ThaiDateFormatter.toBangkokDate(parsed.dateEpochMillis!!))
    }

    @Test
    fun `csv exporter writes utf8 bom and thai columns cleanly`() {
        val out = ByteArrayOutputStream()
        val acc = AccountEntity(id = "a1", name = "KBank", accountType = AccountType.KBANK)
        val tx = TransactionEntity(
            id = "t1",
            amountSatang = 123_450L,
            type = TransactionType.EXPENSE,
            timestampEpochMillis = 1_700_000_000_000L,
            description = "ค่าอาหารกลางวัน",
            accountId = "a1"
        )
        ReportExporter.writeTransactionsCsv(
            outputStream = out,
            transactions = listOf(tx),
            accounts = mapOf("a1" to acc),
            categories = emptyMap(),
            dateFormat = DateDisplayFormat.THAI_BE
        )
        val csv = out.toString("UTF-8")
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("ค่าอาหารกลางวัน"))
        assertTrue(csv.contains("1234.50"))
    }

    @Test
    fun `database migration 1_2 creates taxSection index safely`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.cacheDir, "mig_test.db").apply { delete() }
        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqlite.execSQL("CREATE TABLE transactions (id TEXT PRIMARY KEY, taxSection TEXT NOT NULL)")
        sqlite.close()

        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbFile.absolutePath)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val supportDb = helper.writableDatabase
        DatabaseMigrations.MIGRATION_1_2.migrate(supportDb)
        supportDb.query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_transactions_taxSection'").use { c ->
            assertTrue(c.moveToFirst())
        }
        helper.close()
        dbFile.delete()
    }
}
