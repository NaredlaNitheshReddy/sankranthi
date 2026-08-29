import 'package:flutter_test/flutter_test.dart';
import 'package:sankranthi/core/dates.dart';

void main() {
  group('parsing is strict', () {
    test('accepts a well-formed date', () {
      final DateTime parsed = Dates.parse('2026-08-29');
      expect(parsed.year, 2026);
      expect(parsed.month, 8);
      expect(parsed.day, 29);
      expect(parsed.isUtc, isTrue, reason: 'must be UTC midnight');
    });

    test('rejects unpadded components', () {
      // A lenient parser would let "2026-8-9" reach the database, where it
      // sorts before "2026-10-01" but after "2026-1-1" -- silently wrong
      // ordering rather than a visible error.
      for (final String input in <String>['2026-8-9', '2026-8-09', '26-08-09']) {
        expect(Dates.isValid(input), isFalse, reason: input);
      }
    });

    test('rejects other date shapes', () {
      for (final String input in <String>[
        '29-08-2026',
        '2026/08/29',
        '2026-08-29T00:00:00Z',
        '2026-08-29 ',
        '',
        'today',
      ]) {
        expect(Dates.isValid(input), isFalse, reason: input);
      }
    });

    test('rejects impossible dates instead of rolling them forward', () {
      // DateTime.utc(2026, 2, 30) silently becomes 2 March. Accepting that
      // would record a date the user never chose.
      for (final String input in <String>[
        '2026-02-30',
        '2026-13-01',
        '2026-00-10',
        '2026-04-31',
        '2026-08-00',
      ]) {
        expect(Dates.isValid(input), isFalse, reason: input);
      }
    });

    test('leap years are handled both ways', () {
      expect(Dates.isValid('2024-02-29'), isTrue, reason: '2024 is a leap year');
      expect(Dates.isValid('2026-02-29'), isFalse, reason: '2026 is not');
      expect(Dates.isValid('2000-02-29'), isTrue, reason: 'century leap year');
      expect(Dates.isValid('1900-02-29'), isFalse, reason: '1900 was not');
    });

    test('parse throws where tryParse returns null', () {
      expect(() => Dates.parse('nonsense'), throwsA(isA<DateFormatException>()));
      expect(Dates.tryParse('nonsense'), isNull);
    });
  });

  group('formatting', () {
    test('pads month and day', () {
      expect(Dates.format(DateTime(2026, 1, 5)), '2026-01-05');
    });

    test('today uses the local calendar date', () {
      expect(Dates.today(now: DateTime(2026, 8, 29, 23, 59)), '2026-08-29');
      expect(Dates.today(now: DateTime(2026, 1, 1, 0, 0)), '2026-01-01');
    });

    test('display is human readable', () {
      expect(Dates.display('2026-08-29'), '29 Aug 2026');
      expect(Dates.display('2026-01-05'), '5 Jan 2026');
      expect(Dates.displayShort('2026-08-29'), '29 Aug');
    });

    test('display passes malformed input through rather than throwing', () {
      // This runs inside build methods; an exception there is a red screen.
      expect(Dates.display('not-a-date'), 'not-a-date');
      expect(Dates.displayShort(''), '');
    });
  });

  group('epoch conversion round-trips without shifting the day', () {
    test('every day of a year survives the round trip', () {
      // The bug this guards: using *local* midnight means a timezone behind UTC
      // converts to millis and reads back as the previous day. Checking a whole
      // year also crosses any DST boundary the host happens to observe.
      String date = '2026-01-01';
      for (int i = 0; i < 365; i++) {
        final int millis = Dates.toEpochMillis(date);
        expect(
          Dates.fromEpochMillis(millis),
          date,
          reason: 'round trip changed $date',
        );
        date = Dates.addDays(date, 1);
      }
    });

    test('a known instant maps to a known date', () {
      expect(Dates.fromEpochMillis(0), '1970-01-01');
      expect(Dates.toEpochMillis('1970-01-02'), Duration.millisecondsPerDay);
    });
  });

  group('month boundaries', () {
    test('startOfMonth', () {
      expect(Dates.startOfMonth('2026-08-29'), '2026-08-01');
      expect(Dates.startOfMonth('2026-08-01'), '2026-08-01');
    });

    test('endOfMonth knows how long each month is', () {
      expect(Dates.endOfMonth('2026-08-15'), '2026-08-31');
      expect(Dates.endOfMonth('2026-04-01'), '2026-04-30');
      expect(Dates.endOfMonth('2026-12-25'), '2026-12-31');
    });

    test('endOfMonth handles February in both kinds of year', () {
      expect(Dates.endOfMonth('2026-02-10'), '2026-02-28');
      expect(Dates.endOfMonth('2024-02-10'), '2024-02-29');
    });
  });

  group('arithmetic', () {
    test('addDays crosses month and year boundaries', () {
      expect(Dates.addDays('2026-08-31', 1), '2026-09-01');
      expect(Dates.addDays('2026-12-31', 1), '2027-01-01');
      expect(Dates.addDays('2026-01-01', -1), '2025-12-31');
    });

    test('addDays crosses a leap day', () {
      expect(Dates.addDays('2024-02-28', 1), '2024-02-29');
      expect(Dates.addDays('2024-02-28', 2), '2024-03-01');
      expect(Dates.addDays('2026-02-28', 1), '2026-03-01');
    });

    test('daysBetween is signed', () {
      expect(Dates.daysBetween('2026-08-01', '2026-08-31'), 30);
      expect(Dates.daysBetween('2026-08-31', '2026-08-01'), -30);
      expect(Dates.daysBetween('2026-08-01', '2026-08-01'), 0);
    });

    test('daysBetween across a year is not off by one', () {
      expect(Dates.daysBetween('2026-01-01', '2027-01-01'), 365);
      expect(Dates.daysBetween('2024-01-01', '2025-01-01'), 366);
    });
  });

  group('ordering', () {
    test('lexicographic order is chronological order', () {
      // This property is why dates are stored as text: SQLite can ORDER BY and
      // range-filter the column directly, with no conversion or function index.
      final List<String> dates = <String>[
        '2026-10-01',
        '2025-12-31',
        '2026-01-05',
        '2026-09-30',
        '2026-01-15',
      ];
      final List<String> sorted = <String>[...dates]..sort(Dates.compare);

      expect(sorted, <String>[
        '2025-12-31',
        '2026-01-05',
        '2026-01-15',
        '2026-09-30',
        '2026-10-01',
      ]);

      // And the same order the actual dates have.
      final List<DateTime> asDates = sorted.map(Dates.parse).toList();
      for (int i = 1; i < asDates.length; i++) {
        expect(asDates[i].isAfter(asDates[i - 1]), isTrue);
      }
    });
  });
}
