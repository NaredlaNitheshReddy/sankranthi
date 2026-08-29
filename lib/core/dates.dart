/// Date handling for Sankranthi.
///
/// **Dates are ISO `yyyy-MM-dd` strings end to end** -- in Dart, in SQLite, and
/// in the sheet. Three reasons, all load-bearing:
///
/// 1. A business date has no time and no timezone. "Feed bought on 29 August"
///    is the same fact in every timezone, so storing an instant invites a
///    device in a different offset to render it as the 28th.
/// 2. `yyyy-MM-dd` sorts lexicographically in exactly chronological order,
///    which is why `ORDER BY occurredOn` and `WHERE occurredOn >= ?` work
///    directly on the stored text with no conversion and no function index.
/// 3. It matches the sheet's cell format, so a partner reading the spreadsheet
///    sees what the app shows.
///
/// Where a `DateTime` is unavoidable -- the Material date picker wants epoch
/// milliseconds -- this class uses **UTC midnight**. Using local midnight is
/// the classic defect: in a timezone behind UTC, `DateTime(2026, 8, 29)`
/// converted to millis and back through a UTC-based picker lands on the 28th.
///
/// See CLAUDE.md.
library;

/// Thrown when text is not a valid ISO date.
class DateFormatException implements Exception {
  const DateFormatException(this.input);

  final String input;

  @override
  String toString() => 'DateFormatException("$input")';
}

const List<String> _monthNames = <String>[
  'Jan',
  'Feb',
  'Mar',
  'Apr',
  'May',
  'Jun',
  'Jul',
  'Aug',
  'Sep',
  'Oct',
  'Nov',
  'Dec',
];

/// Exactly four digits, a dash, two digits, a dash, two digits. Deliberately
/// strict: `2026-8-9` is rejected rather than repaired, because a lenient
/// parser here would let unpadded text reach the database where it would sort
/// wrongly against padded rows.
final RegExp _isoShape = RegExp(r'^\d{4}-\d{2}-\d{2}$');

abstract final class Dates {
  /// Today, as an ISO date.
  ///
  /// [now] is injectable so tests are not time-dependent. The local date is
  /// correct here: "today" means today where the user is standing.
  static String today({DateTime? now}) {
    final DateTime moment = now ?? DateTime.now();
    return _render(moment.year, moment.month, moment.day);
  }

  /// Formats the calendar fields of [date] as an ISO date.
  ///
  /// Uses the year, month and day as given without converting between UTC and
  /// local, because those fields already are the date being recorded.
  static String format(DateTime date) => _render(date.year, date.month, date.day);

  /// Whether [iso] is a well-formed, real calendar date.
  static bool isValid(String iso) => tryParse(iso) != null;

  /// Parses [iso] to UTC midnight, or throws [DateFormatException].
  static DateTime parse(String iso) {
    final DateTime? parsed = tryParse(iso);
    if (parsed == null) {
      throw DateFormatException(iso);
    }
    return parsed;
  }

  /// Parses [iso] to UTC midnight, or returns null.
  ///
  /// Rejects impossible dates as well as malformed ones: `2026-02-30` would
  /// otherwise be normalised by [DateTime] into 2 March and quietly accepted.
  static DateTime? tryParse(String iso) {
    if (!_isoShape.hasMatch(iso)) {
      return null;
    }
    final int year = int.parse(iso.substring(0, 4));
    final int month = int.parse(iso.substring(5, 7));
    final int day = int.parse(iso.substring(8, 10));
    if (month < 1 || month > 12 || day < 1 || day > 31) {
      return null;
    }

    final DateTime candidate = DateTime.utc(year, month, day);
    // DateTime.utc rolls overflow forward, so a round-trip catches 31 February.
    if (candidate.year != year ||
        candidate.month != month ||
        candidate.day != day) {
      return null;
    }
    return candidate;
  }

  /// Renders [iso] for people: `29 Aug 2026`.
  ///
  /// Returns [iso] unchanged if it is not parseable, so a malformed value shows
  /// as itself rather than throwing inside a build method.
  static String display(String iso) {
    final DateTime? date = tryParse(iso);
    if (date == null) {
      return iso;
    }
    return '${date.day} ${_monthNames[date.month - 1]} ${date.year}';
  }

  /// Renders [iso] without the year, for lists already grouped by year.
  static String displayShort(String iso) {
    final DateTime? date = tryParse(iso);
    if (date == null) {
      return iso;
    }
    return '${date.day} ${_monthNames[date.month - 1]}';
  }

  /// Epoch milliseconds at UTC midnight, for the Material date picker.
  static int toEpochMillis(String iso) => parse(iso).millisecondsSinceEpoch;

  /// The ISO date for [millis], interpreted as UTC.
  ///
  /// Must pair with [toEpochMillis]: reading these millis back as local time is
  /// what shifts the date by a day.
  static String fromEpochMillis(int millis) {
    final DateTime date = DateTime.fromMillisecondsSinceEpoch(
      millis,
      isUtc: true,
    );
    return _render(date.year, date.month, date.day);
  }

  /// The first day of [iso]'s month.
  static String startOfMonth(String iso) {
    final DateTime date = parse(iso);
    return _render(date.year, date.month, 1);
  }

  /// The last day of [iso]'s month, leap years included.
  static String endOfMonth(String iso) {
    final DateTime date = parse(iso);
    // Day zero of the following month is the last day of this one.
    final DateTime last = DateTime.utc(date.year, date.month + 1, 0);
    return _render(last.year, last.month, last.day);
  }

  /// Adds [days] to [iso], returning an ISO date.
  static String addDays(String iso, int days) {
    final DateTime moved = parse(iso).add(Duration(days: days));
    return _render(moved.year, moved.month, moved.day);
  }

  /// Whole days from [from] to [to]; negative when [to] is earlier.
  static int daysBetween(String from, String to) =>
      parse(to).difference(parse(from)).inDays;

  /// Compares two ISO dates chronologically.
  ///
  /// A plain string comparison, because the format makes that identical to
  /// comparing the dates -- which is also why SQLite can order and range-filter
  /// the stored text directly.
  static int compare(String a, String b) => a.compareTo(b);

  static String _render(int year, int month, int day) =>
      '${year.toString().padLeft(4, '0')}-'
      '${month.toString().padLeft(2, '0')}-'
      '${day.toString().padLeft(2, '0')}';
}
