import 'package:flutter_test/flutter_test.dart';
import 'package:sankranthi/core/quantity.dart';

void main() {
  group('parseToMilli', () {
    test('whole units', () {
      expect(Quantity.parseToMilli('50'), 50000);
    });

    test('the 12.5 kg feed lot that motivates integer storage', () {
      expect(Quantity.parseToMilli('12.5'), 12500);
    });

    test('one, two and three decimal places scale correctly', () {
      expect(Quantity.parseToMilli('1.5'), 1500);
      expect(Quantity.parseToMilli('1.25'), 1250);
      expect(Quantity.parseToMilli('1.125'), 1125);
    });

    test('a gram of feed is the resolution floor', () {
      expect(Quantity.parseToMilli('0.001'), 1);
    });

    test('zero', () {
      expect(Quantity.parseToMilli('0'), 0);
      expect(Quantity.parseToMilli('0.000'), 0);
    });

    test('tolerates separators and whitespace', () {
      expect(Quantity.parseToMilli(' 1,250.5 '), 1250500);
    });
  });

  group('parseToMilli refuses rather than guessing', () {
    test('finer than a thousandth is refused, not rounded', () {
      expect(
        () => Quantity.parseToMilli('1.0005'),
        throwsA(
          isA<QuantityFormatException>().having(
            (QuantityFormatException e) => e.reason,
            'reason',
            QuantityProblem.subMilliPrecision,
          ),
        ),
      );
    });

    test('negative input is refused', () {
      // A consumption is negative because of its transaction *type*, not
      // because someone typed a minus sign.
      expect(
        () => Quantity.parseToMilli('-5'),
        throwsA(
          isA<QuantityFormatException>().having(
            (QuantityFormatException e) => e.reason,
            'reason',
            QuantityProblem.negative,
          ),
        ),
      );
    });

    test('empty input', () {
      for (final String input in <String>['', '  ', ',']) {
        expect(Quantity.tryParseToMilli(input), isNull);
      }
    });

    test('letters and units are not silently stripped', () {
      // "12kg" is refused rather than read as 12: the unit belongs to the stock
      // item, and quietly accepting it would let "12lb" mean 12 kg.
      for (final String input in <String>['12kg', '5 litres', 'abc']) {
        expect(
          () => Quantity.parseToMilli(input),
          throwsA(
            isA<QuantityFormatException>().having(
              (QuantityFormatException e) => e.reason,
              'reason',
              QuantityProblem.unexpectedCharacter,
            ),
          ),
          reason: 'input was "$input"',
        );
      }
    });

    test('absurdly large quantities', () {
      expect(
        () => Quantity.parseToMilli('99999999999999999999'),
        throwsA(isA<QuantityFormatException>()),
      );
    });
  });

  group('format', () {
    test('trims trailing zeros so whole units read cleanly', () {
      expect(Quantity.format(12000), '12');
      expect(Quantity.format(12500), '12.5');
      expect(Quantity.format(12050), '12.05');
      expect(Quantity.format(12345), '12.345');
    });

    test('zero', () {
      expect(Quantity.format(0), '0');
    });

    test('uses Indian grouping, consistently with money', () {
      expect(Quantity.format(1234567000), '12,34,567');
    });

    test('decimals caps the places shown', () {
      expect(Quantity.format(12345, decimals: 0), '12');
      expect(Quantity.format(12345, decimals: 1), '12.3');
      expect(Quantity.format(12345, decimals: 2), '12.34');
      expect(Quantity.format(12345, decimals: 3), '12.345');
    });

    test('truncates toward zero rather than rounding up', () {
      // Rounding 12.999 up to 13 would report more stock on hand than the
      // balance actually holds, which is the wrong direction to be wrong in.
      expect(Quantity.format(12999, decimals: 0), '12');
      expect(Quantity.format(12999, decimals: 2), '12.99');
    });

    test('negative values format, because a movement can be negative', () {
      expect(Quantity.format(-12500), '-12.5');
    });

    test('signed prefixes a positive value only', () {
      expect(Quantity.format(12500, signed: true), '+12.5');
      expect(Quantity.format(-12500, signed: true), '-12.5');
      expect(Quantity.format(0, signed: true), '0');
    });
  });

  group('round-tripping', () {
    test('toEditable output parses back to the same value', () {
      for (final int milli in <int>[0, 1, 999, 1000, 12500, 1234567000]) {
        expect(
          Quantity.parseToMilli(Quantity.toEditable(milli)),
          milli,
          reason: 'failed for $milli',
        );
      }
    });

    test('format output parses back when no precision was dropped', () {
      for (final int milli in <int>[0, 1000, 12500, 12345, 1234567000]) {
        expect(
          Quantity.parseToMilli(Quantity.format(milli)),
          milli,
          reason: 'failed for $milli',
        );
      }
    });

    test('summing thousandths is exact, which is the whole point', () {
      // The same arithmetic in double would not land on 100.0 exactly. A stock
      // balance is derived by summing its history, so this is the property the
      // integer representation exists to guarantee.
      const int lot = 12500; // 12.5 units
      int total = 0;
      for (int i = 0; i < 8; i++) {
        total += lot;
      }
      expect(total, 100000);
      expect(Quantity.format(total), '100');
    });
  });
}
