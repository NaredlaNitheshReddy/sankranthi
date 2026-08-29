/// Quantity handling for stock and livestock.
///
/// **Quantities are an `int` count of thousandths of a unit, never a `double`.**
/// The reason is stronger here than for money: REQUIREMENTS §43 makes a stock
/// balance a *derived* figure, summed over its whole transaction history on
/// every read. Feed arrives in 12.5 kg lots, and 12.5 is not representable in
/// binary floating point, so the error would compound silently across a few
/// hundred rows until two devices disagreed about how much feed is in the shed
/// and neither could be shown to be wrong.
///
/// Three decimal places is the resolution: a gram of feed, a millilitre of
/// medicine. See CLAUDE.md.
library;

import 'numeric_text.dart';

/// Thousandths per whole unit.
const int milliPerUnit = 1000;

/// The largest quantity accepted: a billion units, in thousandths.
const int maxQuantityMilli = 1000000000000;

/// Thrown when text cannot be read as a quantity.
class QuantityFormatException implements Exception {
  const QuantityFormatException(this.reason, this.input);

  final QuantityProblem reason;
  final String input;

  @override
  String toString() => 'QuantityFormatException($reason, "$input")';
}

/// Why a quantity was rejected.
enum QuantityProblem {
  /// Nothing to parse.
  empty,

  /// A character that is not a digit, a single decimal point, or ignorable
  /// decoration.
  unexpectedCharacter,

  /// Negative input. As with money, direction is carried by the record -- a
  /// purchase versus a consumption -- not by the sign of what the user types.
  /// An adjustment picks its direction explicitly in the UI.
  negative,

  /// More than one decimal point.
  multiplePoints,

  /// Finer than a thousandth. Refused rather than rounded, for the same reason
  /// money refuses sub-paisa input.
  subMilliPrecision,

  /// Larger than can be represented safely.
  tooLarge,
}

abstract final class Quantity {
  /// Parses [input] into thousandths of a unit, or throws
  /// [QuantityFormatException].
  static int parseToMilli(String input) {
    final String cleaned = stripNumericDecoration(input);
    if (cleaned.isEmpty) {
      throw QuantityFormatException(QuantityProblem.empty, input);
    }
    if (cleaned.startsWith('-')) {
      throw QuantityFormatException(QuantityProblem.negative, input);
    }

    final List<String> parts = cleaned.split('.');
    if (parts.length > 2) {
      throw QuantityFormatException(QuantityProblem.multiplePoints, input);
    }

    final String whole = parts.first;
    final String fraction = parts.length == 2 ? parts[1] : '';

    if (!isAsciiDigits(whole) || !isAsciiDigits(fraction)) {
      throw QuantityFormatException(
        QuantityProblem.unexpectedCharacter,
        input,
      );
    }
    if (whole.isEmpty && fraction.isEmpty) {
      throw QuantityFormatException(QuantityProblem.empty, input);
    }
    if (fraction.length > 3) {
      throw QuantityFormatException(QuantityProblem.subMilliPrecision, input);
    }

    // Right-pad so "12.5" is 12500 thousandths, not 125.
    final String milli = fraction.padRight(3, '0');
    final int? units = int.tryParse(whole.isEmpty ? '0' : whole);
    if (units == null || units > maxQuantityMilli ~/ milliPerUnit) {
      throw QuantityFormatException(QuantityProblem.tooLarge, input);
    }

    final int total =
        units * milliPerUnit + int.parse(milli.isEmpty ? '0' : milli);
    if (total > maxQuantityMilli) {
      throw QuantityFormatException(QuantityProblem.tooLarge, input);
    }
    return total;
  }

  /// Parses [input], or returns null if it is not a valid quantity.
  static int? tryParseToMilli(String input) {
    try {
      return parseToMilli(input);
    } on QuantityFormatException {
      return null;
    }
  }

  /// Formats [milli] for display, trimming trailing zeros so a whole number of
  /// units reads as `12` rather than `12.000`.
  ///
  /// [decimals] caps the places shown, for units where a fraction is
  /// meaningless -- there is no such thing as a third of a sack. Values are
  /// truncated toward zero rather than rounded, so a displayed figure never
  /// claims more stock than the balance actually holds.
  ///
  /// Handles a negative [milli] even though [parseToMilli] refuses negative
  /// input: a stock movement or an adjustment is legitimately negative.
  static String format(int milli, {int decimals = 3, bool signed = false}) {
    assert(
      decimals >= 0 && decimals <= 3,
      'decimals must be 0..3; the stored resolution is thousandths',
    );

    final bool negative = milli < 0;
    int absolute = negative ? -milli : milli;

    // Truncate to the requested resolution before rendering.
    if (decimals < 3) {
      final int divisor = <int>[1000, 100, 10][decimals];
      absolute = (absolute ~/ divisor) * divisor;
    }

    final String units = (absolute ~/ milliPerUnit).toString();
    String fraction = (absolute % milliPerUnit)
        .toString()
        .padLeft(3, '0')
        .substring(0, decimals);
    while (fraction.isNotEmpty && fraction.endsWith('0')) {
      fraction = fraction.substring(0, fraction.length - 1);
    }

    final String sign = negative
        ? '-'
        : signed && milli > 0
        ? '+'
        : '';
    final String grouped = groupIndian(units);
    return fraction.isEmpty ? '$sign$grouped' : '$sign$grouped.$fraction';
  }

  /// Renders [milli] for a text field: plain digits, no grouping and no sign,
  /// so it round-trips through [parseToMilli] unchanged.
  static String toEditable(int milli) {
    final int absolute = milli < 0 ? -milli : milli;
    String fraction = (absolute % milliPerUnit).toString().padLeft(3, '0');
    while (fraction.isNotEmpty && fraction.endsWith('0')) {
      fraction = fraction.substring(0, fraction.length - 1);
    }
    final String units = (absolute ~/ milliPerUnit).toString();
    return fraction.isEmpty ? units : '$units.$fraction';
  }
}
