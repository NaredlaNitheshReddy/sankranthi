/// Enums for the ledger domain that must remain code.
///
/// ## Why so few
///
/// Taxonomies the organisation will want to extend -- expense categories, stock
/// units, stock movement types, count reasons, livestock categories -- are
/// **SQLite reference tables**, not enums, so an admin can add "Insurance" or
/// "Wastage" without shipping a release. See
/// `data/local/tables/reference_tables.dart`.
///
/// What stays here is what cannot be data:
///
/// * [TradeKind] -- the set is closed. A trade either brings animals in or sends
///   them out; there is no third possibility to extend to, and the sign of the
///   money follows from it directly.
///
/// The same test applies elsewhere: [SyncStatus], [OpType], [OpStatus],
/// [UploadStatus] and [AccessStatus] are the sync engine's and the session
/// gate's own state machines -- code branches exhaustively on them, so a row
/// added to a `sync_statuses` table would have no code path and would do
/// nothing. [Permission] is the same argument from the other direction: a
/// permission no code checks grants nothing, so offering it in an admin screen
/// would be worse than not offering it. Roles, by contrast, *are* data.
library;

import '../core/wire_enum.dart';

/// Whether a livestock trade brought animals in or sent them out (§27).
enum TradeKind implements WireEnum {
  buy('buy', 'Purchase'),
  sell('sell', 'Sale');

  const TradeKind(this.wire, this.label);

  @override
  final String wire;

  /// How this reads to a user.
  final String label;

  /// Money leaves on a purchase and arrives on a sale.
  ///
  /// The stored amount is always a positive magnitude -- direction lives here,
  /// in the record's kind, which is why `Money.parseToMinor` refuses a negative.
  int signedMinor(int amountMinor) =>
      this == TradeKind.sell ? amountMinor : -amountMinor;

  static TradeKind? fromWire(String? wire) =>
      wireEnumOrNull(TradeKind.values, wire);
}
