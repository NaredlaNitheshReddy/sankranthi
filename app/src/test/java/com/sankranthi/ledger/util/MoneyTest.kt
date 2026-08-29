package com.sankranthi.ledger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

    @Test
    fun `parses whole rupees into paise`() {
        assertEquals(1_200_00L, Money.parseToMinor("1200"))
    }

    @Test
    fun `parses paise`() {
        assertEquals(1_200_50L, Money.parseToMinor("1200.50"))
        assertEquals(1_200_05L, Money.parseToMinor("1200.05"))
    }

    @Test
    fun `tolerates grouping, spaces and the rupee sign`() {
        assertEquals(1_23_456_00L, Money.parseToMinor("₹1,23,456"))
        assertEquals(1_200_00L, Money.parseToMinor(" 1 200 "))
    }

    @Test
    fun `rejects input that is not a usable amount`() {
        assertNull(Money.parseToMinor(""))
        assertNull(Money.parseToMinor("abc"))
        assertNull(Money.parseToMinor("12.3.4"))
        // Negatives are a data-entry mistake, not a credit.
        assertNull(Money.parseToMinor("-500"))
        // More precision than paise cannot be stored, so it is refused rather
        // than silently rounded.
        assertNull(Money.parseToMinor("10.999"))
    }

    @Test
    fun `editable form round-trips through the parser`() {
        listOf(0L, 5L, 100L, 1_200_50L, 99_99_999_99L).forEach { minor ->
            assertEquals(minor, Money.parseToMinor(Money.toEditableRupees(minor)))
        }
    }

    @Test
    fun `formats with two decimal places`() {
        assertEquals("1200.00", Money.toEditableRupees(1_200_00L))
        assertEquals("0.05", Money.toEditableRupees(5L))
    }

    @Test
    fun `signed formatting marks money going out`() {
        val negative = Money.formatSigned(-1_200_00L)
        val positive = Money.formatSigned(1_200_00L)
        assertEquals(true, negative.startsWith("-"))
        assertEquals(false, positive.startsWith("-"))
    }
}
