package com.sankranthi.ledger.data.model

/**
 * Domain models. Deliberately free of serialisation annotations: the wire shapes
 * the gateway speaks live in `data/remote/dto/`, and the storage shapes live in
 * `data/local/entity/`. Keeping the three separate is what lets the backend
 * change without touching the UI (§22, §28).
 */

/** Direction of a livestock trade. */
enum class TradeKind {
    BUY,
    SELL,
    ;

    val label: String get() = if (this == BUY) "Purchase" else "Sale"

    /** Value used in the spreadsheet and in DTOs. */
    val wire: String get() = if (this == BUY) "buy" else "sell"

    companion object {
        fun fromWire(value: String): TradeKind? = entries.firstOrNull { it.wire == value }
    }
}

/** A livestock purchase or sale. */
data class LivestockEntry(
    /** Client-generated UUID. Blank only for an entry that has not been saved yet. */
    val id: String,
    val kind: TradeKind,
    val animal: String,
    val headCount: Int,
    /** Paise. */
    val amountMinor: Long,
    val counterparty: String? = null,
    /** ISO `yyyy-MM-dd`. */
    val occurredOn: String,
    val notes: String? = null,
    val receiptId: String? = null,
    val createdByName: String? = null,
    /** True while the row is waiting to reach the shared spreadsheet. */
    val pendingSync: Boolean = false,
) {
    /** Effect on the books: a sale brings money in, a purchase takes it out. */
    val signedMinor: Long get() = if (kind == TradeKind.SELL) amountMinor else -amountMinor
}

/** Categories of running the operation, as opposed to trading stock. */
enum class ExpenseCategory {
    FEED,
    VETERINARY,
    LABOUR,
    TRANSPORT,
    SHED_REPAIR,
    UTILITIES,
    OTHER,
    ;

    val label: String get() = when (this) {
        FEED -> "Feed & fodder"
        VETERINARY -> "Veterinary"
        LABOUR -> "Labour"
        TRANSPORT -> "Transport"
        SHED_REPAIR -> "Shed & repairs"
        UTILITIES -> "Utilities"
        OTHER -> "Other"
    }

    val wire: String get() = when (this) {
        FEED -> "feed"
        VETERINARY -> "veterinary"
        LABOUR -> "labour"
        TRANSPORT -> "transport"
        SHED_REPAIR -> "shed_repair"
        UTILITIES -> "utilities"
        OTHER -> "other"
    }

    companion object {
        fun fromWire(value: String): ExpenseCategory? = entries.firstOrNull { it.wire == value }
    }
}

/** An organisation maintenance expense. */
data class Expense(
    val id: String,
    val category: ExpenseCategory,
    val amountMinor: Long,
    val description: String? = null,
    val occurredOn: String,
    val receiptId: String? = null,
    val createdByName: String? = null,
    val pendingSync: Boolean = false,
)

/** Roll-up shown on the dashboard. */
data class LedgerSummary(
    val salesMinor: Long = 0,
    val purchasesMinor: Long = 0,
    val expensesMinor: Long = 0,
    val headBought: Int = 0,
    val headSold: Int = 0,
) {
    val grossMarginMinor: Long get() = salesMinor - purchasesMinor
    val netMinor: Long get() = grossMarginMinor - expensesMinor

    companion object {
        fun of(livestock: List<LivestockEntry>, expenses: List<Expense>): LedgerSummary =
            LedgerSummary(
                salesMinor = livestock.filter { it.kind == TradeKind.SELL }.sumOf { it.amountMinor },
                purchasesMinor = livestock.filter { it.kind == TradeKind.BUY }.sumOf { it.amountMinor },
                expensesMinor = expenses.sumOf { it.amountMinor },
                headBought = livestock.filter { it.kind == TradeKind.BUY }.sumOf { it.headCount },
                headSold = livestock.filter { it.kind == TradeKind.SELL }.sumOf { it.headCount },
            )
    }
}
