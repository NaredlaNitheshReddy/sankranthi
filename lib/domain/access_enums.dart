/// Enums for the access model (§29, §30, §31, §61, §88, §90).
library;

import '../core/wire_enum.dart';

/// May this account see the books at all (§88)?
///
/// Independent of what it may *change*: a pending account with every permission
/// set can still do nothing, because approval is checked first.
enum AccessStatus implements WireEnum {
  /// Signed in, waiting for an admin. The state every new account starts in --
  /// nobody self-serves.
  pending('pending', 'Pending'),

  /// May use the app.
  approved('approved', 'Approved'),

  /// Refused, or access revoked.
  rejected('rejected', 'Rejected');

  const AccessStatus(this.wire, this.label);

  @override
  final String wire;

  final String label;

  bool get isApproved => this == AccessStatus.approved;

  static AccessStatus? fromWire(String? wire) =>
      wireEnumOrNull(AccessStatus.values, wire);
}

/// How permissions are grouped for the admin screens.
enum PermissionGroup {
  expenses('Expenses'),
  stock('Stock'),
  livestock('Livestock'),
  medicine('Medicine'),
  records('Records'),
  reporting('Reporting'),
  administration('Administration');

  const PermissionGroup(this.label);

  final String label;
}

/// What an account may do (§90).
///
/// **Permissions are code, not data.** A permission this build has never heard
/// of cannot gate anything, so a data-driven catalog would buy nothing and cost
/// type safety. Roles, by contrast, *are* data (§30) so an admin can change who
/// holds what without shipping a release.
///
/// ### Where this differs from §90
///
/// §90 lists seventeen permissions and says the set "should be extensible".
/// Five are added here:
///
/// * `stock_delete`, `livestock_delete`, `medicine_delete` and `medicine_edit` --
///   §90 gives a delete only for expenses, but the previous app had one blanket
///   `delete_entries` right covering every domain. Dropping the others would
///   have been a silent loss of an existing capability; splitting them per
///   domain is what makes the migration lossless.
/// * `record_restore` -- §115 requires restore to be a distinct operation, and
///   §61 requires the server to check it. Without its own permission a restore
///   would inherit the *edit* right, so anyone who could edit could resurrect
///   anything anyone had deleted.
///
/// ### The `*_view` permissions are not a confidentiality boundary
///
/// They gate navigation and affordances only. Every approved account downloads
/// and holds the entire local database, because the download cursor is a single
/// monotonic sequence: filtering it per user would mean an account later granted
/// `stock_view` never receives the rows its cursor already advanced past.
/// Read partitioning is a backend change, not a client flag. Do not describe
/// these as protecting anything. See CLAUDE.md.
enum Permission implements WireEnum {
  expenseView('expense_view', 'View expenses', PermissionGroup.expenses),
  expenseCreate('expense_create', 'Add expenses', PermissionGroup.expenses),
  expenseEdit('expense_edit', 'Edit expenses', PermissionGroup.expenses),
  expenseDelete('expense_delete', 'Delete expenses', PermissionGroup.expenses),

  stockView('stock_view', 'View stock', PermissionGroup.stock),
  stockUpdate('stock_update', 'Update stock', PermissionGroup.stock),
  stockDelete('stock_delete', 'Delete stock records', PermissionGroup.stock),

  livestockView('livestock_view', 'View livestock', PermissionGroup.livestock),
  livestockUpdate(
    'livestock_update',
    'Update livestock',
    PermissionGroup.livestock,
  ),
  livestockDelete(
    'livestock_delete',
    'Delete livestock records',
    PermissionGroup.livestock,
  ),

  medicineView('medicine_view', 'View medicine', PermissionGroup.medicine),
  medicineCreate(
    'medicine_create',
    'Add treatments',
    PermissionGroup.medicine,
  ),
  medicineEdit('medicine_edit', 'Edit treatments', PermissionGroup.medicine),
  medicineDelete(
    'medicine_delete',
    'Delete treatments',
    PermissionGroup.medicine,
  ),

  recordRestore(
    'record_restore',
    'Restore deleted records',
    PermissionGroup.records,
  ),

  reportView('report_view', 'View reports', PermissionGroup.reporting),
  reportGenerate(
    'report_generate',
    'Export reports',
    PermissionGroup.reporting,
  ),

  userManage('user_manage', 'Manage users', PermissionGroup.administration),
  roleManage('role_manage', 'Manage roles', PermissionGroup.administration),
  receiptStorageManage(
    'receipt_storage_manage',
    'Manage receipt storage',
    PermissionGroup.administration,
  ),
  auditView('audit_view', 'View audit log', PermissionGroup.administration),
  syncManage('sync_manage', 'Manage sync', PermissionGroup.administration);

  const Permission(this.wire, this.label, this.group);

  @override
  final String wire;

  final String label;

  final PermissionGroup group;

  /// Whether holding this makes the admin section reachable.
  ///
  /// Admin surface is derived from permissions, not from a role check, so an
  /// audit-only or reports-only role works without any new plumbing.
  bool get isAdminSurface => switch (this) {
    Permission.userManage ||
    Permission.roleManage ||
    Permission.receiptStorageManage ||
    Permission.auditView ||
    Permission.syncManage ||
    Permission.reportView => true,
    _ => false,
  };

  /// Whether this only gates UI, per the note above.
  bool get isViewOnlyGate => wire.endsWith('_view');

  static Permission? fromWire(String? wire) =>
      wireEnumOrNull(Permission.values, wire);

  /// Parses a stored permission list, ignoring values this build does not know.
  ///
  /// Unknown entries are dropped rather than throwing: a newer build may have
  /// granted a permission this one has no concept of, and that must not stop an
  /// older client from working. The raw list is kept alongside the parsed set by
  /// the profile model so a round-trip through an older client is
  /// non-destructive.
  static Set<Permission> parseAll(Iterable<String> wires) {
    final Set<Permission> found = <Permission>{};
    for (final String wire in wires) {
      final Permission? permission = Permission.fromWire(wire);
      if (permission != null) {
        found.add(permission);
      }
    }
    return found;
  }
}
