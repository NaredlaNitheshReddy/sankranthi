/// The contract for every enum that is stored or sent to the server.
///
/// Each value carries an explicit [wire] string. That string -- not the Dart
/// identifier -- is what lands in SQLite and in the sheet, which is the whole
/// point: `ExpenseCategory.shedRepair` must persist as `shed_repair`, because
/// that is the column value the gateway and every other device agree on.
///
/// Relying on the identifier instead (drift's `textEnum`, or `.name`) gives you
/// two representations of the same fact. Queries written against one silently
/// never match rows written with the other, and renaming a Dart constant --
/// normally a safe refactor -- becomes a data migration. See CLAUDE.md.
library;

/// Implemented by every persisted or transmitted enum.
abstract interface class WireEnum {
  /// The stable stored form: lowercase, underscore-separated, never renamed
  /// without a migration.
  String get wire;
}

/// Finds the value in [values] whose [WireEnum.wire] equals [wire], or null.
///
/// A linear scan is deliberate: these enums have a handful of values each, and
/// a scan avoids building and holding a lookup map per enum for no measurable
/// gain.
T? wireEnumOrNull<T extends WireEnum>(List<T> values, String? wire) {
  if (wire == null) {
    return null;
  }
  for (final T value in values) {
    if (value.wire == wire) {
      return value;
    }
  }
  return null;
}

/// Finds the value in [values] matching [wire], or throws [UnknownWireValue].
T wireEnumOf<T extends WireEnum>(List<T> values, String wire) {
  final T? found = wireEnumOrNull<T>(values, wire);
  if (found == null) {
    throw UnknownWireValue(T.toString(), wire);
  }
  return found;
}

/// A stored or received wire string that matches no known enum value.
///
/// Usually means a newer build wrote a value this one does not understand.
class UnknownWireValue implements Exception {
  const UnknownWireValue(this.enumName, this.wire);

  final String enumName;
  final String wire;

  @override
  String toString() => 'UnknownWireValue: "$wire" is not a valid $enumName';
}
