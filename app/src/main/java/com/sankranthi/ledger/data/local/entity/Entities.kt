package com.sankranthi.ledger.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sankranthi.ledger.data.model.ExpenseCategory
import com.sankranthi.ledger.data.model.TradeKind

/**
 * A livestock purchase or sale.
 *
 * [id] is a client-generated UUID, not an autoincrement, because two phones can
 * both create rows while offline and must never collide (§9, §21).
 */
@Entity(
    tableName = "livestock_entries",
    indices = [
        Index("occurredOn"),
        Index("syncStatus"),
    ],
)
data class LivestockEntity(
    @PrimaryKey val id: String,
    val kind: TradeKind,
    val animal: String,
    val headCount: Int,
    /** Paise, never a floating-point rupee value. */
    val amountMinor: Long,
    val counterparty: String?,
    /** ISO `yyyy-MM-dd`, matching the spreadsheet and Postgres `date`. */
    val occurredOn: String,
    val notes: String?,
    /** FK to [ReceiptEntity.id]; deliberately not a Room relation, see note below. */
    val receiptId: String?,
    @Embedded val sync: SyncMeta,
)

/** An organisation maintenance expense: feed, vet, labour, transport, repairs. */
@Entity(
    tableName = "expenses",
    indices = [
        Index("occurredOn"),
        Index("syncStatus"),
    ],
)
data class ExpenseEntity(
    @PrimaryKey val id: String,
    val category: ExpenseCategory,
    val amountMinor: Long,
    val description: String?,
    val occurredOn: String,
    val receiptId: String?,
    @Embedded val sync: SyncMeta,
)

/** Where a receipt image stands with respect to Google Drive. */
enum class UploadStatus {
    /** On the device, not yet in Drive. */
    PENDING,
    UPLOADED,
    FAILED,
    ;

    val wire: String get() = name.lowercase()
}

/**
 * A receipt image. The file itself lives in app-private storage until uploaded;
 * only the metadata is synchronised (§12).
 *
 * [localPath] is kept even after upload so the image can be shown without a
 * network round trip, and so a failed upload never loses the only copy (§13).
 */
@Entity(
    tableName = "receipts",
    indices = [Index("uploadStatus")],
)
data class ReceiptEntity(
    @PrimaryKey val id: String,
    val localPath: String?,
    val mimeType: String,
    val sizeBytes: Long,
    /** Google Drive file id, null until the upload succeeds. */
    val driveFileId: String?,
    val uploadStatus: UploadStatus,
    val uploadAttempts: Int = 0,
    val lastError: String? = null,
    @Embedded val sync: SyncMeta,
)

/**
 * The outbox. One row per *operation*, not per record — because two edits to the
 * same row while offline are two operations that must both be applied, in order,
 * and must not be deduplicated against each other (§3.1 of the review).
 *
 * [opId] is the idempotency key the gateway dedupes on. It is generated once,
 * when the operation is enqueued, and reused across every retry — that is the
 * whole mechanism that prevents duplicate rows (§21).
 */
@Entity(
    tableName = "pending_operations",
    indices = [
        Index("entityType", "entityId"),
        Index("createdAt"),
    ],
)
data class PendingOperationEntity(
    @PrimaryKey val opId: String,
    val entityType: String,
    val entityId: String,
    val opType: String,
    /** JSON body sent to the gateway. */
    val payload: String,
    /** Server version this edit was based on, for optimistic concurrency. */
    val baseVersion: Int,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    /** Ordering key. Operations must be replayed in the order they were made. */
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val TYPE_LIVESTOCK = "livestock"
        const val TYPE_EXPENSE = "expense"
        const val TYPE_RECEIPT = "receipt"

        const val OP_UPSERT = "UPSERT"
        const val OP_DELETE = "DELETE"
        const val OP_UPLOAD_RECEIPT = "UPLOAD_RECEIPT"
    }
}

/**
 * Single-row table holding sync progress. Kept in the database rather than
 * DataStore so that the download cursor advances in the same transaction that
 * writes the downloaded rows — otherwise a crash between the two would silently
 * skip changes.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    /** Highest `serverSeq` successfully applied locally. */
    val lastSeq: Long = 0,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
