# Tasks

Working checklist for the build in [PLAN.md](PLAN.md). Phases are detailed as they are approached —
enumerating 400 tasks for P17 today would be invented precision, not planning.

Legend: `[ ]` open · `[x]` done · `[~]` blocked or partial · **§n** = a REQUIREMENTS.md section.

---

## P0 — Scrap and scaffold

- [x] Delete the Kotlin app, Apps Script gateway, `docs/`, `supabase/`, Gradle build (one commit)
- [x] Verify the deletion is recoverable (old tree at `728f94c`, present on `origin/main`)
- [x] Commit `REQUIREMENTS.md` as the specification of record (byte-exact, SHA-256 verified)
- [x] Verify every section citation in `CLAUDE.md` resolves (28/28)
- [x] Rewrite `CLAUDE.md` around the eight constraints
- [x] Rewrite `README.md`
- [x] Flutter `.gitignore`; verify `config/*.json` ignored, `*.example.json` and `pubspec.lock` tracked
- [x] `config/dev.example.json` + `config/README.md`
- [x] `flutter create . --project-name sankranthi --org com.sankranthi --platforms android --empty`
- [x] Set `applicationId`/`namespace` to `com.sankranthi.ledger`; move `MainActivity.kt` to match
- [x] Harden `analysis_options.yaml` (strict casts/inference/raw-types; `unawaited_futures` to error)
- [x] `AppConfig` with `missingKeys`, and a test for the unconfigured case
- [x] `flutter analyze` clean and `flutter test` green
- [~] `flutter doctor` clean for Android — needs a JDK 17-24 and cmdline-tools (Finding 4)
- [ ] `flutter run` on a real device
- [ ] Release signing config (`android/key.properties`, gitignored) — constraint #6

## P0b — Spikes

- [x] **Spike 1:** `redirectSafePost` with a bounded manual re-POST
- [x] Test: a plain `dio.post` never reaches the redirect target
- [x] Test: `dart:io` does not convert POST to GET (guards against the silent mode appearing later)
- [x] Test: body survives the redirect; relative `Location` resolved; missing `Location` throws; hop limit
- [ ] Confirm against a real deployed `/exec` once P4 exists
- [~] **Spike 2:** `drive.file` consent, silent re-authorization, and whether it works from a background
      isolate. Blocked on a device plus a Google Cloud OAuth client. Its answer decides whether receipt
      upload can run in the background at all, so it gates the shape of P9 and P18.

## P1 — Drift schema and value types

### Value types (everything else depends on these)

- [x] `Money` — `int` paise; `parseToMinor` refuses negatives and sub-paise rather than rounding; format
      with hand-rolled Indian (2,2,3) grouping; 26 tests
- [x] `Quantity` — `int` quantityMilli (thousandths); exact round-trip; truncates rather than rounds; 21 tests
- [x] `Dates` — ISO `yyyy-MM-dd` end to end; strict parsing; UTC-midnight epoch round-trip; 20 tests

### Enums and conversion

- [ ] `WireEnum` contract: every enum carries an explicit `wire` string and a `fromWire`
- [ ] `WireEnumConverter<T>` — the only enum-to-column mapping in the system. Never `textEnum`
- [ ] Enums: `SyncStatus`, `TradeKind`, `ExpenseCategory`, `StockTxnType`, `StockUnit`, `CountReason`,
      `UploadStatus`, `Permission`, `AccessStatus`, `AuditAction`, `OpType`, `OpStatus`
- [ ] Test: every enum's wire values are unique, lowercase with underscores, and round-trip
- [ ] Test: an unknown wire string is handled deliberately, never silently defaulting to a *valid* state

### Shared columns

- [ ] `SyncMetaColumns` mixin: `version`, `serverSeq`, `serverUpdatedAt`, `syncStatus`, `deleted`,
      `deletedAt`, `deletedBy`, `createdAt`, `updatedAt`, `createdBy`, `updatedBy`, `createdByName`
- [ ] No per-row `dirty` flag — dirtiness *is* `syncStatus != synced`

### Tables

- [ ] Ledger: `expenses` (§27), `livestock_trades`, `receipts` (§28, with `storageConfigId`, nullable
      `driveFileId`, and no separate `metadataSyncStatus` per constraint #7)
- [ ] Stock: `stock_items` (no `currentQuantity` — Finding 2), `stock_transactions` (§32)
- [ ] Livestock: `livestock_categories`, `livestock_count_events` (signed `delta` plus reason, not §33's
      `previousCount`/`newCount`)
- [ ] Medicine: `medicine_records` (§34)
- [ ] Access: `users` (§29), `roles` (§30/§31)
- [ ] Audit: `audit_logs` (§35) — download-only mirror, the client never writes it
- [ ] Config: `receipt_storage_configurations` (§49) — download-only, seeded with one row
- [ ] Sync: `sync_operations` (§18, plus `nextAttemptAt`, `dependsOnOpId`, `status`), `sync_state`,
      `device_identity` (§112), `pending_downloads`, `conflict_resolutions`

### Views, indexes, migrations

- [ ] `stock_item_balances` view — `SUM` over signed quantities, never a stored column
- [ ] `livestock_herd_on_hand` view — `SUM(delta)` per category
- [ ] Indexes per §83, as partial indexes (`WHERE deleted = 0`);
      `stock_transactions(stockItemId, occurredOn)` is the hot one
- [ ] Non-unique index on `(categoryId, countedOn)` — a UNIQUE constraint would make a legitimate download
      unapplicable
- [ ] `schemaVersion = 1`; no `deleteEverything()` and no destructive fallback
- [ ] `PRAGMA foreign_keys = ON` in `beforeOpen`; `defer_foreign_keys` inside download batches
- [ ] Commit `drift_schemas/` snapshots (`drift_dev schema dump`, then `steps`)
- [ ] Seed data: default livestock categories, stock units, one storage config

### P1 exit criteria

- [ ] Money refuses negatives and sub-paise (asserted, not rounded)
- [ ] Quantity round-trips exactly
- [ ] `validateDatabaseSchema` passes
- [ ] A v1-to-v1 migration harness runs
- [ ] Deferred FKs accept child-before-parent within a batch and still reject a dangling reference

## P2 — Repositories and the outbox

- [ ] Write path per domain: one transaction, `upsert(row)` plus `markDirty(...)` (§26)
- [ ] Coalescing: no second UPSERT op for the same entity; `payload` stays empty
- [ ] Soft delete routed through the same dirty path — no separate DELETE op
- [ ] FIFO by `createdAt`, tie-broken by `rowid`
- [ ] `advanceSeq` monotonic guard — the cursor never rewinds
- [ ] `purgeTombstones(upToSeq)`
- [ ] Test: save is readable immediately; a new row is pending and queued
- [ ] Test: three edits coalesce to one operation
- [ ] Test: a local edit does not reset the server-owned `version`
- [ ] Test: delete leaves a queued tombstone; deleting an unknown id is a no-op
- [ ] Test: tombstones purge only once synced and only up to the cursor
- [ ] Test: an offline record and its queued operation survive process restart, via an on-disk
      close-and-reopen. An in-memory database passes this while proving nothing.

## P3 — Design system

- [ ] Committed light and dark `ColorScheme`s from seed `#1B5E20`; dynamic colour off by default
- [ ] `SankranthiColors` theme extension: `credit`, `debit`, `pending`, `synced`, `failed`, `offline`
- [ ] Typography: one family plus a Noto Sans Telugu fallback; tabular figures on money and counts
- [ ] `MoneyText` and `QuantityText`
- [ ] Sealed `Failure` plus `FailureMapper` (never `String?` in UI state) and an exhaustiveness test
- [ ] CI check: no `toString()` on error objects under `lib/features/`
- [ ] Components: `SummaryTile`, `SectionHeader`, `EmptyState` (with an action slot), `ErrorBanner`,
      `OfflineBanner`, `AppSnackbar`, `ConfirmSheet`, `DateField`, `PickerField`, sync chip, dual-status row
- [ ] Gallery route; component goldens light and dark, with font and device size pinned

## P4 — Gateway v1 (Apps Script, written fresh)

- [ ] Declarative `SCHEMA` plus `COLUMN_TYPES` driving column order, coercion and writability
- [ ] Permission table with an explicit deny default. An if-chain returning "none required" is fail-open
- [ ] Actions: `ping`, `session`, `signOut`, `sync`, `adminUpdateUser`
- [ ] Idempotency: `AppliedOps` stores the *result* by `opId`; `serverSeq` allocated inside the lock;
      `AppliedOps` read once per request, not once per operation
- [ ] Optimistic concurrency: compare `baseVersion`, return CONFLICT with the server row attached
- [ ] Server-authored audit append inside the same lock
- [ ] `Devices` plus `_Meta.purgeHighWaterMark`, returned as `purgeBelowSeq`
- [ ] `adminUpdateUser` refuses a self role or status change
- [ ] Sessions: ID token to opaque sliding 90-day token, SHA-256 only, `SESSION_EXPIRED` distinct from
      `UNAUTHORISED`
- [ ] Unknown failures are retryable
- [ ] Node test suite against a fake `SpreadsheetApp` and `LockService`

## P5 to P20

Expanded as each phase is reached. Headline deliverables and exit criteria are in [PLAN.md](PLAN.md):
P5 wire layer · P6 auth, RBAC, navigation · P7 sync engine · P8 expenses · P9 receipts ·
P10 sync visibility · P11 livestock counts · P12 stock · P13 medicine · P14 soft delete and restore ·
P15 audit and activity feed · P16 reports and export · P17 settings and polish · P18 background sync ·
P19 receipt storage rotation · P20 multi-device hardening.

---

## Cross-cutting, every phase

- [ ] `flutter analyze` clean and `flutter test` green before a phase is called done
- [ ] Anything touching `lib/data/` also runs `flutter test integration_test/` on a real device
- [ ] Negative widget assertions sit in the same test as a positive control, keyed on a `ValueKey`
- [ ] A new write path gets its server-side permission check in the same change (§61)
