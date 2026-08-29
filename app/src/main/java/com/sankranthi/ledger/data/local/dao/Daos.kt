package com.sankranthi.ledger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.sankranthi.ledger.data.local.entity.ExpenseEntity
import com.sankranthi.ledger.data.local.entity.LivestockEntity
import com.sankranthi.ledger.data.local.entity.PendingOperationEntity
import com.sankranthi.ledger.data.local.entity.ReceiptEntity
import com.sankranthi.ledger.data.local.entity.SyncStateEntity
import com.sankranthi.ledger.data.local.entity.SyncStatus
import com.sankranthi.ledger.data.local.entity.UploadStatus
import kotlinx.coroutines.flow.Flow

/**
 * Note on every read query below: soft-deleted rows are filtered out here rather
 * than physically removed, so a delete can still propagate to other devices
 * (§10). The UI therefore never has to know tombstones exist.
 */
@Dao
interface LivestockDao {

    @Query("SELECT * FROM livestock_entries WHERE deleted = 0 ORDER BY occurredOn DESC, createdAt DESC")
    fun observeAll(): Flow<List<LivestockEntity>>

    @Query("SELECT * FROM livestock_entries WHERE id = :id")
    suspend fun byId(id: String): LivestockEntity?

    @Upsert
    suspend fun upsert(entry: LivestockEntity)

    @Upsert
    suspend fun upsertAll(entries: List<LivestockEntity>)

    /** Soft delete. The row stays until the tombstone has been synced and purged. */
    @Query(
        """
        UPDATE livestock_entries
           SET deleted = 1, syncStatus = :pending, updatedAt = :at, updatedBy = :by
         WHERE id = :id
        """,
    )
    suspend fun softDelete(
        id: String,
        at: Long,
        by: String?,
        pending: SyncStatus = SyncStatus.PENDING,
    )

    @Query("SELECT * FROM livestock_entries WHERE syncStatus != :synced")
    suspend fun unsynced(synced: SyncStatus = SyncStatus.SYNCED): List<LivestockEntity>

    /** Physical removal, only for tombstones every device has already seen. */
    @Query("DELETE FROM livestock_entries WHERE deleted = 1 AND syncStatus = :synced AND serverSeq <= :upToSeq")
    suspend fun purgeTombstones(upToSeq: Long, synced: SyncStatus = SyncStatus.SYNCED): Int
}

@Dao
interface ExpenseDao {

    @Query("SELECT * FROM expenses WHERE deleted = 0 ORDER BY occurredOn DESC, createdAt DESC")
    fun observeAll(): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun byId(id: String): ExpenseEntity?

    @Upsert
    suspend fun upsert(expense: ExpenseEntity)

    @Upsert
    suspend fun upsertAll(expenses: List<ExpenseEntity>)

    @Query(
        """
        UPDATE expenses
           SET deleted = 1, syncStatus = :pending, updatedAt = :at, updatedBy = :by
         WHERE id = :id
        """,
    )
    suspend fun softDelete(
        id: String,
        at: Long,
        by: String?,
        pending: SyncStatus = SyncStatus.PENDING,
    )

    @Query("SELECT * FROM expenses WHERE syncStatus != :synced")
    suspend fun unsynced(synced: SyncStatus = SyncStatus.SYNCED): List<ExpenseEntity>

    @Query("DELETE FROM expenses WHERE deleted = 1 AND syncStatus = :synced AND serverSeq <= :upToSeq")
    suspend fun purgeTombstones(upToSeq: Long, synced: SyncStatus = SyncStatus.SYNCED): Int
}

@Dao
interface ReceiptDao {

    @Query("SELECT * FROM receipts WHERE id = :id")
    suspend fun byId(id: String): ReceiptEntity?

    @Query("SELECT * FROM receipts WHERE id = :id")
    fun observeById(id: String): Flow<ReceiptEntity?>

    @Upsert
    suspend fun upsert(receipt: ReceiptEntity)

    @Upsert
    suspend fun upsertAll(receipts: List<ReceiptEntity>)

    @Query("SELECT * FROM receipts WHERE uploadStatus = :pending ORDER BY createdAt ASC LIMIT :limit")
    suspend fun awaitingUpload(
        limit: Int,
        pending: UploadStatus = UploadStatus.PENDING,
    ): List<ReceiptEntity>

    @Query("UPDATE receipts SET uploadStatus = :status, driveFileId = :driveFileId, lastError = :error, uploadAttempts = uploadAttempts + 1 WHERE id = :id")
    suspend fun recordUploadOutcome(
        id: String,
        status: UploadStatus,
        driveFileId: String?,
        error: String?,
    )
}

/**
 * The outbox queue. `LIMIT` on the drain query is deliberate: the gateway has a
 * six-minute execution ceiling, so a sync must never try to upload an unbounded
 * backlog in one request (§5b of the review).
 */
@Dao
interface PendingOperationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(operation: PendingOperationEntity)

    @Query("SELECT * FROM pending_operations ORDER BY createdAt ASC LIMIT :limit")
    suspend fun nextBatch(limit: Int): List<PendingOperationEntity>

    @Query("SELECT COUNT(*) FROM pending_operations")
    fun observePendingCount(): Flow<Int>

    @Query("DELETE FROM pending_operations WHERE opId = :opId")
    suspend fun remove(opId: String)

    @Query("DELETE FROM pending_operations WHERE opId IN (:opIds)")
    suspend fun removeAll(opIds: List<String>)

    @Query("UPDATE pending_operations SET attemptCount = attemptCount + 1, lastError = :error WHERE opId = :opId")
    suspend fun recordFailure(opId: String, error: String?)

    @Query("SELECT * FROM pending_operations WHERE entityType = :type AND entityId = :id ORDER BY createdAt ASC")
    suspend fun forEntity(type: String, id: String): List<PendingOperationEntity>
}

@Dao
interface SyncStateDao {

    @Query("SELECT * FROM sync_state WHERE id = :id")
    suspend fun get(id: Int = SyncStateEntity.SINGLETON_ID): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE id = :id")
    fun observe(id: Int = SyncStateEntity.SINGLETON_ID): Flow<SyncStateEntity?>

    @Upsert
    suspend fun put(state: SyncStateEntity)

    @Transaction
    suspend fun advanceSeq(newSeq: Long, at: Long) {
        val current = get() ?: SyncStateEntity()
        // Never move the cursor backwards: an out-of-order or replayed response
        // must not cause changes to be downloaded twice or skipped.
        if (newSeq >= current.lastSeq) {
            put(current.copy(lastSeq = newSeq, lastSyncAt = at, lastError = null))
        }
    }
}
