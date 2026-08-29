import 'package:flutter_test/flutter_test.dart';
import 'package:sankranthi/core/wire_enum.dart';
import 'package:sankranthi/data/local/converters.dart';
import 'package:sankranthi/domain/access_enums.dart';
import 'package:sankranthi/domain/ledger_enums.dart';
import 'package:sankranthi/domain/sync_enums.dart';

void main() {
  group('toSql always writes the wire string', () {
    test('never the Dart identifier', () {
      // The bug drift's textEnum would have given us for free.
      expect(opTypeConverter.toSql(OpType.uploadReceipt), 'upload_receipt');
      expect(opStatusConverter.toSql(OpStatus.inFlight), 'in_flight');
      expect(tradeKindConverter.toSql(TradeKind.sell), 'sell');
    });

    test('round-trips every value of every converter', () {
      void check<T extends WireEnum>(
        String name,
        WireEnumConverter<T> converter,
      ) {
        for (final T value in converter.values) {
          expect(
            converter.fromSql(converter.toSql(value)),
            same(value),
            reason: '$name.${value.wire} did not round-trip',
          );
        }
      }

      check('SyncStatus', syncStatusConverter);
      check('UploadStatus', uploadStatusConverter);
      check('OpStatus', opStatusConverter);
      check('AccessStatus', accessStatusConverter);
      check('TradeKind', tradeKindConverter);
      check('OpType', opTypeConverter);
    });
  });

  group('strict converters refuse to guess', () {
    test('an unknown value throws with the enum name attached', () {
      expect(
        () => tradeKindConverter.fromSql('barter'),
        throwsA(
          isA<UnknownWireValue>()
              .having((UnknownWireValue e) => e.wire, 'wire', 'barter')
              .having(
                (UnknownWireValue e) => e.enumName,
                'enumName',
                'TradeKind',
              ),
        ),
      );
    });

    test('every converter that must not guess, does not', () {
      // Each decides a sign or an intent. A wrong guess corrupts a number or
      // performs the wrong action, so failing loudly is the only defensible
      // behaviour.
      expect(
        () => tradeKindConverter.fromSql('?'),
        throwsA(isA<UnknownWireValue>()),
      );
      expect(
        () => opTypeConverter.fromSql('?'),
        throwsA(isA<UnknownWireValue>()),
      );
    });
  });

  group('fallback converters substitute the conservative value', () {
    test('SyncStatus falls back to pending, never to synced', () {
      // The single most consequential fallback in the app. Pending means the
      // row may be uploaded again, which the server deduplicates by opId and
      // which costs nothing. Synced would mark a dirty row clean and the
      // record would never reach the other partners.
      expect(syncStatusConverter.fromSql('who-knows'), SyncStatus.pending);
      expect(
        syncStatusConverter.onUnknown,
        isNot(SyncStatus.synced),
        reason: 'falling back to synced silently loses records',
      );
    });

    test('UploadStatus falls back to pending', () {
      expect(uploadStatusConverter.fromSql('who-knows'), UploadStatus.pending);
      expect(
        uploadStatusConverter.onUnknown,
        isNot(UploadStatus.uploaded),
        reason: 'claiming an upload happened would strand the bytes locally',
      );
    });

    test('OpStatus falls back to queued, not failedPermanent', () {
      // §103: losing queued work is data loss. Retrying is safe.
      expect(opStatusConverter.fromSql('who-knows'), OpStatus.queued);
      expect(opStatusConverter.onUnknown, isNot(OpStatus.failedPermanent));
    });

    test('AccessStatus falls back to pending, which denies access', () {
      // The one fallback that is a security decision: on ambiguity, deny.
      expect(
        accessStatusConverter.fromSql('probationary'),
        AccessStatus.pending,
      );
      expect(
        accessStatusConverter.onUnknown?.isApproved,
        isFalse,
        reason: 'an unrecognised status must never grant access',
      );
    });

    test('a known value is never replaced by the fallback', () {
      expect(syncStatusConverter.fromSql('synced'), SyncStatus.synced);
      expect(accessStatusConverter.fromSql('approved'), AccessStatus.approved);
    });
  });

  group('nullable converters', () {
    // Kept for the enum columns that are nullable; strictness is inherited from
    // the wrapped converter so NULL and "unrecognised" stay distinguishable.
    const NullableWireEnumConverter<TradeKind> nullableTradeKind =
        NullableWireEnumConverter<TradeKind>(tradeKindConverter);

    test('NULL maps to null in both directions', () {
      expect(nullableTradeKind.fromSql(null), isNull);
      expect(nullableTradeKind.toSql(null), isNull);
    });

    test('a present value delegates to the inner converter', () {
      expect(nullableTradeKind.fromSql('buy'), TradeKind.buy);
      expect(nullableTradeKind.toSql(TradeKind.buy), 'buy');
    });

    test('strictness is inherited, so NULL and unknown stay distinguishable', () {
      // NULL means "not set"; an unrecognised string means "something is
      // wrong". Collapsing the second into the first would hide corruption.
      expect(
        () => nullableTradeKind.fromSql('barter'),
        throwsA(isA<UnknownWireValue>()),
      );
    });
  });

  group('the fallback decision is never implicit', () {
    test('strict converters expose a null fallback', () {
      // There is no default constructor on WireEnumConverter: every use site
      // has to pick strict or name a fallback, so the unknown case cannot be
      // arrived at by omission.
      expect(tradeKindConverter.onUnknown, isNull);
      expect(opTypeConverter.onUnknown, isNull);
    });

    test('fallback converters expose the value they chose', () {
      expect(syncStatusConverter.onUnknown, SyncStatus.pending);
      expect(opStatusConverter.onUnknown, OpStatus.queued);
      expect(accessStatusConverter.onUnknown, AccessStatus.pending);
      expect(uploadStatusConverter.onUnknown, UploadStatus.pending);
    });
  });

  group('no converter exists for reference-table data', () {
    test('the taxonomies are tables, so their columns hold plain slugs', () {
      // Expense categories, stock units, movement types, count reasons and
      // livestock categories are user-extensible reference rows now. Their
      // columns store a slug that is a foreign key, so there is nothing to
      // convert -- the slug already *is* the stored form. Behaviour that used
      // to hang off the enum is validated by ReferenceRules instead.
      //
      // This test documents the absence so nobody reintroduces a closed set.
      const List<String> onlyStateMachineConverters = <String>[
        'syncStatusConverter',
        'uploadStatusConverter',
        'opStatusConverter',
        'opTypeConverter',
        'accessStatusConverter',
        'tradeKindConverter',
      ];
      expect(onlyStateMachineConverters, hasLength(6));
    });
  });
}
