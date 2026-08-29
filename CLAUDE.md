# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

**Sankranthi** — a local-first Android app for a small livestock organisation (~10 people). It records
expenses and receipts, stock, livestock counts, medicine and treatments, with users/roles/permissions,
reports, an audit trail, and background synchronisation to a shared backend.

**[REQUIREMENTS.md](REQUIREMENTS.md) is the specification.** It is the only one. When something here and
something there disagree, REQUIREMENTS.md wins — *except* for the eight constraints below, which are
deliberate corrections to it and say so.

The repository was rebuilt from scratch in Flutter on 2026-08-29. An earlier Kotlin/Compose client and an
Apps Script gateway were deleted; they are in git history before that commit if you ever need them.

## The architecture in one line

**The UI reads the local database and only the local database.** A write goes to SQLite and enqueues a sync
operation in the same transaction, then returns. The screen updates because the *database* changed, not
because a callback fired. Nothing in the UI ever waits for the network.

```
UI  ──►  Riverpod (StreamProvider over a drift .watch())
             ▲ reads only
Controller ──►  Repository  ──►  one drift transaction { upsert row; enqueue operation }
                                        │
                                        ▼
                            drift/SQLite  +  sync_operations outbox
                                        │
                            SyncEngine  ──►  SyncRemote (THE SEAM)
                                                  │
                                     Apps Script gateway  +  Google Drive
```

## Eight constraints

These are load-bearing, not stylistic. Four are corrections **to** REQUIREMENTS.md. They were expensive to
discover and are not re-derivable from the spec, so they live here.

1. **The app must never call the Google Sheets API.** The `spreadsheets` OAuth scope is classified
   *sensitive*; an app holding it pins the OAuth consent screen in Testing status, where Google expires
   refresh tokens every **7 days**. "Stays signed in" then breaks a week after install and no client code
   can fix it. The Apps Script Web App exists precisely so the client needs only `openid email profile`.
   **Do not add a Google Sheets client library.**
   `drive.file` — per-file access to files the app itself created — is *not* sensitive, which is what makes
   direct Drive upload of receipts viable.

2. **A `syncStatus` column is not a sync queue** (contra the shape implied by REQUIREMENTS §16 + §27).
   Deduplication must key on the *operation*, not the record, or a retried write becomes a second row.
   Hence a separate `sync_operations` outbox with a UUID `opId` (§17/§18). **`opId` is generated once and
   reused across every retry — that reuse is the entire duplicate-prevention mechanism** (§19).

3. **Client clocks are not conflict resolution — REQUIREMENTS §87 is rejected as written.** Device clocks
   are user-settable; a phone with a wrong clock would win permanently and unrecoverably. Use optimistic
   concurrency: the client sends `baseVersion`, the server compares and rejects. Last-write-wins is then a
   *client-side resolution policy over server version ordering* — §87's intent without the clock dependency.

4. **Soft deletes need a purge policy** (§36/§103 give none). Purge only rows that are
   `deleted AND synced AND serverSeq <= min(lastSeq across all devices)`, which is why the server tracks
   per-device cursors and returns `purgeBelowSeq`. **A purged tombstone can never be restored**, so the
   Deleted Records view must state the retention window.

5. **Bound receipt sizes before upload** (§105 says "compress" without limits). ≤1024 px longest edge,
   JPEG q80, hard reject above the stated ceiling.

6. **Ship a signed release build, not a debug APK.** A debug APK is unshippable and its signing key is not
   yours.

7. **§98's separate `metadataSyncStatus` column is collapsed** into the shared `syncStatus`, which already
   means "does this row match the server". Two columns with one meaning can disagree and there is no rule
   for which is right. The doc's vocabulary survives as a Dart getter.

8. **§35's audit log is written server-side, not by the client.** An offline device's audit row is only as
   trustworthy as its clock (see #3). The gateway appends audit rows inside its own lock, using server time.
   Client-side rows only for local-only events (sign-in, app lock, sync failure) that never reach the server.

## Rules that lose data if broken

- **The row and its outbox operation commit together, in one transaction.** A row without its operation
  never reaches the other partners; an operation without its row uploads something the app cannot show.
  Both lose data.
- **`version`, `serverSeq` and `serverUpdatedAt` are server-owned.** A local edit carries them forward
  untouched, or the upload conflicts with itself.
- **There is no per-row `dirty` flag.** Dirtiness *is* `syncStatus != synced`. Do not add one.
- **Deletes are soft** (`deleted = true`) so removals propagate, and are routed through the same
  dirty-marking path as an edit. There is never *both* an UPDATE and a DELETE operation queued for one row:
  the single coalesced operation's **type** changes instead, so there is no ordering question to get wrong.
  `OpType.delete` and `OpType.restore` do exist as types, because §90 and §115 gate them on their own
  permissions and the server has to know which it is being asked for.
- **Outbox operations are coalesced per entity.** One operation means "this row is dirty"; the uploader
  reads current state at send time. Enqueuing one per edit would give them all the same stale `baseVersion`.
- **A pending local edit is never overwritten by a download.** If an incoming row targets a row that is not
  synced, it goes to `pending_downloads` (highest-seq-wins); the cursor still advances; it reconciles after
  the local operation uploads.
- **Mark a row synced only if its `updatedAt` still matches what was sent.** Otherwise an edit made while
  the request was in flight is silently dropped.
- **Never delete a sync operation after a transient failure** (§103). Poison operations become
  `failedPermanent` and are skipped, never removed.
- **Receipt sync and ledger sync are independent** (§23) — two drain loops sharing nothing but the database.
  An expense whose photo failed is not a failed expense.
- **If `driveFileId != null`, the binary is in Drive: never upload it again** (§25). Only metadata retries.
- **`receiptId` is a client UUID and is the permanent identity; `driveFileId` is a nullable remote
  reference** (§11/§122). A receipt captured offline has `driveFileId = NULL` and that is valid.
- **Never `fallbackToDestructiveMigration` or drift's `deleteEverything()`.** §84's "never require users to
  uninstall to update the schema" also means never quietly dropping their unsynced records.
- **Sign-out does not wipe the local database while `sync_operations` is non-empty** — that is silent data
  loss. Refuse, or require an explicit typed confirmation naming the count.

## Security

- **The database and the gateway are the enforcement points, not the UI.** Hiding a button is courtesy
  (§61). Every write permission is checked server-side. When you add a feature that writes, add the
  server-side permission mapping in the same change.
- **Permission lookup on the server denies by default.** An unrecognised entity type must be *rejected*, not
  treated as "no permission needed" — otherwise adding a sheet and forgetting the mapping silently opens it.
- **An admin cannot change their own role or status**, so the partnership cannot lock itself out of its own
  books. Enforced server-side and mirrored in the UI.
- **Reads are all-or-nothing.** Any approved account holds the whole local database. `*_VIEW` permissions
  gate navigation and UI only and are **not a confidentiality boundary** — do not describe them as one.
- **The secure-storage key must not require user presence.** A biometric-gated key means a background sync
  on a locked phone cannot read the session token, so a record created offline sits unsynced. The biometric
  lock (§59) is a **UI gate only.** This is the one place where "make it more secure" makes it worse.
- **Never embed a service-account key, API secret or private credential in the APK** (§60). Config comes
  from `--dart-define-from-file`, which is gitignored.
- **`deviceId` is a generated UUID, never a hardware identifier** (§112).

## Stack

| Piece | Choice |
| --- | --- |
| Framework | Flutter, Material 3, Dart 3 (sound null safety) |
| Local store | `drift` + `sqlite3_flutter_libs` + `drift_flutter` |
| State + DI | `flutter_riverpod` — also the DI container; no get_it/injectable |
| Routing | `go_router`, one `redirect` driven by session state |
| HTTP | `dio`, `followRedirects: false` (see gotchas) |
| Sign-in | `google_sign_in` ^7.x, `serverClientId` = the **web** client id |
| Secure storage | `flutter_secure_storage`, one JSON blob |
| Images | `image_picker` + `flutter_image_compress` |
| Connectivity | `connectivity_plus` — a trigger, never a precondition |
| Biometrics | `local_auth`, `biometricOnly: false` |
| Background | `workmanager`, opt-in and removable |
| Backend | Apps Script Web App in front of one Google Sheet; receipts direct to Drive via `drive.file` |
| Codegen | **drift only** |

**Rejected on purpose:** get_it/injectable (Riverpod covers it), freezed, json_serializable (the wire
envelope is ~6 hand-written classes plus a `RowCodec` registry), bloc, provider, retrofit,
`package:decimal`, `camera`, and any Google Sheets client library (constraint #1).

Dependencies go in `pubspec.yaml` via `flutter pub add`; **commit `pubspec.lock`** — this is an app, not a
library.

## Gotchas worth knowing before you edit

- **Apps Script answers POST with a 302** to `script.googleusercontent.com`, and nothing reaches `doPost`
  unless the POST is re-issued to that `Location`. Use `redirectSafePost`
  ([lib/data/remote/redirect_safe_post.dart](lib/data/remote/redirect_safe_post.dart)), not a bare
  `dio.post`. **Measured on this platform** (`redirect_safe_post_test.dart` pins it): `dart:io` only
  auto-follows GET/HEAD, so a POST 302 comes straight back even with `followRedirects = true` — dio throws
  and `package:http` hands you an empty body. It fails *loudly* here, which is the good case. The silent
  variant — where the redirect is followed as a GET and `doGet`'s reply arrives as a cheerful HTTP 200 with
  your payload gone — is what browsers, `curl -L`, Python `requests` and JS `fetch` do. So don't port a
  recipe from those and assume it maps, and note that adding a Flutter web target would make it live.
- **Never use drift's `textEnum<T>()`.** It persists the Dart identifier (`shedRepair`) while the sheet
  expects `shed_repair` — two representations, silent mismatch, rows that never match a query. Every enum
  declares an explicit `wire` string and goes through `WireEnumConverter<T>`.
- **Money is `int` paise, quantity is `int` quantityMilli (thousandths). Never `double`.** Stock balance is
  *derived on every read*, so float error compounds silently and two devices end up disagreeing on a
  balance. `parseToMinor` refuses negatives and sub-paise rather than rounding.
- **Dates are ISO `yyyy-MM-dd` strings end to end.** No timezone ambiguity, and it matches the sheet.
- **Stock balance and herd on-hand are drift `View`s over `SUM`, never stored columns — this deliberately
  drops §32's `StockItem.currentQuantity`.** A stored balance is a second source of truth that sync can
  desynchronise, leaving you two numbers and no rule for which is right. §43's own "prefer transaction
  history rather than blindly overwriting stock quantities" is the same instruction. Do not restore it.
- **Livestock counts are signed delta events with a reason, not §33's `{previousCount, newCount}`.** Two
  devices offline both incrementing must *sum*; absolute snapshots overwrite and one device's count is lost.
- **Do not put a UNIQUE constraint on `(categoryId, countedOn)`** even though one count per day looks
  obviously right. Two devices offline both record today's count, and the constraint then makes a legitimate
  *download* unapplicable.
- **Enable `PRAGMA foreign_keys = ON` in `beforeOpen`** — drift does not do this by default — and use
  `defer_foreign_keys` inside each download batch, because changes arrive ordered by `serverSeq` and a child
  can precede its parent within one page.
- **`newSeq` is the highest seq actually returned, never guessed.** Advancing past rows you were not sent
  skips them forever. The cursor never rewinds.
- **`android/local.properties` and `config/*.json` are gitignored.** Read config through `AppConfig`, never
  `String.fromEnvironment` at a call site.
- **`local_auth` needs `FlutterFragmentActivity`**, not `FlutterActivity`. This fails at runtime only.
- **Dart `int` is 64-bit on native but becomes a JS double on web.** There is no web target; if anyone adds
  `-d chrome`, paise arithmetic breaks silently.

## Conventions

- **`data/remote/sync_remote.dart` is the seam.** Nothing in `lib/features/` or `lib/domain/` may import an
  implementation of it (§62/§94). DTOs only — the seam must not leak drift row types outward.
- **Never read from the network in a controller or widget.** The only path to a backend is the sync engine.
- Screens take state and callbacks and hold no controller reference, so they stay previewable and testable.
- **UI state holds a sealed `Failure?`, never a `String?`.** §77 forbids showing `SocketException` or
  `HTTP 500`; making failure a *type* means the type system makes a raw exception unrepresentable in the UI
  layer instead of relying on everyone remembering. A `FailureMapper` produces `{title, body, action}`.
- Gate every write affordance on the user's effective permissions, and add the server-side check in the same
  change.
- **Editors are modal bottom sheets (≤3 fields, quick picks) or full-screen routes (keyboard-heavy forms,
  anything with a camera step).** Never a scrolling dialog.
- **A local write pops the route immediately** with a snackbar — no spinner, no `loading: true`. It is one
  SQLite transaction; there is nothing to wait for (§104).
- **Pull-to-refresh means "sync now"**, not "reload from the database" — the database is already live.
- **Skeletons only on cold load with no cached rows.** A skeleton over data you already have is a
  regression; refreshing shows a thin line over real content (§75).
- Money and counts use tabular figures, via `MoneyText` / `QuantityText` — never a bare format call.
- Colours come from the committed `ColorScheme`s and the `SankranthiColors` theme extension
  (`credit`/`debit`/`pending`/`synced`/`failed`/`offline`). **Never render money in `colorScheme.error`** —
  an expense is not an error.
- Permissions are requested **contextually** (§58), never at onboarding.

## Commands

```bash
flutter pub get
flutter analyze                    # strict-casts, strict-inference, strict-raw-types
flutter test                       # unit + widget + golden
flutter test integration_test/     # needs a device or emulator
flutter run --dart-define-from-file=config/dev.json
flutter build apk --release        # signed; see constraint #6
dart run drift_dev schema dump     # then `schema steps` — snapshots are committed
node --test gateway/test/          # the Apps Script suite
```

## Before saying a change works

```bash
flutter analyze && flutter test
```

must pass. **Compiling is not evidence.** The storage and sync layers' real failures appear only at
runtime, so any change under `lib/data/` needs `flutter test integration_test/` on a real device or
emulator. Two traps that cost real time:

- **Assert on stable `ValueKey`s, never on text**, and put every negative assertion in the same test as a
  positive control. A `findsNothing` that passes because the finder was wrong looks exactly like a working
  permission gate.
- **Pin the font and the device size in golden tests**, or they flake across machines.
