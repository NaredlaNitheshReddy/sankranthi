import 'package:flutter_test/flutter_test.dart';
import 'package:sankranthi/core/wire_enum.dart';
import 'package:sankranthi/domain/access_enums.dart';
import 'package:sankranthi/domain/ledger_enums.dart';
import 'package:sankranthi/domain/sync_enums.dart';

/// Every wire enum left in the app, so the convention checks below cover a new
/// one automatically rather than only the ones someone remembered to add.
///
/// This list is deliberately short. The taxonomies that used to be here --
/// expense categories, stock units, movement types, count reasons -- are
/// user-extensible reference tables now; see reference_defaults_test.dart.
final Map<String, List<WireEnum>> allWireEnums = <String, List<WireEnum>>{
  'TradeKind': TradeKind.values,
  'SyncStatus': SyncStatus.values,
  'UploadStatus': UploadStatus.values,
  'OpType': OpType.values,
  'OpStatus': OpStatus.values,
  'AuditAction': AuditAction.values,
  'AccessStatus': AccessStatus.values,
  'Permission': Permission.values,
};

void main() {
  group('wire conventions hold for every enum', () {
    test('wire values are unique within each enum', () {
      allWireEnums.forEach((String name, List<WireEnum> values) {
        final Set<String> wires = values.map((WireEnum v) => v.wire).toSet();
        expect(
          wires,
          hasLength(values.length),
          reason: '$name has a duplicate wire value',
        );
      });
    });

    test('wire values are lowercase, underscore-separated ASCII', () {
      final RegExp shape = RegExp(r'^[a-z][a-z0-9_]*$');
      allWireEnums.forEach((String name, List<WireEnum> values) {
        for (final WireEnum value in values) {
          expect(
            shape.hasMatch(value.wire),
            isTrue,
            reason: '$name.${value.wire} is not lowercase_with_underscores',
          );
        }
      });
    });

    test('no wire value is a Dart identifier in disguise', () {
      // The failure this guards: writing `shedRepair` as the wire string, which
      // is exactly what drift's textEnum would have done for us.
      allWireEnums.forEach((String name, List<WireEnum> values) {
        for (final WireEnum value in values) {
          expect(
            value.wire,
            isNot(matches(RegExp('[A-Z]'))),
            reason: '$name.${value.wire} looks like a Dart identifier',
          );
        }
      });
    });

    test('every value round-trips through its wire string', () {
      allWireEnums.forEach((String name, List<WireEnum> values) {
        for (final WireEnum value in values) {
          expect(
            wireEnumOf<WireEnum>(values, value.wire),
            same(value),
            reason: '$name.${value.wire} did not round-trip',
          );
        }
      });
    });
  });

  group('what remains an enum, and why', () {
    test('only closed sets and state machines are left', () {
      // The test applied: could an admin usefully add a value at runtime?
      //
      //   TradeKind    -- no, a trade is in or out, there is no third case.
      //   SyncStatus   -- no, the sync engine branches exhaustively on it.
      //   UploadStatus -- no, same.
      //   OpType       -- no, each type is a distinct server action.
      //   OpStatus     -- no, the drain branches on it.
      //   AccessStatus -- no, the session gate branches on it.
      //   AuditAction  -- no, though it is stored as lenient raw text.
      //   Permission   -- no. A permission no code checks grants nothing, so
      //                  offering it in an admin screen would be worse than
      //                  not offering it. Roles are data instead.
      //
      // Anything answering "yes" belongs in reference_tables.dart.
      expect(allWireEnums.keys, hasLength(8));
      expect(
        allWireEnums.keys,
        isNot(contains('ExpenseCategory')),
        reason: 'expense categories are user-extensible reference data now',
      );
      expect(allWireEnums.keys, isNot(contains('StockUnit')));
      expect(allWireEnums.keys, isNot(contains('StockTxnType')));
      expect(allWireEnums.keys, isNot(contains('CountReason')));
    });
  });

  group('specific wire strings are pinned', () {
    // These are the contract with the sheet and with every other device.
    // Changing one is a data migration, so a test has to object.
    test('the snake_case cases, which are the ones that get mangled', () {
      expect(OpType.uploadReceipt.wire, 'upload_receipt');
      expect(OpStatus.inFlight.wire, 'in_flight');
      expect(AuditAction.accessChange.wire, 'access_change');
      expect(Permission.receiptStorageManage.wire, 'receipt_storage_manage');
    });
  });

  group('TradeKind carries direction so amounts stay positive', () {
    test('a sale brings money in, a purchase sends it out', () {
      expect(TradeKind.sell.signedMinor(250000), 250000);
      expect(TradeKind.buy.signedMinor(250000), -250000);
    });

    test('the set is closed, which is why it is still an enum', () {
      expect(TradeKind.values, hasLength(2));
    });
  });

  group('SyncStatus', () {
    test('only synced counts as clean', () {
      expect(SyncStatus.synced.isSynced, isTrue);
      for (final SyncStatus status in SyncStatus.values) {
        if (status != SyncStatus.synced) {
          expect(status.isSynced, isFalse, reason: status.wire);
        }
      }
    });

    test('failed and conflicted are the states a user must see', () {
      expect(SyncStatus.failed.needsAttention, isTrue);
      expect(SyncStatus.conflicted.needsAttention, isTrue);
      expect(SyncStatus.pending.needsAttention, isFalse);
      expect(SyncStatus.synced.needsAttention, isFalse);
    });
  });

  group('OpType', () {
    test('only a receipt upload travels outside the sync action', () {
      // §23: receipts have their own path so a failed photo cannot hold up the
      // ledger.
      expect(OpType.uploadReceipt.isRowOperation, isFalse);
      for (final OpType type in <OpType>[
        OpType.upsert,
        OpType.delete,
        OpType.restore,
      ]) {
        expect(type.isRowOperation, isTrue, reason: type.wire);
      }
    });

    test('delete and restore are distinct types, not just row edits', () {
      // Mechanically a soft delete is another row edit, but §90 gates delete
      // and §115 gates restore on their own permissions, so the server has to
      // be told which it is being asked for.
      expect(OpType.values, contains(OpType.delete));
      expect(OpType.values, contains(OpType.restore));
    });
  });

  group('AuditAction', () {
    test('only the actions that never reach the server are local', () {
      // Constraint #8: the server authors the audit trail, because an offline
      // device's row is only as trustworthy as its clock.
      expect(AuditAction.signIn.isLocalOnly, isTrue);
      expect(AuditAction.syncFailure.isLocalOnly, isTrue);
      for (final AuditAction action in <AuditAction>[
        AuditAction.create,
        AuditAction.update,
        AuditAction.delete,
        AuditAction.restore,
        AuditAction.accessChange,
        AuditAction.storageChange,
      ]) {
        expect(action.isLocalOnly, isFalse, reason: action.wire);
      }
    });
  });

  group('Permission catalogue', () {
    test('covers every §90 permission', () {
      // The seventeen the specification names, by their §90 wire form.
      const List<String> fromSpec = <String>[
        'expense_view',
        'expense_create',
        'expense_edit',
        'expense_delete',
        'stock_view',
        'stock_update',
        'livestock_view',
        'livestock_update',
        'medicine_view',
        'medicine_create',
        'user_manage',
        'role_manage',
        'report_view',
        'report_generate',
        'receipt_storage_manage',
        'audit_view',
        'sync_manage',
      ];
      for (final String wire in fromSpec) {
        expect(
          Permission.fromWire(wire),
          isNotNull,
          reason: '§90 names $wire but the catalogue lacks it',
        );
      }
    });

    test('adds the deletes and the restore that §90 omits', () {
      // Documented deviation: the previous app had one blanket delete_entries
      // right, so dropping the non-expense deletes would have silently removed
      // a capability people already had.
      for (final String wire in <String>[
        'stock_delete',
        'livestock_delete',
        'medicine_delete',
        'medicine_edit',
        'record_restore',
      ]) {
        expect(Permission.fromWire(wire), isNotNull, reason: wire);
      }
    });

    test('admin surface is exactly the management permissions', () {
      final Set<Permission> adminSurface = Permission.values
          .where((Permission p) => p.isAdminSurface)
          .toSet();
      expect(adminSurface, <Permission>{
        Permission.userManage,
        Permission.roleManage,
        Permission.receiptStorageManage,
        Permission.auditView,
        Permission.syncManage,
        Permission.reportView,
      });
    });

    test('no data-entry permission opens the admin section', () {
      for (final Permission p in <Permission>[
        Permission.expenseCreate,
        Permission.expenseEdit,
        Permission.expenseDelete,
        Permission.stockUpdate,
        Permission.livestockUpdate,
        Permission.medicineCreate,
      ]) {
        expect(p.isAdminSurface, isFalse, reason: p.wire);
      }
    });

    test('view gates are identified so nobody mistakes them for security', () {
      final Set<Permission> viewGates = Permission.values
          .where((Permission p) => p.isViewOnlyGate)
          .toSet();
      expect(viewGates, <Permission>{
        Permission.expenseView,
        Permission.stockView,
        Permission.livestockView,
        Permission.medicineView,
        Permission.reportView,
        Permission.auditView,
      });
    });

    test('every permission belongs to a group, for the admin UI', () {
      for (final Permission p in Permission.values) {
        expect(p.label, isNotEmpty, reason: p.wire);
        expect(PermissionGroup.values, contains(p.group), reason: p.wire);
      }
    });

    test('parseAll drops unknown entries instead of throwing', () {
      // A newer build may have granted something this one has no concept of;
      // that must not stop the older client from working.
      final Set<Permission> parsed = Permission.parseAll(<String>[
        'expense_view',
        'teleport_goats',
        'sync_manage',
      ]);
      expect(parsed, <Permission>{
        Permission.expenseView,
        Permission.syncManage,
      });
    });

    test('parseAll on an empty list grants nothing', () {
      expect(Permission.parseAll(const <String>[]), isEmpty);
    });
  });

  group('AccessStatus', () {
    test('only approved is approved', () {
      expect(AccessStatus.approved.isApproved, isTrue);
      expect(AccessStatus.pending.isApproved, isFalse);
      expect(AccessStatus.rejected.isApproved, isFalse);
    });
  });

  group('lookup', () {
    test('an unknown wire string returns null, not a valid-looking value', () {
      expect(SyncStatus.fromWire('teleported'), isNull);
      expect(Permission.fromWire('EXPENSE_VIEW'), isNull);
      expect(TradeKind.fromWire('barter'), isNull);
    });

    test('a null wire string returns null', () {
      expect(SyncStatus.fromWire(null), isNull);
      expect(wireEnumOrNull<SyncStatus>(SyncStatus.values, null), isNull);
    });

    test('wireEnumOf throws with the enum name and the offending value', () {
      expect(
        () => wireEnumOf<SyncStatus>(SyncStatus.values, 'nope'),
        throwsA(
          isA<UnknownWireValue>()
              .having((UnknownWireValue e) => e.wire, 'wire', 'nope')
              .having(
                (UnknownWireValue e) => e.enumName,
                'enumName',
                'SyncStatus',
              ),
        ),
      );
    });
  });
}
