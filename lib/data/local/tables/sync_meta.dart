import 'package:drift/drift.dart';

import '../converters.dart';

/// The columns every synchronised row carries.
///
/// Mixed into each domain table so the sync contract is defined once. drift has
/// no embedded-object concept, which is an improvement here: these land as flat
/// columns with no prefix, so a DAO can say `where deleted equals false`
/// directly.
///
/// Three rules travel with this mixin and are enforced by convention plus tests
/// rather than by the type system, so they are worth restating:
///
/// 1. **[version], [serverSeq] and [serverUpdatedAt] are server-owned.** A local
///    edit carries them forward untouched. Resetting [version] makes the next
///    upload conflict with itself, because the server compares what you send
///    against what it stored.
/// 2. **There is no `dirty` flag.** Dirtiness *is* `syncStatus != synced`. A
///    second boolean could disagree with the status and there would be no rule
///    for which one wins.
/// 3. **[createdAt] and [updatedAt] are device clocks, for display and local
///    ordering only.** They are never used to resolve a conflict -- see
///    constraint #3 in CLAUDE.md, and [serverUpdatedAt] for the authoritative
///    time.
mixin SyncMetaColumns on Table {
  /// Optimistic-concurrency version, assigned by the server.
  ///
  /// Sent back as `baseVersion` so the server can reject a write built on a
  /// stale read. Zero means the row has never been accepted by the server.
  IntColumn get version => integer().withDefault(const Constant(0))();

  /// The server's global change sequence for this row, and the download cursor.
  ///
  /// Null until the server has seen the row. Also the tombstone-purge key: a
  /// deleted row may only be removed once its [serverSeq] is below the
  /// slowest device's cursor.
  IntColumn get serverSeq => integer().nullable()();

  /// When the server last wrote this row, ISO-8601. Authoritative, unlike
  /// [updatedAt].
  TextColumn get serverUpdatedAt => text().nullable()();

  /// Whether this row matches the server.
  ///
  /// Also answers §98's `metadataSyncStatus`; see constraint #7.
  TextColumn get syncStatus => text().map(syncStatusConverter)();

  /// Soft-delete flag (§36). The row stays so the removal propagates.
  BoolColumn get deleted => boolean().withDefault(const Constant(false))();

  /// When the row was soft-deleted, epoch milliseconds.
  IntColumn get deletedAt => integer().nullable()();

  /// Who soft-deleted the row.
  TextColumn get deletedBy => text().nullable()();

  /// When the row was created on the originating device, epoch milliseconds.
  IntColumn get createdAt => integer()();

  /// When the row was last edited locally, epoch milliseconds.
  ///
  /// Load-bearing for one specific rule: the uploader captures this at send
  /// time and only marks the row synced if it still matches, so an edit made
  /// while the request was in flight is not silently discarded.
  IntColumn get updatedAt => integer()();

  /// Author's identity, stamped by the server where one is available.
  TextColumn get createdBy => text().nullable()();

  /// Who last edited the row.
  TextColumn get updatedBy => text().nullable()();

  /// The author's display name, denormalised so a record stays attributable
  /// without a join and without having downloaded the users table.
  TextColumn get createdByName => text().nullable()();
}
