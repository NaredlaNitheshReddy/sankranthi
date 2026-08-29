package com.sankranthi.ledger.data.remote

import com.sankranthi.ledger.data.local.entity.ExpenseEntity
import com.sankranthi.ledger.data.local.entity.LivestockEntity

/**
 * The migration seam (§22, §28).
 *
 * This is the **only** interface that knows a backend exists. Nothing in `ui/`
 * may import an implementation of it, and no implementation type may appear in a
 * domain model. Swapping Google Sheets for Postgres later means writing one new
 * implementation and changing one line in `ServiceLocator`.
 *
 * The shape is deliberately sync-oriented rather than CRUD: one call uploads a
 * bounded batch and downloads everything newer than a cursor. That is what the
 * Apps Script gateway can do inside its six-minute execution ceiling, and it is
 * also efficient against a real database later.
 *
 * Implementations must be safe to call twice with the same [SyncRequest] — the
 * network can fail after the server committed but before the client saw the
 * response, and the retry must not duplicate anything.
 */
interface RemoteDataSource {

    /** Uploads pending operations and downloads changes past the cursor. */
    suspend fun sync(request: SyncRequest): SyncResponse

    /** Uploads one receipt image. Idempotent on `receiptId`. */
    suspend fun uploadReceipt(request: ReceiptUploadRequest): ReceiptUploadResult
}

data class SyncRequest(
    /** Short-lived Google ID token; the backend verifies it and applies the allowlist. */
    val idToken: String,
    val deviceId: String,
    /** Highest server sequence already applied locally. */
    val lastSeq: Long,
    val operations: List<OutboundOperation>,
)

/**
 * One row to push. [opId] is the idempotency key and is reused across retries;
 * [baseVersion] is the server version the edit was based on, so the backend can
 * reject a stale write instead of silently overwriting a newer one (§3.2).
 */
data class OutboundOperation(
    val opId: String,
    val entityType: String,
    val entityId: String,
    val opType: String,
    val baseVersion: Int,
    val livestock: LivestockEntity? = null,
    val expense: ExpenseEntity? = null,
)

data class SyncResponse(
    val profile: RemoteProfile?,
    val results: List<OperationResult>,
    val changes: RemoteChanges,
    val newSeq: Long,
    /** True when the backend truncated the change set and should be called again. */
    val hasMore: Boolean,
)

/** The backend's verdict on one pushed operation. */
data class OperationResult(
    val opId: String,
    val status: Status,
    val version: Int? = null,
    val seq: Long? = null,
    val message: String? = null,
) {
    enum class Status {
        /** Written. Adopt [version] and [seq]. */
        APPLIED,

        /** Already applied on an earlier attempt. Treat exactly like APPLIED. */
        DUPLICATE,

        /** Server row moved past [baseVersion]. Needs reconciliation. */
        CONFLICT,

        /** Refused — bad data or insufficient permission. Do not retry blindly. */
        REJECTED,
    }
}

data class RemoteChanges(
    val livestock: List<LivestockEntity> = emptyList(),
    val expenses: List<ExpenseEntity> = emptyList(),
)

/** Authorisation state as the backend sees it — never decided on the client (§14). */
data class RemoteProfile(
    val email: String,
    val displayName: String?,
    val role: String,
    val active: Boolean,
)

data class ReceiptUploadRequest(
    val idToken: String,
    val receiptId: String,
    val mimeType: String,
    val bytes: ByteArray,
) {
    // ByteArray in a data class: identity equality would be wrong and structural
    // equality would be expensive, and neither is ever needed here.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

data class ReceiptUploadResult(val driveFileId: String)

/** Distinguishes "try again later" from "this will never work". */
class RemoteTransientException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class RemotePermanentException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
