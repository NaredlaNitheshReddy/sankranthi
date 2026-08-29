/// Shared numeric text handling for [Money] and [Quantity].
///
/// Both are integer-scaled values (paise, thousandths of a unit) parsed from
/// and rendered to text the same way, so the digit handling lives here rather
/// than being written twice and drifting.
library;

/// Code units that carry no numeric meaning and are silently ignored on input:
/// rupee sign, comma, space, non-breaking space, narrow non-breaking space.
const Set<int> _ignorable = <int>{0x20B9, 0x2C, 0x20, 0xA0, 0x202F};

/// Strips decoration people actually type or paste, leaving digits, a sign and
/// decimal points for the caller to validate.
String stripNumericDecoration(String input) {
  final StringBuffer out = StringBuffer();
  for (final int unit in input.trim().codeUnits) {
    if (_ignorable.contains(unit)) {
      continue;
    }
    out.writeCharCode(unit);
  }
  return out.toString();
}

/// Whether every code unit in [value] is an ASCII digit. An empty string is
/// vacuously true, which is what the callers want for an absent fraction part.
bool isAsciiDigits(String value) {
  for (final int unit in value.codeUnits) {
    if (unit < 0x30 || unit > 0x39) {
      return false;
    }
  }
  return true;
}

/// Indian digit grouping: the last three digits, then groups of two.
///
/// 12345678 becomes `1,23,45,678`, not `12,345,678`. Getting this wrong makes
/// every figure in the app look subtly foreign to the people reading it.
String groupIndian(String digits) {
  if (digits.length <= 3) {
    return digits;
  }
  final String last3 = digits.substring(digits.length - 3);
  String rest = digits.substring(0, digits.length - 3);
  final List<String> groups = <String>[];
  while (rest.length > 2) {
    groups.insert(0, rest.substring(rest.length - 2));
    rest = rest.substring(0, rest.length - 2);
  }
  if (rest.isNotEmpty) {
    groups.insert(0, rest);
  }
  return '${groups.join(',')},$last3';
}
