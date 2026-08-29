package com.sankranthi.ledger.data.repository

import androidx.room.withTransaction
import com.sankranthi.ledger.data.local.AppDatabase
import com.sankranthi.ledger.data.local.entity.PendingOperationEntity
import com.sankranthi.ledger.data.local.toDomain
import com.sankranthi.ledger.data.local.toEntity
import com.sankranthi.ledger.data.model.Expense
import com.sankranthi.ledger.data.model.LivestockEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** Who is performing a write. Used for attribution, not authorisation. */
data class Actor(val email: String?, val displayName: String?)

/**
 * The offline-first ledger.
 *
 * Every write goes to Room **and** enqueues an outbox operation inside a single
 * transaction, then returns. It never waits for the network. That atomicity is
 * load-bearing: a row committed without its operation would exist locally and
 * never reach the other partners, and an operation committed without its row
 * would upload something the app cannot show. Either way a partner loses data,
 * which §20 forbids.
 *
 * Reads are Room `Flow`s, so the UI updates the instant a write lands and again
 * when a download arrives, with no explicit refresh anywhere.
 */
class LedgerRepository(
    private val database: AppDatabase,
) {
    private val livestockDao = database.livestockDao()
    private val expenseDao = database.expenseDao()
    private val operations = database.pendingOperationDao()

    fun observeLivestock(): Flow<List<LivestockEntry>> =
        livestockDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeExpenses(): Flow<List<Expense>> =
        expenseDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /** Operations waiting to reach the spreadsheet, for the sync indicator. */
    fun observePendingCount(): Flow<Int> = operations.observePendingCount()

    /**
     * Creates or updates a livestock entry. A blank [LivestockEntry.id] means
     * "new", and the UUID is minted here rather than server-side — two phones
     * must be able to create rows offline without colliding (§21).
     */
    suspend fun saveLivestock(entry: LivestockEntry, actor: Actor): String {
        val id = entry.id.ifBlank { UUID.randomUUID().toString() }
        database.withTransaction {
            val existing = livestockDao.byId(id)
            val row = entry.copy(id = id).toEntity(existing, actor.email, actor.displayName)
            livestockDao.upsert(row)
            markDirty(PendingOperationEntity.TYPE_LIVESTOCK, id, row.sync.version)
        }
        return id
    }

    /**
     * Soft delete. Deliberately routed through the *same* dirty-marking path as
     * an edit: a deletion is just a row whose `deleted` flag is now set, so the
     * uploader has one code path and there is no ordering hazard between an
     * "update" operation and a "delete" operation for the same row (§10).
     */
    suspend fun deleteLivestock(id: String, actor: Actor) {
        database.withTransaction {
            val existing = livestockDao.byId(id) ?: return@withTransaction
            livestockDao.softDelete(id, System.currentTimeMillis(), actor.email)
            markDirty(PendingOperationEntity.TYPE_LIVESTOCK, id, existing.sync.version)
        }
    }

    suspend fun saveExpense(expense: Expense, actor: Actor): String {
        val id = expense.id.ifBlank { UUID.randomUUID().toString() }
        database.withTransaction {
            val existing = expenseDao.byId(id)
            val row = expense.copy(id = id).toEntity(existing, actor.email, actor.displayName)
            expenseDao.upsert(row)
            markDirty(PendingOperationEntity.TYPE_EXPENSE, id, row.sync.version)
        }
        return id
    }

    suspend fun deleteExpense(id: String, actor: Actor) {
        database.withTransaction {
            val existing = expenseDao.byId(id) ?: return@withTransaction
            expenseDao.softDelete(id, System.currentTimeMillis(), actor.email)
            markDirty(PendingOperationEntity.TYPE_EXPENSE, id, existing.sync.version)
        }
    }

    /**
     * Records that a row needs uploading, coalescing repeat edits.
     *
     * If an operation for this entity is already queued, no second one is added.
     * This matters for correctness, not just tidiness: enqueuing one operation
     * per edit would give every operation the same `baseVersion` (the server
     * version has not moved while offline), so the first upload would succeed and
     * bump the version and the rest would then conflict *with our own edit*.
     * One operation meaning "this row is dirty" avoids that entirely.
     *
     * The payload is left empty on purpose. The uploader reads the row at send
     * time, so an entry edited three times offline uploads its final state once
     * instead of replaying three stale bodies.
     *
     * Contract this imposes on the Phase 5 uploader: because a coalesced
     * operation can be edited again while its upload is in flight, the uploader
     * must only mark a row SYNCED if the row's `updatedAt` still matches what it
     * actually sent. If it changed mid-flight, the row stays dirty and keeps its
     * operation. Clearing the operation unconditionally would drop that edit.
     */
    private suspend fun markDirty(entityType: String, entityId: String, baseVersion: Int) {
        val alreadyQueued = operations.forEntity(entityType, entityId)
            .any { it.opType == PendingOperationEntity.OP_UPSERT }
        if (alreadyQueued) return

        operations.enqueue(
            PendingOperationEntity(
                opId = UUID.randomUUID().toString(),
                entityType = entityType,
                entityId = entityId,
                opType = PendingOperationEntity.OP_UPSERT,
                payload = "",
                baseVersion = baseVersion,
            ),
        )
    }
}
