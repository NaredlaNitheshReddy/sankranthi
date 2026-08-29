/// Enums for the ledger, stock and livestock domains.
///
/// Every value carries an explicit [WireEnum.wire] string; see
/// [lib/core/wire_enum.dart] for why the Dart identifier is never the stored
/// form.
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

/// What a maintenance expense was for (§27, §46).
enum ExpenseCategory implements WireEnum {
  feed('feed', 'Feed'),
  veterinary('veterinary', 'Veterinary'),
  labour('labour', 'Labour'),
  transport('transport', 'Transport'),
  shedRepair('shed_repair', 'Shed repair'),
  utilities('utilities', 'Utilities'),
  other('other', 'Other');

  const ExpenseCategory(this.wire, this.label);

  @override
  final String wire;

  final String label;

  static ExpenseCategory? fromWire(String? wire) =>
      wireEnumOrNull(ExpenseCategory.values, wire);
}

/// How a stock transaction moves the balance (§32).
enum StockTxnType implements WireEnum {
  purchase('purchase', 'Purchase', 1),
  consumption('consumption', 'Consumption', -1),
  adjustment('adjustment', 'Adjustment', 0),
  transfer('transfer', 'Transfer', 0);

  const StockTxnType(this.wire, this.label, this._sign);

  @override
  final String wire;

  final String label;

  /// +1 adds to the balance, -1 subtracts, 0 means the row carries its own sign.
  final int _sign;

  /// Whether the user must choose a direction for this type.
  ///
  /// An adjustment can correct upward or downward and a transfer can be in or
  /// out, so the UI asks; a purchase and a consumption cannot be anything but
  /// their own direction.
  bool get needsExplicitDirection => _sign == 0;

  /// Applies this type's direction to a positive [quantityMilli].
  ///
  /// For [adjustment] and [transfer] the caller has already signed the value,
  /// so it passes through unchanged.
  int signedQuantity(int quantityMilli) =>
      _sign == 0 ? quantityMilli : _sign * quantityMilli.abs();

  static StockTxnType? fromWire(String? wire) =>
      wireEnumOrNull(StockTxnType.values, wire);
}

/// The unit a stock item is measured in (§32).
///
/// [decimals] is how many places to show: a third of a sack is meaningless, so
/// countable units display none, while weights and volumes show up to the
/// stored resolution of thousandths.
enum StockUnit implements WireEnum {
  kilogram('kilogram', 'kg', 3),
  gram('gram', 'g', 0),
  litre('litre', 'L', 3),
  millilitre('millilitre', 'mL', 0),
  piece('piece', 'pcs', 0),
  bag('bag', 'bags', 0),
  bundle('bundle', 'bundles', 0),
  dose('dose', 'doses', 0);

  const StockUnit(this.wire, this.symbol, this.decimals);

  @override
  final String wire;

  /// The short form shown next to a quantity.
  final String symbol;

  /// Decimal places to display; see [Quantity.format].
  final int decimals;

  static StockUnit? fromWire(String? wire) =>
      wireEnumOrNull(StockUnit.values, wire);
}

/// Why a livestock count changed (§33, §44).
///
/// The count domain records signed *deltas* with a reason rather than §33's
/// `previousCount`/`newCount` snapshots, so two devices counting offline sum
/// correctly instead of overwriting each other. See CLAUDE.md.
enum CountReason implements WireEnum {
  birth('birth', 'Birth', 1),
  death('death', 'Death', -1),
  purchase('purchase', 'Purchased', 1),
  sale('sale', 'Sold', -1),
  transferIn('transfer_in', 'Transferred in', 1),
  transferOut('transfer_out', 'Transferred out', -1),
  correction('correction', 'Correction', 0);

  const CountReason(this.wire, this.label, this._sign);

  @override
  final String wire;

  final String label;

  final int _sign;

  /// Whether the herd grows (+1), shrinks (-1), or either ([correction]).
  int get sign => _sign;

  /// A correction can go either way, so its delta is computed from the count
  /// the user typed rather than implied by the reason.
  bool get needsExplicitDirection => _sign == 0;

  /// Whether this reason should require a note.
  ///
  /// A correction is the one reason that does not explain itself -- "the count
  /// was wrong" is not a reason -- so §44's exact-count action demands one.
  bool get requiresNote => this == CountReason.correction;

  static CountReason? fromWire(String? wire) =>
      wireEnumOrNull(CountReason.values, wire);
}
