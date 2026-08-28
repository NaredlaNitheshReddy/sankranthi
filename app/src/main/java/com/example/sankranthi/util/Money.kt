package com.example.sankranthi.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/**
 * Money is stored as an integer count of paise so that arithmetic on the books
 * is exact. These helpers are the only place rupees and paise convert.
 */
object Money {

    private val locale = Locale.forLanguageTag("en-IN")

    /** "₹1,23,456.78" */
    fun format(amountMinor: Long): String {
        val formatter = NumberFormat.getCurrencyInstance(locale).apply {
            maximumFractionDigits = 2
            minimumFractionDigits = 2
        }
        return formatter.format(BigDecimal.valueOf(amountMinor, 2))
    }

    /** "1,23,456" — no symbol, no paise. For dense summary tiles. */
    fun formatCompact(amountMinor: Long): String {
        val rupees = BigDecimal.valueOf(amountMinor, 2).setScale(0, RoundingMode.HALF_UP)
        return "₹" + NumberFormat.getIntegerInstance(locale).format(rupees)
    }

    /** Formats with an explicit sign, for figures that can go either way. */
    fun formatSigned(amountMinor: Long): String =
        if (amountMinor < 0) "-${format(-amountMinor)}" else format(amountMinor)

    /**
     * Plain rupees with no grouping ("1234.50"), for pre-filling an edit field.
     * Round-trips through [parseToMinor].
     */
    fun toEditableRupees(amountMinor: Long): String =
        BigDecimal.valueOf(amountMinor, 2).toPlainString()

    /**
     * Parses user input in rupees ("1200", "1,200.50", "₹1200") into paise.
     * Returns null when the text is not a usable amount.
     */
    fun parseToMinor(input: String): Long? {
        val cleaned = input.trim().removePrefix("₹").replace(",", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        val decimal = cleaned.toBigDecimalOrNull() ?: return null
        if (decimal.signum() < 0) return null
        if (decimal.scale() > 2) return null
        return decimal.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).toLong()
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? =
        try { BigDecimal(this) } catch (_: NumberFormatException) { null }
}
