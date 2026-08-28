package com.example.sankranthi.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerSummaryTest {

    private fun trade(kind: TradeKind, head: Int, minor: Long) = LivestockEntry(
        id = "t${minor}${head}${kind.wire}",
        kind = kind,
        animal = "Goat",
        headCount = head,
        amountMinor = minor,
        occurredOn = "2026-08-01",
    )

    private fun expense(minor: Long) = Expense(
        id = "e$minor",
        category = ExpenseCategory.FEED,
        amountMinor = minor,
        occurredOn = "2026-08-01",
    )

    @Test
    fun `sums sales, purchases and expenses separately`() {
        val summary = LedgerSummary.of(
            livestock = listOf(
                trade(TradeKind.BUY, 10, 100_000_00L),
                trade(TradeKind.SELL, 4, 60_000_00L),
                trade(TradeKind.SELL, 2, 30_000_00L),
            ),
            expenses = listOf(expense(10_000_00L), expense(5_000_00L)),
        )

        assertEquals(90_000_00L, summary.salesMinor)
        assertEquals(100_000_00L, summary.purchasesMinor)
        assertEquals(15_000_00L, summary.expensesMinor)
    }

    @Test
    fun `margin is sales minus purchases and net also subtracts expenses`() {
        val summary = LedgerSummary.of(
            livestock = listOf(
                trade(TradeKind.BUY, 10, 100_000_00L),
                trade(TradeKind.SELL, 10, 130_000_00L),
            ),
            expenses = listOf(expense(12_000_00L)),
        )

        assertEquals(30_000_00L, summary.grossMarginMinor)
        assertEquals(18_000_00L, summary.netMinor)
    }

    @Test
    fun `net goes negative when expenses outrun the margin`() {
        val summary = LedgerSummary.of(
            livestock = listOf(
                trade(TradeKind.BUY, 5, 50_000_00L),
                trade(TradeKind.SELL, 5, 55_000_00L),
            ),
            expenses = listOf(expense(9_000_00L)),
        )

        assertEquals(-4_000_00L, summary.netMinor)
    }

    @Test
    fun `head counts track both directions`() {
        val summary = LedgerSummary.of(
            livestock = listOf(
                trade(TradeKind.BUY, 12, 1L),
                trade(TradeKind.SELL, 5, 2L),
            ),
            expenses = emptyList(),
        )

        assertEquals(12, summary.headBought)
        assertEquals(5, summary.headSold)
    }

    @Test
    fun `an empty ledger is all zeroes`() {
        val summary = LedgerSummary.of(emptyList(), emptyList())

        assertEquals(0L, summary.salesMinor)
        assertEquals(0L, summary.netMinor)
        assertEquals(0, summary.headBought)
    }

    @Test
    fun `a purchase counts against the books and a sale for them`() {
        assertEquals(-500L, trade(TradeKind.BUY, 1, 500L).signedMinor)
        assertEquals(500L, trade(TradeKind.SELL, 1, 500L).signedMinor)
    }
}
