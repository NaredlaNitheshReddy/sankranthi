import 'package:flutter_test/flutter_test.dart';
import 'package:sankranthi/data/local/reference_defaults.dart';
import 'package:sankranthi/domain/reference_rules.dart';

/// Every seeded reference list, with the attributes each one is required to
/// carry, so a newly added list is covered by the sweeps below automatically.
final Map<String, ({List<ReferenceSeed> rows, bool needsSign, bool needsDecimals})>
allReferenceLists = <String, ({List<ReferenceSeed> rows, bool needsSign, bool needsDecimals})>{
  'expense_category': (
    rows: defaultExpenseCategories,
    needsSign: false,
    needsDecimals: false,
  ),
  'stock_unit': (rows: defaultStockUnits, needsSign: false, needsDecimals: true),
  'stock_txn_type': (
    rows: defaultStockTxnTypes,
    needsSign: true,
    needsDecimals: false,
  ),
  'count_reason': (
    rows: defaultCountReasons,
    needsSign: true,
    needsDecimals: false,
  ),
  'livestock_category': (
    rows: defaultLivestockCategories,
    needsSign: false,
    needsDecimals: false,
  ),
};

void main() {
  group('validation replaces what the compiler used to prove', () {
    test('a sign outside {-1, 0, 1} is rejected, not clamped', () {
      // Clamping would invent a direction and silently misreport a stock
      // balance. The point of integer quantities is to avoid exactly this kind
      // of quiet wrongness, so it fails loudly and someone fixes the row.
      for (final int bad in <int>[2, -2, 7, -99]) {
        expect(
          () => ReferenceRules.checkSign(bad, 'stock_txn_type.mystery'),
          throwsA(
            isA<ReferenceDataException>().having(
              (ReferenceDataException e) => e.problem,
              'problem',
              ReferenceProblem.invalidSign,
            ),
          ),
          reason: 'sign $bad should be rejected',
        );
      }
    });

    test('the three valid signs pass', () {
      for (final int good in <int>[-1, 0, 1]) {
        expect(ReferenceRules.checkSign(good, 'subject'), good);
      }
    });

    test('decimals beyond the stored resolution are rejected', () {
      // Thousandths is what the column holds; claiming four places would
      // display precision that does not exist.
      expect(
        () => ReferenceRules.checkDecimals(4, 'stock_unit.nanogram'),
        throwsA(
          isA<ReferenceDataException>().having(
            (ReferenceDataException e) => e.problem,
            'problem',
            ReferenceProblem.invalidDecimals,
          ),
        ),
      );
      expect(
        () => ReferenceRules.checkDecimals(-1, 'stock_unit.odd'),
        throwsA(isA<ReferenceDataException>()),
      );
    });

    test('decimals 0 through 3 pass', () {
      for (int d = 0; d <= 3; d++) {
        expect(ReferenceRules.checkDecimals(d, 'subject'), d);
      }
    });

    test('a key that is not a slug is rejected', () {
      // The key is also the wire value and the foreign key, so it has to obey
      // the same convention every enum did.
      for (final String bad in <String>[
        'Feed',
        'shed repair',
        'shed-repair',
        '_feed',
        '1feed',
        '',
        'feed!',
      ]) {
        expect(
          ReferenceRules.isValidKey(bad),
          isFalse,
          reason: '"$bad" should not be a valid key',
        );
      }
    });

    test('valid slugs pass', () {
      for (final String good in <String>[
        'feed',
        'shed_repair',
        'transfer_in',
        'unit2',
      ]) {
        expect(ReferenceRules.isValidKey(good), isTrue, reason: good);
      }
    });

    test('an empty label is rejected', () {
      // A blank picker entry is unselectable and unexplainable.
      expect(
        () => ReferenceRules.checkLabel('   ', 'expense_category.blank'),
        throwsA(
          isA<ReferenceDataException>().having(
            (ReferenceDataException e) => e.problem,
            'problem',
            ReferenceProblem.emptyLabel,
          ),
        ),
      );
    });

    test('the exception says what was wrong and where', () {
      // A validation failure has to be actionable: which row, which value.
      final ReferenceDataException e = ReferenceDataException(
        ReferenceProblem.invalidSign,
        'stock_txn_type.wastage',
        7,
      );
      expect(e.toString(), contains('stock_txn_type.wastage'));
      expect(e.toString(), contains('7'));
    });
  });

  group('applySign', () {
    test('a signed type takes the magnitude, so it cannot double-negate', () {
      // Guards a real mistake: passing an already-negative consumption and
      // getting a positive back, which would add stock instead of removing it.
      expect(
        ReferenceRules.applySign(sign: -1, value: 5000, subject: 's'),
        -5000,
      );
      expect(
        ReferenceRules.applySign(sign: -1, value: -5000, subject: 's'),
        -5000,
      );
      expect(ReferenceRules.applySign(sign: 1, value: 5000, subject: 's'), 5000);
      expect(
        ReferenceRules.applySign(sign: 1, value: -5000, subject: 's'),
        5000,
      );
    });

    test('sign 0 passes the value through, direction and all', () {
      // The user chose the direction, so the stored value already carries it.
      expect(
        ReferenceRules.applySign(sign: 0, value: -500, subject: 's'),
        -500,
      );
      expect(ReferenceRules.applySign(sign: 0, value: 500, subject: 's'), 500);
    });

    test('validates before it computes', () {
      expect(
        () => ReferenceRules.applySign(sign: 3, value: 100, subject: 's'),
        throwsA(isA<ReferenceDataException>()),
      );
    });

    test('zero stays zero for every sign', () {
      for (final int sign in <int>[-1, 0, 1]) {
        expect(ReferenceRules.applySign(sign: sign, value: 0, subject: 's'), 0);
      }
    });
  });

  group('the seeded defaults are all valid', () {
    test('every row in every list passes checkRow', () {
      // The seed data is the one reference set we ship, so it had better obey
      // the rules we enforce on downloaded rows.
      allReferenceLists.forEach((
        String name,
        ({List<ReferenceSeed> rows, bool needsSign, bool needsDecimals}) list,
      ) {
        for (final ReferenceSeed row in list.rows) {
          expect(
            () => ReferenceRules.checkRow(
              key: row.key,
              label: row.label,
              subject: '$name.${row.key}',
              sign: row.sign,
              decimals: row.decimals,
            ),
            returnsNormally,
            reason: '$name.${row.key} is not a valid seed row',
          );
        }
      });
    });

    test('keys are unique within each list', () {
      // They are primary keys; a duplicate would fail at insert with a much
      // less obvious message.
      allReferenceLists.forEach((
        String name,
        ({List<ReferenceSeed> rows, bool needsSign, bool needsDecimals}) list,
      ) {
        final Set<String> keys = list.rows
            .map((ReferenceSeed r) => r.key)
            .toSet();
        expect(
          keys,
          hasLength(list.rows.length),
          reason: '$name has a duplicate key',
        );
      });
    });

    test('lists that need a sign have one on every row', () {
      allReferenceLists.forEach((
        String name,
        ({List<ReferenceSeed> rows, bool needsSign, bool needsDecimals}) list,
      ) {
        if (!list.needsSign) {
          return;
        }
        for (final ReferenceSeed row in list.rows) {
          expect(
            row.sign,
            isNotNull,
            reason: '$name.${row.key} must declare a sign',
          );
        }
      });
    });

    test('stock units all declare a symbol and a precision', () {
      for (final ReferenceSeed unit in defaultStockUnits) {
        expect(unit.symbol, isNotNull, reason: unit.key);
        expect(unit.symbol, isNotEmpty, reason: unit.key);
        expect(unit.decimals, isNotNull, reason: unit.key);
      }
    });
  });

  group('the defaults preserve the behaviour the enums had', () {
    ReferenceSeed find(List<ReferenceSeed> rows, String key) =>
        rows.firstWhere((ReferenceSeed r) => r.key == key);

    test('purchase adds, consumption subtracts', () {
      expect(find(defaultStockTxnTypes, 'purchase').sign, 1);
      expect(find(defaultStockTxnTypes, 'consumption').sign, -1);
    });

    test('adjustment and transfer ask the user for a direction', () {
      for (final String key in <String>['adjustment', 'transfer']) {
        final ReferenceSeed row = find(defaultStockTxnTypes, key);
        expect(row.sign, 0, reason: key);
        expect(row.requiresDirection, isTrue, reason: key);
      }
    });

    test('purchase and consumption do not ask', () {
      for (final String key in <String>['purchase', 'consumption']) {
        expect(
          find(defaultStockTxnTypes, key).requiresDirection,
          isFalse,
          reason: key,
        );
      }
    });

    test('count reasons carry the direction the herd moves', () {
      expect(find(defaultCountReasons, 'birth').sign, 1);
      expect(find(defaultCountReasons, 'purchase').sign, 1);
      expect(find(defaultCountReasons, 'transfer_in').sign, 1);
      expect(find(defaultCountReasons, 'death').sign, -1);
      expect(find(defaultCountReasons, 'sale').sign, -1);
      expect(find(defaultCountReasons, 'transfer_out').sign, -1);
      expect(find(defaultCountReasons, 'correction').sign, 0);
    });

    test('only a correction demands a note', () {
      // "The count was wrong" does not explain itself, so §44's exact-count
      // action requires the user to say why.
      expect(find(defaultCountReasons, 'correction').requiresNote, isTrue);
      for (final ReferenceSeed row in defaultCountReasons) {
        if (row.key != 'correction') {
          expect(row.requiresNote ?? false, isFalse, reason: row.key);
        }
      }
    });

    test('countable units show no decimals', () {
      // There is no such thing as a third of a sack.
      for (final String key in <String>['piece', 'bag', 'bundle', 'dose']) {
        expect(find(defaultStockUnits, key).decimals, 0, reason: key);
      }
    });

    test('weights and volumes show the stored resolution', () {
      expect(find(defaultStockUnits, 'kilogram').decimals, 3);
      expect(find(defaultStockUnits, 'litre').decimals, 3);
    });

    test('the slugs are unchanged from when these were enums', () {
      // The stored form must not have moved: existing rows point at these, and
      // the sheet already contains them.
      expect(
        defaultExpenseCategories.map((ReferenceSeed r) => r.key),
        containsAll(<String>[
          'feed',
          'veterinary',
          'labour',
          'transport',
          'shed_repair',
          'utilities',
          'other',
        ]),
      );
      expect(
        defaultCountReasons.map((ReferenceSeed r) => r.key),
        containsAll(<String>['transfer_in', 'transfer_out', 'correction']),
      );
    });

    test('the expense fallback category exists', () {
      // An expense whose category row is missing has to land somewhere.
      expect(
        defaultExpenseCategories.map((ReferenceSeed r) => r.key),
        contains(fallbackExpenseCategoryKey),
      );
    });

    test('"other" sorts last so it does not crowd the real choices', () {
      final ReferenceSeed other = find(defaultExpenseCategories, 'other');
      for (final ReferenceSeed row in defaultExpenseCategories) {
        if (row.key != 'other') {
          expect(
            row.sortOrder,
            lessThan(other.sortOrder),
            reason: row.key,
          );
        }
      }
    });
  });
}
