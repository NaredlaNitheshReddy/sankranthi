package com.sankranthi.ledger.data.local.entity

/** Where a row stands with respect to the shared spreadsheet. */
enum class SyncStatus {
    /** Created or edited locally, not yet accepted by the gateway. */
    PENDING,

    /** The gateway has accepted this exact version. */
    SYNCED,

    /** Upload was rejected in a way retrying alone will not fix. */
    FAILED,

    /**
     * The gateway reported a newer server version than we based our edit on.
     * Needs reconciliation before it can be uploaded again.
     */
    CONFLICTED,
    ;

    val wire: String get() = name.lowercase()
}

/**
 * The sync bookkeeping every synchronised row carries, per §9 of the
 * requirements. Embedded rather than repeated so the rules stay in one place.
 *
 * Ownership matters here and is easy to get wrong:
 * - [version], [serverSeq] and [serverUpdatedAt] are **server-owned**. The client
 *   only ever copies back what the gateway returned.
 * - [createdAt] and [updatedAt] are client clocks and are for **display and
 *   ordering within this device only**. They are never used to resolve a
 *   conflict, because device clocks can be wrong or deliberately changed.
 */
data class SyncMeta(
    /** Server-assigned optimistic-concurrency counter. 0 until first accepted. */
    val version: Int = 0,

    /** Server-assigned monotonic sequence; also the download cursor. */
    val serverSeq: Long? = null,

    /** Authoritative timestamp from the gateway, ISO-8601. Null until synced. */
    val serverUpdatedAt: String? = null,

    val syncStatus: SyncStatus = SyncStatus.PENDING,

    /** Soft delete, so the removal can propagate to other devices. */
    val deleted: Boolean = false,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),

    /** Email of the account that created / last edited the row. */
    val createdBy: String? = null,
    val updatedBy: String? = null,

    /** Display name of the creator, as the gateway reports it. For attribution UI. */
    val createdByName: String? = null,
) {
    val isSynced: Boolean get() = syncStatus == SyncStatus.SYNCED

    /** Marks the row as locally dirty again after an edit. */
    fun touched(by: String?, at: Long = System.currentTimeMillis()): SyncMeta =
        copy(syncStatus = SyncStatus.PENDING, updatedAt = at, updatedBy = by)
}
