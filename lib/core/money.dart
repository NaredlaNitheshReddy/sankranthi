/// Money handling for Sankranthi.
///
/// **Money is an `int` count of paise, never a `double`.** REQUIREMENTS §27
/// and §120 make amounts integral; a binary floating point type cannot
/// represent 0.01 exactly, and these values are summed into reports and netted
/// against each other, so the error would accumulate somewhere no one is
/// looking.
///
/// All conversion goes through this class. See CLAUDE.md.
library;

import 'numeric_text.dart';

/// Thrown when text cannot be read as an amount.
///
/// Carries [reason] so the UI can explain what is wrong without inspecting the
/// message string, per the sealed-failure convention in CLAUDE.md.
class MoneyFormatException implements Exception {
  const MoneyFormatException(this.reason, this.input);

  final MoneyProblem reason;
  final String input;

  @override
  String toString() => 'MoneyFormatException($reason, "$input")';
}

/// Why an amount was rejected.
enum MoneyProblem {
  /// Nothing to parse.
  empty,

  /// A character that is not a digit, a single decimal point, or ignorable
  /// decoration.
  unexpectedCharacter,

  /// Negative input. Amounts are magnitudes; direction is carried by the record
  /// (a purchase versus a sale), not by the sign of the number the user types.
  negative,

  /// More than one decimal point.
  multiplePoints,

  /// More precision than a paisa. Refused rather than rounded: silently turning
  /// 10.005 into 10.01 loses a rupee across two hundred rows and nobody can
  /// explain the discrepancy afterwards.
  subPaisaPrecision,

  /// Larger than can be represented safely.
  tooLarge,
}

/// The largest amount accepted: ten billion rupees in paise.
///
/// Far above anything this organisation will record, and far below the 2^63
/// limit, so intermediate sums in reports cannot overflow either.
const int maxAmountMinor = 1000000000000;

abstract final class Money {
  /// Parses [input] into paise, or throws [MoneyFormatException].
  ///
  /// Tolerates the decoration people actually type or paste: a rupee sign,
  /// thousands separators, and surrounding whitespace.
  static int parseToMinor(String input) {
    final String cleaned = stripNumericDecoration(input);
    if (cleaned.isEmpty) {
      throw MoneyFormatException(MoneyProblem.empty, input);
    }
    if (cleaned.startsWith('-')) {
      throw MoneyFormatException(MoneyProblem.negative, input);
    }

    final List<String> parts = cleaned.split('.');
    if (parts.length > 2) {
      throw MoneyFormatException(MoneyProblem.multiplePoints, input);
    }

    final String whole = parts.first;
    final String fraction = parts.length == 2 ? parts[1] : '';

    if (!isAsciiDigits(whole) || !isAsciiDigits(fraction)) {
      throw MoneyFormatException(MoneyProblem.unexpectedCharacter, input);
    }
    if (whole.isEmpty && fraction.isEmpty) {
      throw MoneyFormatException(MoneyProblem.empty, input);
    }
    if (fraction.length > 2) {
      throw MoneyFormatException(MoneyProblem.subPaisaPrecision, input);
    }

    // Right-pad so "1.5" is 150 paise, not 15.
    final String paise = fraction.padRight(2, '0');
    final int? rupees = int.tryParse(whole.isEmpty ? '0' : whole);
    if (rupees == null || rupees > maxAmountMinor ~/ 100) {
      throw MoneyFormatException(MoneyProblem.tooLarge, input);
    }

    final int minor = rupees * 100 + int.parse(paise.isEmpty ? '0' : paise);
    if (minor > maxAmountMinor) {
      throw MoneyFormatException(MoneyProblem.tooLarge, input);
    }
    return minor;
  }

  /// Parses [input] into paise, or returns null if it is not a valid amount.
  static int? tryParseToMinor(String input) {
    try {
      return parseToMinor(input);
    } on MoneyFormatException {
      return null;
    }
  }

  /// Formats [minor] paise for display, e.g. `₹12,34,567.89`.
  ///
  /// Handles a negative [minor] even though [parseToMinor] refuses negative
  /// input: a user cannot type a negative amount, but a net position or a
  /// margin is computed and can legitimately be below zero.
  ///
  /// When [signed] is true a positive value is prefixed with `+`, for figures
  /// where the direction is the point.
  static String format(int minor, {bool signed = false, bool symbol = true}) {
    final bool negative = minor < 0;
    final int absolute = negative ? -minor : minor;
    final String digits = (absolute ~/ 100).toString();
    final String paise = (absolute % 100).toString().padLeft(2, '0');

    final String sign = negative
        ? '-'
        : signed && minor > 0
        ? '+'
        : '';
    return '$sign${symbol ? '₹' : ''}${groupIndian(digits)}.$paise';
  }

  /// Renders [minor] for a text field: plain digits and a point, no grouping
  /// and no symbol, so it round-trips through [parseToMinor] unchanged.
  static String toEditable(int minor) {
    final int absolute = minor < 0 ? -minor : minor;
    return '${absolute ~/ 100}.${(absolute % 100).toString().padLeft(2, '0')}';
  }

}
