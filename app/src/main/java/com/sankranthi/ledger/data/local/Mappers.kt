package com.sankranthi.ledger.data.local

import com.sankranthi.ledger.data.local.entity.ExpenseEntity
import com.sankranthi.ledger.data.local.entity.LivestockEntity
import com.sankranthi.ledger.data.local.entity.SyncMeta
import com.sankranthi.ledger.data.local.entity.SyncStatus
import com.sankranthi.ledger.data.model.Expense
import com.sankranthi.ledger.data.model.LivestockEntry

/**
 * Storage ↔ domain translation. One direction drops sync bookkeeping the UI has
 * no business knowing about; the other preserves it, because an edit must never
 * silently reset a row's `version` and cause a spurious conflict on upload.
 */

fun LivestockEntity.toDomain(): LivestockEntry = LivestockEntry(
    id = id,
    kind = kind,
    animal = animal,
    headCount = headCount,
    amountMinor = amountMinor,
    counterparty = counterparty,
    occurredOn = occurredOn,
    notes = notes,
    receiptId = receiptId,
    createdByName = sync.createdByName,
    pendingSync = sync.syncStatus != SyncStatus.SYNCED,
)

fun ExpenseEntity.toDomain(): Expense = Expense(
    id = id,
    category = category,
    amountMinor = amountMinor,
    description = description,
    occurredOn = occurredOn,
    receiptId = receiptId,
    createdByName = sync.createdByName,
    pendingSync = sync.syncStatus != SyncStatus.SYNCED,
)

/**
 * Builds the row to store for a user edit.
 *
 * [existing] is the row currently in the database, if any. Its [SyncMeta] is
 * carried forward with only the "touched" fields changed, so `version` and
 * `serverSeq` — both server-owned — survive the edit. Passing null produces a
 * brand-new row owned by [actorEmail].
 */
fun LivestockEntry.toEntity(
    existing: LivestockEntity?,
    actorEmail: String?,
    actorName: String?,
    now: Long = System.currentTimeMillis(),
): LivestockEntity = LivestockEntity(
    id = id,
    kind = kind,
    animal = animal,
    headCount = headCount,
    amountMinor = amountMinor,
    counterparty = counterparty,
    occurredOn = occurredOn,
    notes = notes,
    receiptId = receiptId,
    sync = existing?.sync?.touched(by = actorEmail, at = now)
        ?: SyncMeta(
            createdAt = now,
            updatedAt = now,
            createdBy = actorEmail,
            updatedBy = actorEmail,
            createdByName = actorName,
        ),
)

fun Expense.toEntity(
    existing: ExpenseEntity?,
    actorEmail: String?,
    actorName: String?,
    now: Long = System.currentTimeMillis(),
): ExpenseEntity = ExpenseEntity(
    id = id,
    category = category,
    amountMinor = amountMinor,
    description = description,
    occurredOn = occurredOn,
    receiptId = receiptId,
    sync = existing?.sync?.touched(by = actorEmail, at = now)
        ?: SyncMeta(
            createdAt = now,
            updatedAt = now,
            createdBy = actorEmail,
            updatedBy = actorEmail,
            createdByName = actorName,
        ),
)
