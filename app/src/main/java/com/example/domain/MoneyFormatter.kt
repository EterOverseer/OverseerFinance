package com.example.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Exact monetary representation in Satang (1 THB = 100 Satang).
 * Never uses Double/Float arithmetic for financial totals.
 */
object MoneyFormatter {
    private val thbSymbols = DecimalFormatSymbols(Locale.US).apply {
        groupingSeparator = ','
        decimalSeparator = '.'
    }

    fun formatThb(satang: Long, includeSymbol: Boolean = true, showSign: Boolean = false): String {
        val isNegative = satang < 0L
        val absSatang = if (satang == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(satang)
        val baht = absSatang / 100L
        val remSatang = (absSatang % 100L).toInt()
        val bahtFormatted = DecimalFormat("#,##0", thbSymbols).format(baht)
        val dec = String.format(Locale.US, "%02d", remSatang)
        val signPrefix = when {
            isNegative -> "-"
            showSign && satang > 0L -> "+"
            else -> ""
        }
        val symbol = if (includeSymbol) "฿" else ""
        return "$signPrefix$symbol$bahtFormatted.$dec"
    }

    fun formatCompactThb(satang: Long): String {
        val baht = BigDecimal.valueOf(satang).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
        val absBaht = baht.abs()
        val sign = if (satang < 0L) "-" else ""
        return when {
            absBaht >= BigDecimal(1_000_000) -> {
                val m = absBaht.divide(BigDecimal(1_000_000), 2, RoundingMode.HALF_UP)
                "${sign}฿${m.stripTrailingZeros().toPlainString()}M"
            }
            absBaht >= BigDecimal(10_000) -> {
                val k = absBaht.divide(BigDecimal(1_000), 1, RoundingMode.HALF_UP)
                "${sign}฿${k.stripTrailingZeros().toPlainString()}K"
            }
            else -> formatThb(satang)
        }
    }

    /**
     * Parses a user-entered THB amount string (e.g. "1,250.50" or "500") into exact Satang.
     * Returns null if invalid or negative when allowNegative is false.
     */
    fun parseThbToSatang(input: String, allowNegative: Boolean = false): Long? {
        val cleaned = input.trim()
            .replace("฿", "")
            .replace("บาท", "")
            .replace("THB", "", ignoreCase = true)
            .replace(",", "")
            .replace(" ", "")
        if (cleaned.isEmpty()) return null
        return try {
            val bd = BigDecimal(cleaned)
            val satang = bd.multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).longValueExact()
            if (!allowNegative && satang < 0L) null else satang
        } catch (_: Exception) {
            null
        }
    }

    fun satangToInputString(satang: Long): String {
        val bd = BigDecimal.valueOf(satang).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
        return bd.toPlainString()
    }
}
