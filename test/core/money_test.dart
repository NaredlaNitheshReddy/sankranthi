import 'package:flutter_test/flutter_test.dart';
import 'package:sankranthi/core/money.dart';

void main() {
  group('parseToMinor accepts what people type', () {
    test('whole rupees', () {
      expect(Money.parseToMinor('250'), 25000);
    });

    test('rupees and paise', () {
      expect(Money.parseToMinor('2500.75'), 250075);
    });

    test('one decimal place is tenths of a rupee, not paise', () {
      // The classic off-by-ten: "1.5" must be 150 paise, never 15.
      expect(Money.parseToMinor('1.5'), 150);
    });

    test('a bare decimal point with no fraction', () {
      expect(Money.parseToMinor('12.'), 1200);
    });

    test('a leading point', () {
      expect(Money.parseToMinor('.75'), 75);
    });

    test('zero', () {
      expect(Money.parseToMinor('0'), 0);
      expect(Money.parseToMinor('0.00'), 0);
    });

    test('tolerates a rupee sign, separators and whitespace', () {
      expect(Money.parseToMinor('  ₹ 12,34,567.89 '), 123456789);
    });

    test('tolerates a non-breaking space', () {
      expect(Money.parseToMinor('₹ 2,500'), 250000);
    });
  });

  group('parseToMinor refuses rather than guessing', () {
    test('sub-paisa precision is refused, not rounded', () {
      // The whole point: rounding 10.005 to 10.01 loses a rupee across two
      // hundred rows and nobody can reconstruct why the totals disagree.
      expect(
        () => Money.parseToMinor('10.005'),
        throwsA(
          isA<MoneyFormatException>().having(
            (MoneyFormatException e) => e.reason,
            'reason',
            MoneyProblem.subPaisaPrecision,
          ),
        ),
      );
    });

    test('a negative amount is refused', () {
      // Direction lives in the record -- a purchase versus a sale -- not in the
      // sign of the number someone types.
      expect(
        () => Money.parseToMinor('-50'),
        throwsA(
          isA<MoneyFormatException>().having(
            (MoneyFormatException e) => e.reason,
            'reason',
            MoneyProblem.negative,
          ),
        ),
      );
    });

    test('empty and whitespace-only input', () {
      for (final String input in <String>['', '   ', '₹', ',']) {
        expect(
          () => Money.parseToMinor(input),
          throwsA(
            isA<MoneyFormatException>().having(
              (MoneyFormatException e) => e.reason,
              'reason',
              MoneyProblem.empty,
            ),
          ),
          reason: 'input was "$input"',
        );
      }
    });

    test('letters and stray symbols', () {
      for (final String input in <String>['12a', 'abc', '1 2 3x', '5%']) {
        expect(
          () => Money.parseToMinor(input),
          throwsA(
            isA<MoneyFormatException>().having(
              (MoneyFormatException e) => e.reason,
              'reason',
              MoneyProblem.unexpectedCharacter,
            ),
          ),
          reason: 'input was "$input"',
        );
      }
    });

    test('more than one decimal point', () {
      expect(
        () => Money.parseToMinor('1.2.3'),
        throwsA(
          isA<MoneyFormatException>().having(
            (MoneyFormatException e) => e.reason,
            'reason',
            MoneyProblem.multiplePoints,
          ),
        ),
      );
    });

    test('absurdly large amounts', () {
      expect(
        () => Money.parseToMinor('99999999999999999999'),
        throwsA(
          isA<MoneyFormatException>().having(
            (MoneyFormatException e) => e.reason,
            'reason',
            MoneyProblem.tooLarge,
          ),
        ),
      );
    });

    test('tryParseToMinor returns null instead of throwing', () {
      expect(Money.tryParseToMinor('-50'), isNull);
      expect(Money.tryParseToMinor('10.005'), isNull);
      expect(Money.tryParseToMinor('2500.75'), 250075);
    });
  });

  group('format uses Indian grouping', () {
    test('groups as 2,2,3 from the right, not 3,3,3', () {
      // 12,345,678 would be wrong here; Indian grouping is 1,23,45,678.
      expect(Money.format(1234567890), '₹1,23,45,678.90');
    });

    test('short amounts are not grouped', () {
      expect(Money.format(0), '₹0.00');
      expect(Money.format(5), '₹0.05');
      expect(Money.format(50), '₹0.50');
      expect(Money.format(99999), '₹999.99');
    });

    test('the first group appears at a thousand', () {
      expect(Money.format(100000), '₹1,000.00');
    });

    test('a lakh and a crore', () {
      expect(Money.format(10000000), '₹1,00,000.00');
      expect(Money.format(1000000000), '₹1,00,00,000.00');
    });

    test('paise are always two digits', () {
      expect(Money.format(250070), '₹2,500.70');
      expect(Money.format(250007), '₹2,500.07');
    });

    test('negative values format, because a net position can be below zero', () {
      // parseToMinor refuses negative *input*, but a computed net legitimately
      // goes negative and still has to be displayable.
      expect(Money.format(-250075), '-₹2,500.75');
    });

    test('signed prefixes a positive value only', () {
      expect(Money.format(250075, signed: true), '+₹2,500.75');
      expect(Money.format(-250075, signed: true), '-₹2,500.75');
      expect(Money.format(0, signed: true), '₹0.00');
    });

    test('the symbol can be omitted', () {
      expect(Money.format(250075, symbol: false), '2,500.75');
    });
  });

  group('round-tripping', () {
    test('toEditable output parses back to the same value', () {
      for (final int minor in <int>[0, 1, 99, 100, 150, 250075, 1234567890]) {
        expect(
          Money.parseToMinor(Money.toEditable(minor)),
          minor,
          reason: 'failed for $minor',
        );
      }
    });

    test('formatted output parses back to the same value', () {
      // Decoration tolerance exists so a displayed figure can be pasted back
      // into a field without editing.
      for (final int minor in <int>[0, 5, 100000, 1234567890]) {
        expect(
          Money.parseToMinor(Money.format(minor)),
          minor,
          reason: 'failed for $minor',
        );
      }
    });

    test('toEditable drops the sign so a field never shows one', () {
      expect(Money.toEditable(-250075), '2500.75');
    });
  });
}
