import 'package:drift/drift.dart';
import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sqlite3/sqlite3.dart' show sqlite3;

void main() {
  test('package:sqlite3 loads a native library on the host', () {
    // ignore: avoid_print
    print('sqlite3 version: ${sqlite3.version}');
    expect(sqlite3.version.libVersion, isNotEmpty);
  });

  test('drift NativeDatabase.memory() opens and answers a query', () async {
    final NativeDatabase db = NativeDatabase.memory();
    await db.ensureOpen(_NoopUser());
    final List<Map<String, Object?>> rows = await db.runSelect(
      'select 1 as one;',
      const <Object?>[],
    );
    expect(rows.single['one'], 1);
    await db.close();
  });
}

class _NoopUser extends QueryExecutorUser {
  @override
  int get schemaVersion => 1;

  @override
  Future<void> beforeOpen(
    QueryExecutor executor,
    OpeningDetails details,
  ) async {}
}
