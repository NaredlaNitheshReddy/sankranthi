/// Enums describing synchronisation state (§16, §18, §24, §35, §98).
library;

import '../core/wire_enum.dart';

/// Whether a row matches the server (§16).
///
/// This single column also answers §98's `metadataSyncStatus`: "does this row's
/// metadata match the server" is exactly what it means. Constraint #7 collapses
/// the two rather than keeping columns that can disagree with no rule for which
/// wins.
///
/// **There is no `dirty` flag anywhere.** Dirtiness *is* `!= synced`.
///
/// The unrecognised-value fallback for this enum is [pending], and the reasoning
/// matters: an extra upload is deduplicated by `opId` and costs nothing, while
/// defaulting to [synced] would mark a dirty row clean and the record would
/// never reach the other partners.
enum SyncStatus implements WireEnum {
  /// Local changes are waiting to go up. The state a new row starts in.
  pending('pending'),

  /// Matches the server as of the last exchange.
  synced('synced'),

  /// Upload was rejected permanently; needs a human.
  failed('failed'),

  /// The server had a newer version; both payloads are kept for resolution.
  conflicted('conflicted');

  const SyncStatus(this.wire);

  @override
  final String wire;

  /// Whether this row has nothing left to send.
  bool get isSynced => this == SyncStatus.synced;

  /// Whether the user should be shown something about this row.
  bool get needsAttention =>
      this == SyncStatus.failed || this == SyncStatus.conflicted;

  static SyncStatus? fromWire(String? wire) =>
      wireEnumOrNull(SyncStatus.values, wire);
}

/// Where a receipt's image bytes have got to (§24).
///
/// §24 draws a seven-state machine; four of those states are about the *binary*
/// and belong here, while `METADATA_PENDING` and `SYNCED` are about the row and
/// are carried by [SyncStatus]. So §24's machine is expressed as a pair:
///
/// | §24 state         | uploadStatus | syncStatus |
/// | ----------------- | ------------ | ---------- |
/// | LOCAL_ONLY        | pending      | pending    |
/// | PENDING_UPLOAD    | pending      | pending    |
/// | UPLOADING         | uploading    | pending    |
/// | FAILED            | failed       | pending    |
/// | UPLOADED          | uploaded     | pending    |
/// | METADATA_PENDING  | uploaded     | pending    |
/// | SYNCED            | uploaded     | synced     |
///
/// The pairing is what makes §25 a queryable invariant: `driveFileId != null`
/// means the bytes are safe in Drive, so only the metadata may retry and the
/// upload must never repeat.
enum UploadStatus implements WireEnum {
  /// Bytes are on this device only.
  pending('pending'),

  /// An upload is in flight.
  uploading('uploading'),

  /// Bytes are in Drive. Never upload again -- see §25.
  uploaded('uploaded'),

  /// The last attempt failed; it will be retried.
  failed('failed');

  const UploadStatus(this.wire);

  @override
  final String wire;

  static UploadStatus? fromWire(String? wire) =>
      wireEnumOrNull(UploadStatus.values, wire);
}

/// What an outbox operation is asking the server to do (§18).
///
/// §18 lists CREATE, UPDATE, DELETE, RESTORE, UPLOAD_RECEIPT and
/// SYNC_RECEIPT_METADATA. This collapses CREATE and UPDATE into [upsert],
/// because the uploader sends current row state at send time and the server
/// upserts by UUID -- so which one it "was" is not a distinction either side
/// acts on.
///
/// [delete] and [restore] survive as separate types even though a soft delete
/// is mechanically just another row edit, because §90 gates them on their own
/// permissions and the server has to know which one it is being asked for.
///
/// **Still exactly one operation per row at a time.** Operations are coalesced
/// per entity; when a row is deleted or restored, its queued operation's *type*
/// changes rather than a second operation being added. That is what CLAUDE.md
/// means by "no separate DELETE operation to order against an UPDATE": there is
/// never an ordering question, because there is never a pair.
enum OpType implements WireEnum {
  /// Create or update, by UUID.
  upsert('upsert'),

  /// Soft delete: set `deleted`, keep the row so the removal propagates.
  delete('delete'),

  /// Undo a soft delete (§115). Never a local flag flip.
  restore('restore'),

  /// Send receipt bytes. Not part of the `sync` action -- receipts travel their
  /// own path so a failed photo cannot hold up the ledger (§23).
  uploadReceipt('upload_receipt');

  const OpType(this.wire);

  @override
  final String wire;

  /// Whether this operation carries a row payload through the `sync` action.
  bool get isRowOperation => this != OpType.uploadReceipt;

  static OpType? fromWire(String? wire) =>
      wireEnumOrNull(OpType.values, wire);
}

/// The lifecycle of a queued operation.
///
/// Presence in the outbox already means "there is work to do"; this distinguishes
/// work that can be attempted from work that must not be retried blindly.
enum OpStatus implements WireEnum {
  /// Ready to attempt, subject to `nextAttemptAt`.
  queued('queued'),

  /// Sent, awaiting a response. On restart this returns to [queued] -- the
  /// `opId` makes the retry safe.
  inFlight('in_flight'),

  /// Rejected permanently, or past the attempt ceiling.
  ///
  /// Skipped by the drain so it cannot block the queue, and **never deleted**:
  /// §103 is explicit that discarding a failed operation is data loss.
  failedPermanent('failed_permanent');

  const OpStatus(this.wire);

  @override
  final String wire;

  static OpStatus? fromWire(String? wire) =>
      wireEnumOrNull(OpStatus.values, wire);
}

/// What an audit entry records (§35).
///
/// §35 names entity-specific actions (`CREATE_EXPENSE`, `UPDATE_EXPENSE`, ...).
/// That does not scale to eleven domains -- it would be forty-odd constants that
/// have to be extended for every new table -- so the action is kept orthogonal
/// and the entity is a separate column on the audit row. `CREATE_EXPENSE`
/// becomes `(create, expense)`, which is the same fact and composes.
///
/// Audit rows are written by the **server**, inside its lock, using server time
/// (constraint #8). The client mirrors them read-only. Only the local-only
/// actions below are ever client-authored, because they never reach the server.
enum AuditAction implements WireEnum {
  create('create'),
  update('update'),
  delete('delete'),
  restore('restore'),

  /// Access granted, revoked, or permissions changed (§88).
  accessChange('access_change'),

  /// The receipt storage configuration was switched (§50).
  storageChange('storage_change'),

  /// Local-only: someone signed in on this device.
  signIn('sign_in'),

  /// Local-only: sync failed here. Never leaves the device.
  syncFailure('sync_failure');

  const AuditAction(this.wire);

  @override
  final String wire;

  /// Whether this action is recorded locally rather than by the server.
  bool get isLocalOnly =>
      this == AuditAction.signIn || this == AuditAction.syncFailure;

  static AuditAction? fromWire(String? wire) =>
      wireEnumOrNull(AuditAction.values, wire);
}
