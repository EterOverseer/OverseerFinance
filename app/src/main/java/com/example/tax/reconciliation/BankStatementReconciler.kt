package com.example.tax.reconciliation

import com.example.data.local.AppDatabase
import com.example.data.local.ReviewStatus
import com.example.data.local.StatementImportBatchEntity
import com.example.data.local.TaxSection
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionSource
import com.example.data.local.TransactionType
import com.example.domain.ImageHashing
import com.example.domain.MoneyFormatter
import com.example.domain.ThaiDateFormatter
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class BankMappingPreset(val labelTh: String) {
    KBANK_CSV("กสิกรไทย (KBank Statement CSV)"),
    SCB_CSV("ไทยพาณิชย์ (SCB Easy CSV)"),
    KTB_CSV("กรุงไทย (Krungthai NEXT CSV)"),
    BBL_CSV("กรุงเทพ (Bualuang CSV)"),
    CUSTOM("กำหนดคอลัมน์เอง (Custom CSV)")
}

enum class CsvCharsetOption(val labelTh: String, val charsetName: String) {
    UTF_8("UTF-8", "UTF-8"),
    TIS_620("TIS-620 / Windows-874 (ไทยดั้งเดิม)", "windows-874")
}

data class BankCsvColumnMapping(
    val preset: BankMappingPreset = BankMappingPreset.KBANK_CSV,
    val bankName: String = "KBank",
    val dateColumn: Int = 0,
    val descriptionColumn: Int = 1,
    val debitColumn: Int? = 2,      // Withdrawal / Expense
    val creditColumn: Int? = 3,     // Deposit / Inflow
    val signedAmountColumn: Int? = null,
    val referenceColumn: Int? = 4,
    val hasHeaderRow: Boolean = true,
    val charset: CsvCharsetOption = CsvCharsetOption.UTF_8
) {
    companion object {
        fun forPreset(preset: BankMappingPreset): BankCsvColumnMapping = when (preset) {
            BankMappingPreset.KBANK_CSV -> BankCsvColumnMapping(
                preset = preset,
                bankName = "KBank",
                dateColumn = 0,
                descriptionColumn = 1,
                debitColumn = 2,
                creditColumn = 3,
                referenceColumn = 4
            )
            BankMappingPreset.SCB_CSV -> BankCsvColumnMapping(
                preset = preset,
                bankName = "SCB",
                dateColumn = 0,
                descriptionColumn = 3,
                debitColumn = 1,
                creditColumn = 2,
                referenceColumn = 4
            )
            BankMappingPreset.KTB_CSV -> BankCsvColumnMapping(
                preset = preset,
                bankName = "Krungthai",
                dateColumn = 0,
                descriptionColumn = 1,
                debitColumn = 2,
                creditColumn = 3,
                referenceColumn = 4
            )
            BankMappingPreset.BBL_CSV -> BankCsvColumnMapping(
                preset = preset,
                bankName = "Bangkok Bank",
                dateColumn = 0,
                descriptionColumn = 1,
                debitColumn = 2,
                creditColumn = 3,
                referenceColumn = 4
            )
            BankMappingPreset.CUSTOM -> BankCsvColumnMapping(
                preset = preset,
                bankName = "Custom Bank"
            )
        }
    }
}

enum class ReconciliationBucket(val labelTh: String) {
    TAXABLE_INCOME("เงินได้พึงประเมิน (ยืนยันแล้ว)"),
    NON_TAXABLE_INCOME("รายรับยกเว้นภาษี"),
    INTERNAL_TRANSFER("โอนระหว่างบัญชีตนเอง"),
    LOANS("เงินกู้ยืม / ชำระคืนหนี้"),
    REFUNDS("เงินคืน / รีฟันด์"),
    REIMBURSEMENTS("รับชำระคืน / เงินทดรองจ่ายคืน"),
    PERSONAL_EXPENSES("ค่าใช้จ่ายส่วนตัว"),
    BUSINESS_EXPENSES("ค่าใช้จ่ายธุรกิจ / ต้นทุน"),
    REQUIRES_REVIEW("อื่นๆ / รอผู้ใช้ตรวจสอบ")
}

data class ParsedStatementRow(
    val rowNumber: Int,
    val timestampEpochMillis: Long,
    val amountSatang: Long,
    val isInflow: Boolean,
    val description: String,
    val reference: String
)

data class StatementImportOutcome(
    val batch: StatementImportBatchEntity?,
    val alreadyImportedFile: Boolean,
    val parsedRowCount: Int,
    val importedCount: Int,
    val duplicateSkippedCount: Int,
    val invalidRowCount: Int,
    val messageTh: String
)

object BankStatementReconciler {

    private val bangkokZone = ZoneId.of("Asia/Bangkok")

    fun classifyBucket(tx: TransactionEntity): ReconciliationBucket {
        if (tx.reviewStatus == ReviewStatus.NEEDS_REVIEW || tx.reviewStatus == ReviewStatus.DRAFT) {
            return ReconciliationBucket.REQUIRES_REVIEW
        }
        return when (tx.type) {
            TransactionType.INTERNAL_TRANSFER -> ReconciliationBucket.INTERNAL_TRANSFER
            TransactionType.LOAN_RECEIVED, TransactionType.LOAN_REPAYMENT -> ReconciliationBucket.LOANS
            TransactionType.REFUND -> ReconciliationBucket.REFUNDS
            TransactionType.DEBT_COLLECTION -> ReconciliationBucket.REIMBURSEMENTS
            TransactionType.EXPENSE -> {
                if (tx.isBusiness) ReconciliationBucket.BUSINESS_EXPENSES
                else ReconciliationBucket.PERSONAL_EXPENSES
            }
            TransactionType.INCOME -> when {
                tx.taxSection == TaxSection.NON_TAXABLE -> ReconciliationBucket.NON_TAXABLE_INCOME
                tx.taxSection.isTaxableIncome -> ReconciliationBucket.TAXABLE_INCOME
                else -> ReconciliationBucket.REQUIRES_REVIEW
            }
            TransactionType.OTHER -> ReconciliationBucket.REQUIRES_REVIEW
        }
    }

    fun summarizeYearBuckets(
        transactions: List<TransactionEntity>,
        taxYearBe: Int
    ): Map<ReconciliationBucket, Pair<Int, Long>> {
        val yearCe = taxYearBe - 543
        val yearTxs = transactions.filter {
            ThaiDateFormatter.toBangkokDate(it.timestampEpochMillis).year == yearCe
        }
        val grouped = yearTxs.groupBy { classifyBucket(it) }
        return ReconciliationBucket.entries.associateWith { bucket ->
            val list = grouped[bucket].orEmpty()
            list.size to list.sumOf { it.amountSatang }
        }
    }

    fun parseCsvBytes(
        rawBytes: ByteArray,
        mapping: BankCsvColumnMapping
    ): Pair<List<ParsedStatementRow>, Int> {
        val charset = runCatching { Charset.forName(mapping.charset.charsetName) }
            .getOrDefault(Charsets.UTF_8)
        var text = String(rawBytes, charset)
        if (text.startsWith("\uFEFF")) {
            text = text.removePrefix("\uFEFF")
        }

        val rawLines = text.lineSequence().filter { it.isNotBlank() }.toList()
        val startIndex = if (mapping.hasHeaderRow && rawLines.isNotEmpty()) 1 else 0

        val parsed = mutableListOf<ParsedStatementRow>()
        var invalidCount = 0

        for (i in startIndex until rawLines.size) {
            val cols = splitCsvRow(rawLines[i])
            val dateText = cols.getOrNull(mapping.dateColumn)?.trim().orEmpty()
            val descText = cols.getOrNull(mapping.descriptionColumn)?.trim().orEmpty()
            val refText = mapping.referenceColumn?.let { cols.getOrNull(it)?.trim().orEmpty() }.orEmpty()

            val timestamp = parseStatementDateMillis(dateText)
            if (timestamp == null) {
                invalidCount++
                continue
            }

            val (amountSatang, isInflow) = resolveRowAmount(cols, mapping) ?: run {
                invalidCount++
                continue
            }
            if (amountSatang <= 0L) {
                invalidCount++
                continue
            }

            parsed.add(
                ParsedStatementRow(
                    rowNumber = i + 1,
                    timestampEpochMillis = timestamp,
                    amountSatang = amountSatang,
                    isInflow = isInflow,
                    description = descText.ifBlank { "รายการเดินบัญชี (${mapping.bankName})" },
                    reference = refText
                )
            )
        }
        return parsed to invalidCount
    }

    suspend fun importCsvIntoDatabase(
        db: AppDatabase,
        rawBytes: ByteArray,
        fileName: String,
        accountId: String,
        mapping: BankCsvColumnMapping
    ): StatementImportOutcome {
        val fileSha = ImageHashing.sha256(rawBytes)
        val existingBatch = db.taxFilingDao().findBatchBySha256(fileSha)
        if (existingBatch != null) {
            return StatementImportOutcome(
                batch = existingBatch,
                alreadyImportedFile = true,
                parsedRowCount = existingBatch.rowCount,
                importedCount = 0,
                duplicateSkippedCount = existingBatch.rowCount,
                invalidRowCount = 0,
                messageTh = "ไฟล์รายการเดินบัญชีนี้ถูกนำเข้าแล้วก่อนหน้า (${existingBatch.fileName})"
            )
        }

        val (rows, invalidCount) = parseCsvBytes(rawBytes, mapping)
        val existingTxs = db.transactionDao().getAllOnce()
        val seenKeys = existingTxs.mapTo(mutableSetOf()) { buildMatchKey(it) }

        var imported = 0
        var duplicates = 0
        val yearsTouched = mutableSetOf<Int>()

        for (row in rows) {
            if (isDuplicateOfExisting(row, existingTxs, seenKeys)) {
                duplicates++
                continue
            }

            // Never assume a bank deposit is taxable income!
            // Both inflows and outflows from CSV require user verification & classification.
            val initialType = if (row.isInflow) TransactionType.OTHER else TransactionType.EXPENSE
            val tx = TransactionEntity(
                amountSatang = row.amountSatang,
                type = initialType,
                timestampEpochMillis = row.timestampEpochMillis,
                description = row.description,
                accountId = accountId,
                paymentReference = row.reference,
                notes = "นำเข้า CSV (${mapping.bankName}: $fileName) — กรุณายืนยันประเภทภาษีด้วยตนเอง",
                source = TransactionSource.BANK_STATEMENT,
                taxSection = TaxSection.NONE,
                reviewStatus = ReviewStatus.NEEDS_REVIEW
            )
            db.transactionDao().upsert(tx)
            existingTxs.toMutableList()
            seenKeys.add(buildMatchKey(tx))
            yearsTouched.add(ThaiDateFormatter.toBangkokDate(row.timestampEpochMillis).year + 543)
            imported++
        }

        val batch = StatementImportBatchEntity(
            bankName = mapping.bankName,
            fileName = fileName,
            sha256 = fileSha,
            rowCount = rows.size,
            importedCount = imported,
            duplicateCount = duplicates
        )
        db.taxFilingDao().upsertImportBatch(batch)
        yearsTouched.forEach { db.taxFilingDao().markActiveXmlOutdated(it) }

        return StatementImportOutcome(
            batch = batch,
            alreadyImportedFile = false,
            parsedRowCount = rows.size,
            importedCount = imported,
            duplicateSkippedCount = duplicates,
            invalidRowCount = invalidCount,
            messageTh = "นำเข้า $imported รายการ • ข้ามรายการซ้ำ $duplicates รายการ (รอตรวจสอบก่อนคำนวณภาษี)"
        )
    }

    fun isDuplicateOfExisting(
        row: ParsedStatementRow,
        existingTxs: List<TransactionEntity>,
        seenKeys: Set<String>
    ): Boolean {
        val rowDate = ThaiDateFormatter.toBangkokDate(row.timestampEpochMillis)
        val candidateKey = "${rowDate}_${row.amountSatang}_${row.reference.lowercase(Locale.ROOT).trim()}"
        if (row.reference.isNotBlank() && seenKeys.contains(candidateKey)) return true

        return existingTxs.any { existing ->
            val exDate = ThaiDateFormatter.toBangkokDate(existing.timestampEpochMillis)
            val sameDayOrAdjacent = kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(exDate, rowDate)) <= 1L
            val exactSameDay = exDate == rowDate
            val sameAmount = existing.amountSatang == row.amountSatang
            val refMatch = row.reference.isNotBlank() &&
                existing.paymentReference.isNotBlank() &&
                row.reference.equals(existing.paymentReference, ignoreCase = true)
            val descSimilar = existing.description.trim().equals(row.description.trim(), ignoreCase = true) ||
                (existing.recipientName.isNotBlank() && row.description.contains(existing.recipientName, ignoreCase = true)) ||
                (existing.senderName.isNotBlank() && row.description.contains(existing.senderName, ignoreCase = true))

            when {
                sameAmount && refMatch && sameDayOrAdjacent -> true
                sameAmount && exactSameDay && descSimilar -> true
                // Also prevent exact same-day same-amount CSV duplicate if imported already as same direction
                sameAmount && exactSameDay && existing.source == TransactionSource.K_PLUS_OCR &&
                    (existing.type.isCashInflow == row.isInflow || existing.type == TransactionType.EXPENSE && !row.isInflow) ->
                    descSimilar || row.reference.isNotBlank() && existing.paymentReference == row.reference
                else -> false
            }
        }
    }

    private fun buildMatchKey(tx: TransactionEntity): String {
        val d = ThaiDateFormatter.toBangkokDate(tx.timestampEpochMillis)
        return "${d}_${tx.amountSatang}_${tx.paymentReference.lowercase(Locale.ROOT).trim()}"
    }

    private fun resolveRowAmount(cols: List<String>, mapping: BankCsvColumnMapping): Pair<Long, Boolean>? {
        val signedIdx = mapping.signedAmountColumn
        if (signedIdx != null) {
            val raw = cols.getOrNull(signedIdx)?.trim().orEmpty()
            if (raw.isBlank()) return null
            val cleaned = raw.replace(",", "").replace("฿", "").replace("THB", "", true).trim()
            val isNegative = cleaned.startsWith("-") || (cleaned.startsWith("(") && cleaned.endsWith(")"))
            val unsigned = cleaned.removePrefix("-").removePrefix("+").removeSurrounding("(", ")")
            val satang = MoneyFormatter.parseThbToSatang(unsigned) ?: return null
            return satang to !isNegative
        }

        val creditRaw = mapping.creditColumn?.let { cols.getOrNull(it)?.trim().orEmpty() }.orEmpty()
        val debitRaw = mapping.debitColumn?.let { cols.getOrNull(it)?.trim().orEmpty() }.orEmpty()
        val creditSatang = MoneyFormatter.parseThbToSatang(cleanNumericCell(creditRaw)) ?: 0L
        val debitSatang = MoneyFormatter.parseThbToSatang(cleanNumericCell(debitRaw)) ?: 0L
        return when {
            creditSatang > 0L -> creditSatang to true
            debitSatang > 0L -> debitSatang to false
            else -> null
        }
    }

    private fun cleanNumericCell(raw: String): String =
        raw.replace(",", "").replace("฿", "").replace("THB", "", true).trim()

    private fun parseStatementDateMillis(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        val datePatterns = listOf(
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "dd-MM-yyyy HH:mm",
            "dd/MM/yyyy",
            "yyyy-MM-dd",
            "dd-MM-yyyy"
        )
        for (pattern in datePatterns) {
            val fmt = DateTimeFormatter.ofPattern(pattern, Locale.US)
            val parsedDt = runCatching { LocalDateTime.parse(trimmed, fmt) }.getOrNull()
            if (parsedDt != null) {
                val fixedYear = if (parsedDt.year >= 2400) parsedDt.year - 543 else parsedDt.year
                return parsedDt.withYear(fixedYear).atZone(bangkokZone).toInstant().toEpochMilli()
            }
            val parsedDate = runCatching { LocalDate.parse(trimmed, fmt) }.getOrNull()
            if (parsedDate != null) {
                val fixedYear = if (parsedDate.year >= 2400) parsedDate.year - 543 else parsedDate.year
                return parsedDate.withYear(fixedYear).atTime(LocalTime.NOON).atZone(bangkokZone).toInstant().toEpochMilli()
            }
        }
        return null
    }

    fun splitCsvRow(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        result.add(current.toString())
        return result
    }
}
