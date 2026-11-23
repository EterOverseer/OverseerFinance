package com.example.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

data class OcrTextLine(
    val text: String,
    val confidence: Float
)

data class ParsedBankSlip(
    val detectedBank: String,
    val amountSatang: Long?,
    val amountConfidence: Float,
    val dateEpochMillis: Long?,
    val dateConfidence: Float,
    val timeText: String,
    val timeConfidence: Float,
    val senderName: String,
    val senderConfidence: Float,
    val recipientName: String,
    val recipientConfidence: Float,
    val paymentReference: String,
    val referenceConfidence: Float,
    val accountHint: String,
    val overallConfidence: Float,
    val needsReview: Boolean
)

/**
 * Flexible Thai bank slip parser supporting K PLUS, SCB, KTB, BBL, Krungsri, GSB, and PromptPay.
 * Never fabricates missing fields: unknown values stay empty/null and force needsReview = true.
 */
object ThaiBankSlipParser {

    private val thaiMonthMap = mapOf(
        "ม.ค." to 1, "มกราคม" to 1, "jan" to 1,
        "ก.พ." to 2, "กุมภาพันธ์" to 2, "feb" to 2,
        "มี.ค." to 3, "มีนาคม" to 3, "mar" to 3,
        "เม.ย." to 4, "เมษายน" to 4, "apr" to 4,
        "พ.ค." to 5, "พฤษภาคม" to 5, "may" to 5,
        "มิ.ย." to 6, "มิถุนายน" to 6, "jun" to 6,
        "ก.ค." to 7, "กรกฎาคม" to 7, "jul" to 7,
        "ส.ค." to 8, "สิงหาคม" to 8, "aug" to 8,
        "ก.ย." to 9, "กันยายน" to 9, "sep" to 9,
        "ต.ค." to 10, "ตุลาคม" to 10, "oct" to 10,
        "พ.ย." to 11, "พฤศจิกายน" to 11, "nov" to 11,
        "ธ.ค." to 12, "ธันวาคม" to 12, "dec" to 12
    )

    private val amountRegex = Regex("""(\d{1,3}(?:,\d{3})*(?:\.\d{2})|\d+\.\d{2})\s*(?:บาท|THB|฿)?""", RegexOption.IGNORE_CASE)
    private val timeRegex = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)(?::([0-5]\d))?\s*(?:น\.|hrs?)?""")
    private val thaiDateRegex = Regex("""(\d{1,2})\s+(ม\.ค\.|ก\.พ\.|มี\.ค\.|เม\.ย\.|พ\.ค\.|มิ\.ย\.|ก\.ค\.|ส\.ค\.|ก\.ย\.|ต\.ค\.|พ\.ย\.|ธ\.ค\.|มกราคม|กุมภาพันธ์|มีนาคม|เมษายน|พฤษภาคม|มิถุนายน|กรกฎาคม|สิงหาคม|กันยายน|ตุลาคม|พฤศจิกายน|ธันวาคม|Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+(\d{2,4})""", RegexOption.IGNORE_CASE)
    private val slashDateRegex = Regex("""\b(\d{1,2})/(\d{1,2})/(\d{2,4})\b""")
    private val refRegex = Regex("""(?:เลขที่รายการ|รหัสอ้างอิง|เลขที่อ้างอิง|Ref(?:\.|erence)?(?:\s*ID)?)\s*[:：]?\s*([A-Za-z0-9\-]{6,32})""", RegexOption.IGNORE_CASE)
    private val standaloneRefRegex = Regex("""\b\d{14,25}\b|\b[A-Z0-9]{12,24}\b""")
    private val accountMaskRegex = Regex("""[xX*\d]{3}-[xX*\d]-[xX*\d]{4,5}-[xX*\d]|[xX*]{3,6}\d{3,4}""")

    fun parse(lines: List<OcrTextLine>): ParsedBankSlip {
        if (lines.isEmpty()) {
            return ParsedBankSlip(
                detectedBank = "",
                amountSatang = null,
                amountConfidence = 0f,
                dateEpochMillis = null,
                dateConfidence = 0f,
                timeText = "",
                timeConfidence = 0f,
                senderName = "",
                senderConfidence = 0f,
                recipientName = "",
                recipientConfidence = 0f,
                paymentReference = "",
                referenceConfidence = 0f,
                accountHint = "",
                overallConfidence = 0f,
                needsReview = true
            )
        }

        val fullText = lines.joinToString("\n") { it.text }
        val bank = detectBank(fullText)

        var amountSatang: Long? = null
        var amountConf = 0f
        var parsedDate: LocalDate? = null
        var dateConf = 0f
        var timeText = ""
        var timeConf = 0f
        var ref = ""
        var refConf = 0f
        var accountHint = ""
        var sender = ""
        var senderConf = 0f
        var recipient = ""
        var recipientConf = 0f

        for ((index, line) in lines.withIndex()) {
            val txt = line.text.trim()
            if (txt.isEmpty()) continue

            // Amount (prioritize lines with บาท / จำนวน / ยอด)
            val amtMatch = amountRegex.find(txt)
            if (amtMatch != null && !txt.contains("ค่าธรรมเนียม") && !txt.contains("Fee", ignoreCase = true)) {
                val parsed = MoneyFormatter.parseThbToSatang(amtMatch.groupValues[1])
                val hasCurrencyWord = txt.contains("บาท") || txt.contains("THB", true) || txt.contains("จำนวน")
                if (parsed != null && (amountSatang == null || (hasCurrencyWord && line.confidence >= amountConf))) {
                    amountSatang = parsed
                    amountConf = line.confidence
                }
            }

            // Date
            if (parsedDate == null) {
                val td = thaiDateRegex.find(txt)
                if (td != null) {
                    val day = td.groupValues[1].toIntOrNull()
                    val monthKey = td.groupValues[2].lowercase(Locale.US)
                    val month = thaiMonthMap[monthKey]
                    val yearRaw = td.groupValues[3].toIntOrNull()
                    if (day != null && month != null && yearRaw != null) {
                        val ceYear = normalizeBeOrCeYear(yearRaw)
                        parsedDate = safeLocalDate(ceYear, month, day)
                        if (parsedDate != null) dateConf = line.confidence
                    }
                } else {
                    val sd = slashDateRegex.find(txt)
                    if (sd != null) {
                        val day = sd.groupValues[1].toIntOrNull()
                        val month = sd.groupValues[2].toIntOrNull()
                        val yearRaw = sd.groupValues[3].toIntOrNull()
                        if (day != null && month != null && yearRaw != null) {
                            val ceYear = normalizeBeOrCeYear(yearRaw)
                            parsedDate = safeLocalDate(ceYear, month, day)
                            if (parsedDate != null) dateConf = line.confidence
                        }
                    }
                }
            }

            // Time
            if (timeText.isEmpty()) {
                val tm = timeRegex.find(txt)
                if (tm != null) {
                    timeText = "${tm.groupValues[1].padStart(2, '0')}:${tm.groupValues[2]}"
                    timeConf = line.confidence
                }
            }

            // Reference
            if (ref.isEmpty()) {
                val rm = refRegex.find(txt)
                if (rm != null) {
                    ref = rm.groupValues[1].trim()
                    refConf = line.confidence
                } else if (txt.contains("เลขที่รายการ") || txt.contains("อ้างอิง") || txt.contains("Ref", true)) {
                    val nextLine = lines.getOrNull(index + 1)?.text?.trim().orEmpty()
                    val sr = standaloneRefRegex.find(nextLine)
                    if (sr != null) {
                        ref = sr.value
                        refConf = minOf(line.confidence, lines[index + 1].confidence)
                    }
                }
            }

            // Account hint
            if (accountHint.isEmpty()) {
                val am = accountMaskRegex.find(txt)
                if (am != null) {
                    accountHint = am.value
                }
            }

            // Sender / Recipient heuristic on Thai slip layout ("จาก / From" -> "ไปยัง / To")
            if (sender.isEmpty() && (txt.startsWith("จาก") || txt.equals("From", ignoreCase = true))) {
                val inline = txt.removePrefix("จาก").removePrefix("From").trim(':', ' ')
                if (inline.length >= 2) {
                    sender = inline
                    senderConf = line.confidence
                } else {
                    val next = lines.getOrNull(index + 1)
                    if (next != null && next.text.trim().length >= 2) {
                        sender = next.text.trim()
                        senderConf = next.confidence
                    }
                }
            }
            if (recipient.isEmpty() && (txt.startsWith("ไปยัง") || txt.startsWith("ถึง") || txt.equals("To", ignoreCase = true))) {
                val inline = txt.removePrefix("ไปยัง").removePrefix("ถึง").removePrefix("To").trim(':', ' ')
                if (inline.length >= 2) {
                    recipient = inline
                    recipientConf = line.confidence
                } else {
                    val next = lines.getOrNull(index + 1)
                    if (next != null && next.text.trim().length >= 2) {
                        recipient = next.text.trim()
                        recipientConf = next.confidence
                    }
                }
            }
        }

        val dateEpochMillis = parsedDate?.let { date ->
            val timeParts = timeText.split(":")
            val localTime = if (timeParts.size == 2) {
                LocalTime.of(timeParts[0].toIntOrNull() ?: 12, timeParts[1].toIntOrNull() ?: 0)
            } else {
                LocalTime.of(12, 0)
            }
            LocalDateTime.of(date, localTime).atZone(ThaiDateFormatter.BANGKOK_ZONE).toInstant().toEpochMilli()
        }

        val confidences = listOf(amountConf, dateConf, timeConf, refConf).filter { it > 0f }
        val overall = if (confidences.isEmpty()) 0f else confidences.average().toFloat()
        val needsReview = amountSatang == null || dateEpochMillis == null || overall < 0.75f

        return ParsedBankSlip(
            detectedBank = bank,
            amountSatang = amountSatang,
            amountConfidence = amountConf,
            dateEpochMillis = dateEpochMillis,
            dateConfidence = dateConf,
            timeText = timeText,
            timeConfidence = timeConf,
            senderName = sender,
            senderConfidence = senderConf,
            recipientName = recipient,
            recipientConfidence = recipientConf,
            paymentReference = ref,
            referenceConfidence = refConf,
            accountHint = accountHint,
            overallConfidence = overall,
            needsReview = needsReview
        )
    }

    private fun detectBank(fullText: String): String {
        val lower = fullText.lowercase(Locale.US)
        return when {
            lower.contains("k plus") || lower.contains("kplus") || fullText.contains("กสิกร") -> "KBank (K PLUS)"
            lower.contains("scb") || fullText.contains("ไทยพาณิชย์") -> "SCB"
            lower.contains("krungthai") || lower.contains("ktb") || fullText.contains("กรุงไทย") -> "Krungthai"
            lower.contains("bualuang") || lower.contains("bbl") || fullText.contains("กรุงเทพ") -> "Bangkok Bank"
            lower.contains("krungsri") || fullText.contains("bay") || fullText.contains("กรุงศรี") -> "Krungsri"
            lower.contains("promptpay") || fullText.contains("พร้อมเพย์") -> "PromptPay"
            else -> ""
        }
    }

    private fun normalizeBeOrCeYear(rawYear: Int): Int {
        return when {
            rawYear in 2400..2700 -> rawYear - 543 // Full BE e.g. 2569 -> 2026
            rawYear in 1900..2300 -> rawYear       // Full CE e.g. 2026
            rawYear in 60..99 -> (2500 + rawYear) - 543 // 2-digit Thai BE e.g. 69 -> 2569 -> 2026
            rawYear in 0..59 -> 2000 + rawYear     // 2-digit CE e.g. 26 -> 2026
            else -> rawYear
        }
    }

    private fun safeLocalDate(year: Int, month: Int, day: Int): LocalDate? {
        return try {
            LocalDate.of(year, month, day)
        } catch (_: Exception) {
            null
        }
    }
}
