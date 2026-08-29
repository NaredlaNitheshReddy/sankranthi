/// Validation and arithmetic for behaviour that lives in reference tables.
///
/// ## Why this file exists
///
/// When a movement type was an enum, the compiler guaranteed its sign was one
/// of three hand-written cases. Now the sign is a column, so nothing stops a
/// bad sync, a hand-edited sheet or a future bug from storing `sign = 7`.
///
/// That is the price of extensibility, and it is payable -- but only if the
/// values are checked at the boundary instead of trusted. Everything read from
/// a reference table passes through here first.
///
/// **Invalid values are rejected, never clamped.** Clamping `sign = 7` to 1
/// would invent a direction and silently misreport a stock balance; the whole
/// reason quantities are integers is to avoid exactly that class of quiet
/// wrongness. Failing loudly means someone fixes the row.
library;

/// Thrown when a reference row cannot be trusted.
class ReferenceDataException implements Exception {
  const ReferenceDataException(this.problem, this.subject, this.value);

  final ReferenceProblem problem;

  /// What was being validated, e.g. `stock_txn_type.purchase`.
  final String subject;

  /// The offending value, for the message.
  final Object? value;

  @override
  String toString() =>
      'ReferenceDataException($problem) on $subject: got $value';
}

/// What is wrong with a reference row.
enum ReferenceProblem {
  /// A sign outside {-1, 0, 1}.
  invalidSign,

  /// Display precision outside 0..3, the stored resolution.
  invalidDecimals,

  /// A key that is not a lowercase slug, so it would not round-trip as a wire
  /// value.
  invalidKey,

  /// An empty label, which would render as a blank picker entry.
  emptyLabel,
}

/// The only signs a movement may have.
const Set<int> validSigns = <int>{-1, 0, 1};

/// The stored quantity resolution, in decimal places.
const int maxQuantityDecimals = 3;

/// Wire-value shape, matching the convention every enum already follows.
final RegExp _slugShape = RegExp(r'^[a-z][a-z0-9_]*$');

abstract final class ReferenceRules {
  /// Whether [key] is usable as a slug primary key and wire value.
  static bool isValidKey(String key) =>
      key.isNotEmpty && key.length <= 64 && _slugShape.hasMatch(key);

  /// Throws unless [sign] is -1, 0 or 1.
  static int checkSign(int sign, String subject) {
    if (!validSigns.contains(sign)) {
      throw ReferenceDataException(
        ReferenceProblem.invalidSign,
        subject,
        sign,
      );
    }
    return sign;
  }

  /// Throws unless [decimals] is within the stored resolution.
  static int checkDecimals(int decimals, String subject) {
    if (decimals < 0 || decimals > maxQuantityDecimals) {
      throw ReferenceDataException(
        ReferenceProblem.invalidDecimals,
        subject,
        decimals,
      );
    }
    return decimals;
  }

  /// Throws unless [key] is a valid slug.
  static String checkKey(String key, String subject) {
    if (!isValidKey(key)) {
      throw ReferenceDataException(ReferenceProblem.invalidKey, subject, key);
    }
    return key;
  }

  /// Throws unless [label] has visible content.
  static String checkLabel(String label, String subject) {
    if (label.trim().isEmpty) {
      throw ReferenceDataException(
        ReferenceProblem.emptyLabel,
        subject,
        label,
      );
    }
    return label;
  }

  /// Applies a reference row's [sign] to a quantity or head count.
  ///
  /// A sign of 0 means the row already carries its own direction because the
  /// user chose one, so [value] passes through untouched. Otherwise the
  /// magnitude is taken and the sign applied, so a caller cannot double-negate
  /// a consumption by passing an already-negative number.
  ///
  /// Replaces what `StockTxnType.signedQuantity` and `CountReason.sign` did
  /// when they were enums.
  static int applySign({
    required int sign,
    required int value,
    required String subject,
  }) {
    checkSign(sign, subject);
    if (sign == 0) {
      return value;
    }
    return sign * value.abs();
  }

  /// Validates every field of a reference row at once.
  ///
  /// Called when reference data is read or downloaded, so a bad row is caught
  /// where it enters rather than where it eventually produces a wrong total.
  static void checkRow({
    required String key,
    required String label,
    required String subject,
    int? sign,
    int? decimals,
  }) {
    checkKey(key, subject);
    checkLabel(label, subject);
    if (sign != null) {
      checkSign(sign, subject);
    }
    if (decimals != null) {
      checkDecimals(decimals, subject);
    }
  }
}
