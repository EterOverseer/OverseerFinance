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
    fun `dbnet connected components separate side icons or columns and preserve Thai unclip headroom`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = PaddleOcrEngine(context)

        // 100x60 probability map representing a 1000x600 slip (10x scale)
        val mapW = 100
        val mapH = 60
        val prob = Array(mapH) { FloatArray(mapW) { 0f } }

        // Row band y=10..15: left column label (x=5..25) & far-right amount (x=65..92) -> MUST stay 2 separate boxes
        for (y in 10..14) {
            for (x in 5..24) prob[y][x] = 0.88f
            for (x in 65..92) prob[y][x] = 0.92f
        }
        // Row band y=30..35: two words in a Thai name separated by small space (x=25..42 and x=45..68) -> MUST merge into 1 line
        for (y in 30..34) {
            for (x in 25..42) prob[y][x] = 0.90f
            for (x in 45..68) prob[y][x] = 0.90f
        }

        val boxes = engine.extractTextBoxesFromProbMap(prob, mapW, mapH, 1000, 600)
        assertEquals(3, boxes.size)

        // Reading order: row 1 left ("จำนวน"), row 1 right ("1,500.00 บาท"), row 2 merged ("นาย สมชาย ใจดี")
        assertTrue(boxes[0].left < boxes[1].left)
        assertTrue(boxes[1].left > 550)
        // Row 2 merged width covers x=250..690
        assertTrue(boxes[2].left <= 250 && boxes[2].right >= 680)
        // Vertical unclip must expand generously above y=300 (rawTop=300) for Thai tone marks
        assertTrue(boxes[2].top < 275)
        assertTrue(boxes[2].bottom > 370)
    }

    @Test
    fun `slip parser ignores fee amount on preceding label line and picks true transfer amount`() {
        val lines = listOf(
            OcrTextLine("K PLUS", 0.96f),
            OcrTextLine("14 ส.ค. 69 09:30 น.", 0.92f),
            OcrTextLine("จาก", 0.90f),
            OcrTextLine("นาย สมชาย ใจดี", 0.93f),
            OcrTextLine("ไปยัง", 0.91f),
            OcrTextLine("บจก. ทดสอบ ไทย", 0.94f),
            OcrTextLine("จำนวน:", 0.91f),
            OcrTextLine("1,500.00 บาท", 0.98f),
            OcrTextLine("ค่าธรรมเนียม", 0.92f),
            OcrTextLine("0.00 บาท", 0.99f)
        )
        val parsed = ThaiBankSlipParser.parse(lines)
        assertEquals("KBank (K PLUS)", parsed.detectedBank)
        assertEquals(150_000L, parsed.amountSatang)
        assertEquals("นาย สมชาย ใจดี", parsed.senderName)
        assertEquals("บจก. ทดสอบ ไทย", parsed.recipientName)
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
