package com.sankranthi.ledger.data.local

import androidx.room.TypeConverter
import com.sankranthi.ledger.data.local.entity.SyncStatus
import com.sankranthi.ledger.data.local.entity.UploadStatus
import com.sankranthi.ledger.data.model.ExpenseCategory
import com.sankranthi.ledger.data.model.Permission
import com.sankranthi.ledger.data.model.TradeKind

/**
 * Enums cross the Room boundary as their `wire` strings — the same values the
 * gateway and the spreadsheet use. One vocabulary end to end means a stored row
 * and an uploaded row can never disagree about what "buy" means.
 *
 * Every enum stored by an entity needs a converter here, including the internal
 * ones. Room will silently fall back to storing an enum by `name` if no converter
 * is registered, which produces `PENDING` in the column while queries written
 * against `wire` look for `pending` — the two then never match, and reading the
 * row back throws. Converters for [SyncStatus] and [UploadStatus] exist to close
 * exactly that gap, so DAO parameters are typed as the enum rather than as
 * `String` and there is no second representation to get wrong.
 *
 * Unknown values decode to a safe default rather than throwing: a row written by
 * a newer build must not make an older build unable to read its own database.
 */
class Converters {

    @TypeConverter
    fun fromTradeKind(value: TradeKind): String = value.wire

    @TypeConverter
    fun toTradeKind(value: String): TradeKind = TradeKind.fromWire(value) ?: TradeKind.BUY

    @TypeConverter
    fun fromExpenseCategory(value: ExpenseCategory): String = value.wire

    @TypeConverter
    fun toExpenseCategory(value: String): ExpenseCategory =
        ExpenseCategory.fromWire(value) ?: ExpenseCategory.OTHER

    @TypeConverter
    fun fromSyncStatus(value: SyncStatus): String = value.wire

    /**
     * Defaults to PENDING, never SYNCED. An unreadable status must err towards
     * "this row still needs uploading" — the opposite mistake would strand a
     * record on the device forever (§20).
     */
    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus =
        SyncStatus.entries.firstOrNull { it.wire == value } ?: SyncStatus.PENDING

    @TypeConverter
    fun fromUploadStatus(value: UploadStatus): String = value.wire

    /** Same reasoning as [toSyncStatus]: default to "still needs uploading". */
    @TypeConverter
    fun toUploadStatus(value: String): UploadStatus =
        UploadStatus.entries.firstOrNull { it.wire == value } ?: UploadStatus.PENDING

    @TypeConverter
    fun fromPermissions(value: Set<Permission>): String =
        value.map(Permission::wire).sorted().joinToString(",")

    @TypeConverter
    fun toPermissions(value: String): Set<Permission> =
        value.split(",")
            .mapNotNull { Permission.fromWire(it.trim()) }
            .toSet()
}
