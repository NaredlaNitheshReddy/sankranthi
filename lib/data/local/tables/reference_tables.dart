
/// User-extensible reference data.
///
/// These were enums. They are tables so the organisation can add an expense
/// category, a stock unit or a movement reason without a release -- §90's
/// "permissions should be extensible" reasoning applied to the taxonomies the
/// user actually sees.
///
/// ## Why a slug primary key, not a UUID
///
/// §12 requires a UUID on every synchronisable entity, and every *business*
/// record here obeys that. Reference rows deliberately do not: their primary
/// key is a stable lowercase slug (`feed`, `kilogram`, `transfer_in`).
///
/// Two reasons, and the second is the important one:
///
/// 1. The sheet stays readable. An Expenses row says `feed`, not
///    `550e8400-e29b-41d4-a716-446655440000`, so a partner reading the
///    spreadsheet can still tell what they are looking at.
/// 2. **Concurrent creation converges instead of duplicating.** If two admins
///    both add "Insurance" while offline, a UUID key gives you two categories
///    that mean the same thing and split every report between them, with no way
///    to tell they should be one. A slug key makes both writes the same upsert,
///    and they merge. For a closed taxonomy that is the correct outcome, not a
///    collision to be avoided.
///
/// The slug is also the wire value, so the stored form is unchanged from when
/// these were enums -- `shed_repair` is still `shed_repair`.
///
/// ## Behaviour as columns
///
/// Moving these to data means some behaviour moves with them: a unit's display
/// precision, a movement type's sign. Code can no longer prove it has handled
/// every case, so it must **validate** what it reads instead -- see
/// `referenceIntegrity` checks and their tests. A `sign` outside {-1, 0, 1} or
/// `decimals` outside 0..3 is corruption, and is rejected loudly rather than
/// clamped, because a wrong sign silently inverts a stock balance.
///
/// ## Deactivating, not deleting
///
/// [isActive] hides a row from pickers while leaving history intact. Deleting a
/// category that expenses still reference would orphan them; §36's soft-delete
/// reasoning applies to taxonomies too.
library;

import 'package:drift/drift.dart';

import 'sync_meta.dart';

/// What a maintenance expense was for (§27, §46). Was `ExpenseCategory`.
@DataClassName('ExpenseCategoryRow')
class ExpenseCategories extends Table with SyncMetaColumns {
  /// Stable slug, also the wire value. Never renamed without a migration.
  TextColumn get key => text().withLength(min: 1, max: 64)();

  /// What the user sees. Safe to rename freely, unlike [key].
  TextColumn get label => text().withLength(min: 1, max: 80)();

  /// Ordering in pickers; ties broken by [label].
  IntColumn get sortOrder => integer().withDefault(const Constant(100))();

  /// Whether this appears in pickers. Existing records keep referencing it.
  BoolColumn get isActive => boolean().withDefault(const Constant(true))();

  @override
  Set<Column<Object>> get primaryKey => <Column<Object>>{key};
}

/// The unit a stock item is measured in (§32). Was `StockUnit`.
@DataClassName('StockUnitRow')
class StockUnits extends Table with SyncMetaColumns {
  TextColumn get key => text().withLength(min: 1, max: 64)();

  TextColumn get label => text().withLength(min: 1, max: 80)();

  /// The short form shown next to a quantity, e.g. `kg`.
  TextColumn get symbol => text().withLength(min: 1, max: 16)();

  /// Decimal places to display, 0..3.
  ///
  /// Zero for countable units -- there is no such thing as a third of a sack.
  /// Three is the stored resolution; more cannot be represented, so a larger
  /// value is rejected rather than silently ignored.
  IntColumn get decimals => integer().withDefault(const Constant(0))();

  IntColumn get sortOrder => integer().withDefault(const Constant(100))();

  BoolColumn get isActive => boolean().withDefault(const Constant(true))();

  @override
  Set<Column<Object>> get primaryKey => <Column<Object>>{key};
}

/// How a stock movement affects the balance (§32). Was `StockTxnType`.
@DataClassName('StockTxnTypeRow')
class StockTxnTypes extends Table with SyncMetaColumns {
  TextColumn get key => text().withLength(min: 1, max: 64)();

  TextColumn get label => text().withLength(min: 1, max: 80)();

  /// Direction: +1 adds to the balance, -1 subtracts, 0 means the row carries
  /// its own sign because the user chose a direction.
  ///
  /// **The most safety-critical column in the reference data.** A stock balance
  /// is derived by summing signed quantities, so a wrong value here silently
  /// misreports how much is in the shed.
  IntColumn get sign => integer().withDefault(const Constant(0))();

  /// Whether the user is asked which way this movement goes.
  ///
  /// Stored rather than derived from `sign == 0` so a future type can be
  /// signed *and* ask for confirmation.
  BoolColumn get requiresDirection =>
      boolean().withDefault(const Constant(false))();

  IntColumn get sortOrder => integer().withDefault(const Constant(100))();

  BoolColumn get isActive => boolean().withDefault(const Constant(true))();

  @override
  Set<Column<Object>> get primaryKey => <Column<Object>>{key};
}

/// Why a livestock count changed (§33, §44). Was `CountReason`.
@DataClassName('CountReasonRow')
class CountReasons extends Table with SyncMetaColumns {
  TextColumn get key => text().withLength(min: 1, max: 64)();

  TextColumn get label => text().withLength(min: 1, max: 80)();

  /// Whether the herd grows (+1), shrinks (-1), or either (0).
  IntColumn get sign => integer().withDefault(const Constant(0))();

  /// Whether the user must explain themselves.
  ///
  /// True for a correction: "the count was wrong" does not say why, and §44's
  /// exact-count action is the one place a number changes without a real-world
  /// event behind it.
  BoolColumn get requiresNote =>
      boolean().withDefault(const Constant(false))();

  IntColumn get sortOrder => integer().withDefault(const Constant(100))();

  BoolColumn get isActive => boolean().withDefault(const Constant(true))();

  @override
  Set<Column<Object>> get primaryKey => <Column<Object>>{key};
}

/// A kind of animal the organisation counts (§33).
///
/// Already a table in the specification. Keyed by slug for the same convergence
/// reason as the rest: two admins adding "Buffalo" offline should end up with
/// one category, not two that split the herd.
@DataClassName('LivestockCategoryRow')
class LivestockCategories extends Table with SyncMetaColumns {
  TextColumn get key => text().withLength(min: 1, max: 64)();

  TextColumn get label => text().withLength(min: 1, max: 80)();

  IntColumn get sortOrder => integer().withDefault(const Constant(100))();

  BoolColumn get isActive => boolean().withDefault(const Constant(true))();

  @override
  Set<Column<Object>> get primaryKey => <Column<Object>>{key};
}
