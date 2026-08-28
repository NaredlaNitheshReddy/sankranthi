package com.example.sankranthi.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Direction of a livestock trade. */
@Serializable
enum class TradeKind {
    @SerialName("buy") BUY,
    @SerialName("sell") SELL,
    ;

    val label: String get() = if (this == BUY) "Purchase" else "Sale"

    /** Value stored in the Postgres enum column. */
    val wire: String get() = if (this == BUY) "buy" else "sell"
}

/** A livestock purchase or sale — a row of `public.livestock_entries`. */
@Serializable
data class LivestockEntry(
    val id: String,
    val kind: TradeKind,
    val animal: String,
    @SerialName("head_count") val headCount: Int,
    @SerialName("amount_minor") val amountMinor: Long,
    val counterparty: String? = null,
    @SerialName("occurred_on") val occurredOn: String,
    val notes: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
) {
    /** Effect on the books: a sale brings money in, a purchase takes it out. */
    val signedMinor: Long get() = if (kind == TradeKind.SELL) amountMinor else -amountMinor
}

/** Categories of running the operation, as opposed to trading stock. */
@Serializable
enum class ExpenseCategory {
    @SerialName("feed") FEED,
    @SerialName("veterinary") VETERINARY,
    @SerialName("labour") LABOUR,
    @SerialName("transport") TRANSPORT,
    @SerialName("shed_repair") SHED_REPAIR,
    @SerialName("utilities") UTILITIES,
    @SerialName("other") OTHER,
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

    /** Value stored in the Postgres enum column. */
    val wire: String get() = when (this) {
        FEED -> "feed"
        VETERINARY -> "veterinary"
        LABOUR -> "labour"
        TRANSPORT -> "transport"
        SHED_REPAIR -> "shed_repair"
        UTILITIES -> "utilities"
        OTHER -> "other"
    }
}

/** An organisation maintenance expense — a row of `public.expenses`. */
@Serializable
data class Expense(
    val id: String,
    val category: ExpenseCategory,
    @SerialName("amount_minor") val amountMinor: Long,
    val description: String? = null,
    @SerialName("occurred_on") val occurredOn: String,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
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
