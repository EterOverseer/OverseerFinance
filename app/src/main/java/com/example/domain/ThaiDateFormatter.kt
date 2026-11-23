package com.example.domain

import com.example.data.local.DateDisplayFormat
import com.example.data.local.PeriodFilter
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

object ThaiDateFormatter {
    val BANGKOK_ZONE: ZoneId = ZoneId.of("Asia/Bangkok")

    private val thaiMonthsShort = listOf(
        "ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.",
        "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค."
    )

    private val thaiMonthsFull = listOf(
        "มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน", "พฤษภาคม", "มิถุนายน",
        "กรกฎาคม", "สิงหาคม", "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม"
    )

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    fun toBangkokDateTime(epochMillis: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), BANGKOK_ZONE)

    fun toBangkokDate(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(BANGKOK_ZONE).toLocalDate()

    fun currentYearBe(): Int = LocalDate.now(BANGKOK_ZONE).year + 543

    fun currentYearMonth(): String {
        val now = LocalDate.now(BANGKOK_ZONE)
        return String.format(Locale.US, "%04d-%02d", now.year, now.monthValue)
    }

    fun formatDate(epochMillis: Long, format: DateDisplayFormat = DateDisplayFormat.THAI_BE): String {
        val date = toBangkokDate(epochMillis)
        return when (format) {
            DateDisplayFormat.THAI_BE -> {
                val month = thaiMonthsShort[date.monthValue - 1]
                val yearBe = date.year + 543
                "${date.dayOfMonth} $month $yearBe"
            }
            DateDisplayFormat.GREGORIAN_CE -> {
                String.format(Locale.US, "%02d/%02d/%04d", date.dayOfMonth, date.monthValue, date.year)
            }
        }
    }

    fun formatDateTime(epochMillis: Long, format: DateDisplayFormat = DateDisplayFormat.THAI_BE): String {
        val dt = toBangkokDateTime(epochMillis)
        val dateStr = formatDate(epochMillis, format)
        val timeStr = dt.format(timeFormatter)
        return "$dateStr • $timeStr น."
    }

    fun formatMonthYearTh(yearMonth: String): String {
        val parts = yearMonth.split("-")
        if (parts.size != 2) return yearMonth
        val year = parts[0].toIntOrNull() ?: return yearMonth
        val month = parts[1].toIntOrNull() ?: return yearMonth
        if (month !in 1..12) return yearMonth
        return "${thaiMonthsFull[month - 1]} ${year + 543}"
    }

    fun periodBoundsMillis(
        filter: PeriodFilter,
        customStartMillis: Long? = null,
        customEndMillis: Long? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): Pair<Long, Long> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(BANGKOK_ZONE).toLocalDate()
        return when (filter) {
            PeriodFilter.TODAY -> {
                val start = today.atStartOfDay(BANGKOK_ZONE).toInstant().toEpochMilli()
                val end = today.plusDays(1).atStartOfDay(BANGKOK_ZONE).toInstant().toEpochMilli() - 1L
                start to end
            }
            PeriodFilter.THIS_WEEK -> {
                val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val start = monday.atStartOfDay(BANGKOK_ZONE).toInstant().toEpochMilli()
                val end = monday.plusWeeks(1).atStartOfDay(BANGKOK_ZONE).toInstant().toEpochMilli() - 1L
                start to end
            }
            PeriodFilter.THIS_MONTH -> {
                val first = today.withDayOfMonth(1)
                val start = first.atStartOfDay(BANGKOK_ZONE).toInstant().toEpochMilli()
                val end = first.plusMonths(1).atStartOfDay(BANGKOK_ZONE).toInstant().toEpochMilli() - 1L
                start to end
            }
            PeriodFilter.THIS_YEAR -> {
                val first = today.withDayOfYear(1)
                val start = first.atStartOfDay(BANGKOK_ZONE).toInstant().toEpochMilli()
                val end = first.plusYears(1).atStartOfDay(BANGKOK_ZONE).toInstant().toEpochMilli() - 1L
                start to end
            }
            PeriodFilter.CUSTOM -> {
                val s = customStartMillis ?: today.withDayOfMonth(1).atStartOfDay(BANGKOK_ZONE).toInstant().toEpochMilli()
                val e = customEndMillis ?: nowMillis
                minOf(s, e) to maxOf(s, e)
            }
        }
    }
}
