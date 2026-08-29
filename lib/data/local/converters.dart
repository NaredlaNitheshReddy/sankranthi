import 'package:drift/drift.dart';

import '../../core/wire_enum.dart';
import '../../domain/access_enums.dart';
import '../../domain/ledger_enums.dart';
import '../../domain/sync_enums.dart';

/// Maps a [WireEnum] to and from its stored [WireEnum.wire] string.
///
/// **This is the only enum-to-column mapping in the system.** Never use drift's
/// `textEnum`: it stores the Dart identifier, so `shedRepair` would be written
/// where the sheet and every query expect `shed_repair`. See CLAUDE.md.
///
/// There is no default constructor on purpose. Reading a value this build does
/// not recognise is a real situation -- a newer app version wrote it, or a
/// partner edited the sheet by hand -- and what to do about it differs per
/// enum, so the choice is forced at the use site:
///
/// * [WireEnumConverter.strict] throws. Correct where any substitute would be a
///   lie, e.g. an expense category.
/// * [WireEnumConverter.fallingBackTo] substitutes a value you have argued is
///   *safe*. Safe means "wrong in the direction that loses nothing".
///
/// The canonical example is [SyncStatus]: falling back to `pending` means a row
/// might be uploaded again, which the server deduplicates by `opId` and costs
/// nothing. Falling back to `synced` would mark a dirty row clean, and the
/// record would never reach the other partners. Same mechanism, opposite
/// consequence -- which is why this cannot have a default.
class WireEnumConverter<T extends WireEnum> extends TypeConverter<T, String> {
  /// Throws [UnknownWireValue] on a value this build does not know.
  const WireEnumConverter.strict(this.values) : onUnknown = null;

  /// Substitutes [fallback] on a value this build does not know.
  ///
  /// Document at the call site why [fallback] is the conservative choice.
  const WireEnumConverter.fallingBackTo(this.values, T fallback)
    : onUnknown = fallback;

  /// Every value of the enum, normally `MyEnum.values`.
  final List<T> values;

  /// What to return for an unrecognised stored value; null means throw.
  final T? onUnknown;

  @override
  T fromSql(String fromDb) {
    final T? found = wireEnumOrNull<T>(values, fromDb);
    if (found != null) {
      return found;
    }
    final T? fallback = onUnknown;
    if (fallback == null) {
      throw UnknownWireValue(T.toString(), fromDb);
    }
    return fallback;
  }

  @override
  String toSql(T value) => value.wire;
}

/// A [WireEnumConverter] for a nullable column, where SQL NULL means "no
/// value" and is distinct from any enum member.
class NullableWireEnumConverter<T extends WireEnum>
    extends TypeConverter<T?, String?> {
  const NullableWireEnumConverter(this._inner);

  final WireEnumConverter<T> _inner;

  @override
  T? fromSql(String? fromDb) =>
      fromDb == null ? null : _inner.fromSql(fromDb);

  @override
  String? toSql(T? value) => value == null ? null : _inner.toSql(value);
}

// ---------------------------------------------------------------------------
// The canonical converters.
//
// Every table uses these rather than constructing its own, so each enum's
// unknown-value decision is made once, argued once, and testable. The rule
// applied below: fall back only where the fallback provably loses nothing; be
// strict wherever a wrong guess would corrupt a number or invert a meaning.
// ---------------------------------------------------------------------------

/// Falls back to [SyncStatus.pending].
///
/// An unnecessary re-upload is deduplicated by `opId` and costs nothing.
/// Falling back to `synced` would mark a dirty row clean and the record would
/// never reach the other partners.
const WireEnumConverter<SyncStatus> syncStatusConverter =
    WireEnumConverter<SyncStatus>.fallingBackTo(
      SyncStatus.values,
      SyncStatus.pending,
    );

/// Falls back to [UploadStatus.pending].
///
/// Safe because §25 is enforced by `driveFileId`, not by this column: a row
/// that already has a Drive id is excluded from the upload query regardless of
/// what this says, so the worst case is a receipt being reconsidered, not
/// uploaded twice.
const WireEnumConverter<UploadStatus> uploadStatusConverter =
    WireEnumConverter<UploadStatus>.fallingBackTo(
      UploadStatus.values,
      UploadStatus.pending,
    );

/// Falls back to [OpStatus.queued].
///
/// Retrying is safe -- that is what `opId` is for. Falling back to
/// `failedPermanent` would silently strand the operation, and §103 forbids
/// losing queued work.
const WireEnumConverter<OpStatus> opStatusConverter =
    WireEnumConverter<OpStatus>.fallingBackTo(
      OpStatus.values,
      OpStatus.queued,
    );

/// Falls back to [ExpenseCategory.other].
///
/// A category is a label and a report bucket; the amount, date and author are
/// unaffected. Keeping the row readable in the wrong bucket beats throwing and
/// taking the whole expense list down over one unfamiliar value.
const WireEnumConverter<ExpenseCategory> expenseCategoryConverter =
    WireEnumConverter<ExpenseCategory>.fallingBackTo(
      ExpenseCategory.values,
      ExpenseCategory.other,
    );

/// Falls back to [AccessStatus.pending].
///
/// The only fallback here that is a security decision: on an unrecognised
/// status, deny. An approved user briefly seeing the waiting screen is
/// recoverable; defaulting to `approved` would hand the books to an account
/// nobody vouched for.
const WireEnumConverter<AccessStatus> accessStatusConverter =
    WireEnumConverter<AccessStatus>.fallingBackTo(
      AccessStatus.values,
      AccessStatus.pending,
    );

/// Strict: which direction a trade went decides the sign of the money.
const WireEnumConverter<TradeKind> tradeKindConverter =
    WireEnumConverter<TradeKind>.strict(TradeKind.values);

/// Strict: the type decides whether a movement adds to or subtracts from a
/// balance, so a wrong guess corrupts the stock on hand.
const WireEnumConverter<StockTxnType> stockTxnTypeConverter =
    WireEnumConverter<StockTxnType>.strict(StockTxnType.values);

/// Strict: the unit is what makes a quantity mean anything. Showing "12.5 kg"
/// for a value recorded in litres is worse than showing an error.
const WireEnumConverter<StockUnit> stockUnitConverter =
    WireEnumConverter<StockUnit>.strict(StockUnit.values);

/// Strict: the domain is small and closed, and a count event whose reason is
/// unknown cannot be reported on honestly.
const WireEnumConverter<CountReason> countReasonConverter =
    WireEnumConverter<CountReason>.strict(CountReason.values);

/// Strict: an operation whose intent is unknown must never be guessed at --
/// mistaking it could delete a record or resurrect one.
const WireEnumConverter<OpType> opTypeConverter =
    WireEnumConverter<OpType>.strict(OpType.values);

/// Nullable variants, for columns where SQL NULL means "not set".
const NullableWireEnumConverter<StockUnit> nullableStockUnitConverter =
    NullableWireEnumConverter<StockUnit>(stockUnitConverter);

const NullableWireEnumConverter<CountReason> nullableCountReasonConverter =
    NullableWireEnumConverter<CountReason>(countReasonConverter);

// Deliberately absent: a converter for AuditAction.
//
// `audit_logs` is a download-only mirror of a server-authored table
// (constraint #8), so a newer build introducing an action this one has never
// heard of is expected rather than exceptional. The column stays raw text and
// the mapper parses it leniently, showing the unrecognised value as itself. A
// strict converter would let one unfamiliar row break the whole audit screen;
// a fallback would misreport what somebody actually did.
