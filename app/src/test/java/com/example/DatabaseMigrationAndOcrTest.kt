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
    fun `paddle ocr engine honestly reports missing onnx model assets without fake output`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = PaddleOcrEngine(context)
        assertEquals(OcrModelStatus.MISSING_MODEL_ASSETS, engine.inspectModelAvailability())

        val dummyFile = File(context.cacheDir, "dummy_slip.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val outcome = engine.recognizeSlip(dummyFile)
        assertEquals(OcrModelStatus.MISSING_MODEL_ASSETS, outcome.modelStatus)
        assertTrue(outcome.lines.isEmpty())
        assertTrue(outcome.parsedSlip.needsReview)
        assertEquals(null, outcome.parsedSlip.amountSatang)
        dummyFile.delete()
    }

    @Test
    fun `paddle ocr engine installs updates and resets local onnx models with diagnostics`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = PaddleOcrEngine(context)
        engine.resetInstalledModels()
        assertEquals(OcrModelStatus.MISSING_MODEL_ASSETS, engine.inspectModelAvailability())

        // 1) Undersized or explicitly invalid ONNX files must be rejected cleanly
        val smallBytes = ByteArray(128) { 1 }
        val rejectedSmall = engine.installOrUpdateModelFiles(
            listOf("PP-OCRv5_mobile_det.onnx" to smallBytes.inputStream())
        )
        assertEquals(0, rejectedSmall.installedCount)
        assertEquals(OcrModelStatus.MISSING_MODEL_ASSETS, rejectedSmall.diagnostics.status)

        val validSizeBytes = ByteArray(2048) { (it % 127).toByte() }
        val rejectedInvalidOnnx = engine.installOrUpdateModelFiles(
            entries = listOf("PP-OCRv5_mobile_det.onnx" to validSizeBytes.inputStream()),
            validateOnnx = { throw IllegalArgumentException("Invalid ONNX protobuf") }
        )
        assertEquals(0, rejectedInvalidOnnx.installedCount)
        assertEquals(OcrModelStatus.MISSING_MODEL_ASSETS, rejectedInvalidOnnx.diagnostics.status)

        // 2) Installing Det + Rec model files transitions status to READY and reset cleans them
        val res = engine.installOrUpdateModelFiles(
            entries = listOf(
                "PP-OCRv5_mobile_det.onnx" to validSizeBytes.inputStream(),
                "th_PP-OCRv5_mobile_rec.onnx" to validSizeBytes.inputStream()
            )
        )
        assertEquals(2, res.installedCount)
        assertEquals(OcrModelStatus.READY, res.diagnostics.status)
        assertTrue(res.diagnostics.hasUserInstalledFiles)
        assertTrue(res.diagnostics.hasDetectionModel)
        assertTrue(res.diagnostics.hasRecognitionModel)
        assertTrue(res.diagnostics.hasDictionary)

        val resetDiag = engine.resetInstalledModels()
        assertEquals(OcrModelStatus.MISSING_MODEL_ASSETS, resetDiag.status)
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
