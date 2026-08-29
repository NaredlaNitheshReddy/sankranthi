# Sankranthi — clean-slate Flutter build, phased plan

> **Status:** live document. Phase status is tracked in [TASKS.md](TASKS.md); findings that changed the
> plan after approval are logged at the bottom of this file. The specification is
> [REQUIREMENTS.md](REQUIREMENTS.md); the load-bearing rules are in [CLAUDE.md](CLAUDE.md).

## Progress

| Phase | State | Notes |
| --- | --- | --- |
| P0 Scrap and scaffold | **Done** except Android toolchain | `flutter doctor` not clean: needs a JDK 17–24 and cmdline-tools |
| P0b spike 1 — Apps Script 302 | **Done** | Outcome differed from the prediction; see Finding 1 |
| P0b spike 2 — `drive.file` silent re-auth | **Blocked** | Needs a device + a Google Cloud OAuth client |
| P1 Drift schema and value types | **In progress** | Host-test risk retired; see Finding 3 |
| P2 Repositories and outbox | Not started | |
| P3 Design system | Not started | |
| P4 Gateway v1 | Not started | |
| P5 Wire layer | Not started | |
| P6 Auth, RBAC, navigation | Not started | |
| P7 Sync engine | Not started | |
| P8 Expenses | Not started | |
| P9 Receipts | Not started | Shape depends on spike 2 |
| P10 Sync visibility | Not started | |
| P11 Livestock counts | Not started | |
| P12 Stock | Not started | |
| P13 Medicine | Not started | |
| P14 Soft delete and restore | Not started | |
| P15 Audit and activity feed | Not started | |
| P16 Reports and export | Not started | |
| P17 Settings and polish | Not started | |
| P18 Background sync | Not started | May legitimately exit as "delete it" |
| P19 Receipt storage rotation | Not started | Deferred by decision |
| P20 Multi-device hardening | Not started | |

## Context

`Android App – Technical Requirements v3.0` specifies a local-first livestock management app for a ~10-person
organisation: expenses, receipts, stock, livestock counts, medicine, users/roles/permissions, reports, audit,
sync, and admin-configurable receipt storage. §6/§7/§119/§120 mandate **Flutter + Dart + Material 3 +
SQLite/Drift**, Google Drive for receipt files, UUID identity, a persistent sync queue, idempotent sync, and
₹0 infrastructure.

The repo currently holds an Android Kotlin/Compose client, an Apps Script gateway, design docs and shelved
Supabase migrations — covering two of the eleven domains. **The decision is to scrap all of it and build
fresh from the requirements doc.** This plan is that build.

**Decisions taken before planning:**

| Question | Decision |
| --- | --- |
| Client | Flutter, at the repo root |
| Old Kotlin app, gateway, docs, supabase migrations | **All deleted** in P0 |
| Backend for business data | **Apps Script Web App in front of Google Sheets**, written fresh |
| Receipt files | **App uploads direct to Google Drive** with the `drive.file` scope |
| Roles / permissions | Permission = Dart enum (full §90 catalog); Role = table, seeded Worker + Admin; effective = `role ∪ userGrants − userRevokes` |
| Reads | All-or-nothing; `*_VIEW` gates UI only, **not** a confidentiality boundary. Writes enforced server-side |
| Livestock counts | Signed delta events with a reason, debounced; on-hand = `SUM(delta)` |
| Receipt Drive rotation (§47–§55) | Deferred to P19; `storageConfigId` carried from P9 regardless |

---

## Constraints carried forward

The prior design documents are being deleted. Six findings in them were expensive to discover and are
**not** derivable from the requirements doc, so they are restated here as first-class design rules and go
into `CLAUDE.md` in P0. Four of them are corrections *to* the requirements doc.

1. **The Google Sheets `spreadsheets` OAuth scope is classified *sensitive*.** An app holding it pins the
   OAuth consent screen in Testing status, where Google expires refresh tokens every **7 days** — so
   "stays signed in" breaks a week after install, and no amount of client code fixes it. **Therefore the
   app must never call the Sheets API.** The Apps Script Web App exists precisely so the client needs only
   `openid email profile`. Do not add a Google Sheets client library. This is *why* the gateway exists; it
   is not an implementation preference.
   `drive.file` (per-file access to files the app itself created) is **not** sensitive, which is what makes
   direct Drive upload viable — see P0b.
2. **A `syncStatus` column alone is not a sync queue** (contra the shape implied by §16 + §27). Dedupe must
   key on the *operation*, not the record, or a retried write becomes a second row. Hence a separate
   `sync_operations` outbox with a UUID `opId`, per §17/§18 — and `opId` reuse across retries is the
   *entire* duplicate-prevention mechanism (§19).
3. **Client clocks are not conflict resolution — §87 as written must be rejected.** Device clocks are
   user-settable; a phone with a wrong clock wins permanently and unrecoverably. Use optimistic
   concurrency: the client sends `baseVersion`, the server compares and rejects. Last-write-wins is then
   implemented as a *client-side resolution policy over server version ordering*, which is §87's intent
   without the clock dependency.
4. **Soft deletes need a purge policy** (§36/§103 give none). Tombstones accumulate forever and eventually
   dominate the dataset. Purge only rows that are `deleted AND synced AND serverSeq <= min(lastSeq across
   all devices)` — which means the server must track per-device cursors. A purged tombstone can never be
   restored, so §36's Deleted Records view must show the retention window.
5. **Bound receipt sizes before upload** (§105 says "compress", without limits). ~1024 px longest edge,
   JPEG q80, hard reject above a stated ceiling. An unbounded 12 MP photo is a failed upload on a rural
   connection and a filled Drive.
6. **Ship a signed release build, not a debug APK.** `flutter build apk --release` with a committed
   keystore config; a debug APK is unshippable and its signing key is not yours.

Two further corrections to the doc, derived here:

7. **§98's separate `metadataSyncStatus` column is collapsed** into the shared `syncStatus`, which already
   means "does this row match the server". Two columns with one meaning can disagree and there is no rule
   for which is right. The doc's vocabulary survives as a Dart getter.
8. **§35's client-written audit log is moved server-side.** An offline device's audit row is exactly as
   trustworthy as its clock (see #3). The gateway appends audit rows inside its own lock, using server
   time. Client-side rows only for local-only events (sign-in, app lock, sync failure) that never reach
   the server.

---

## Stack

| Concern | Choice | Reasoning |
| --- | --- | --- |
| Local DB | **drift** + `sqlite3_flutter_libs` + `drift_flutter` | §7. Bundled SQLite — OEM-shipped versions vary wildly and you don't want partial-index support to be device-dependent |
| State + DI | **Riverpod** | `StreamProvider` over drift `.watch()` *is* §94/§95's "UI reads the local DB" rule in one line, with automatic disposal. `ProviderScope` overrides replace get_it/injectable — one DI mechanism, zero extra codegen |
| Routing | **go_router** | §57's gate becomes one declarative `redirect`, so no deep link can reach a data route while unapproved |
| HTTP | **dio** | Needs `followRedirects: false` + manual POST re-issue — see P0b |
| Codegen | **drift only** | No freezed, no json_serializable, no riverpod_generator. ~6 hand-written envelope classes + a `RowCodec<T>` registry. One code generator on a toolchain is enough |
| Enums in DB | `WireEnumConverter<T>` over each enum's explicit `wire` string. **Never `textEnum`** | `textEnum` persists the Dart identifier (`shedRepair`) while the sheet expects `shed_repair` — two representations, silent mismatch, rows that never match a query |
| Money | `int` **paise** | §27. Never `double`. `parseToMinor` hand-rolled (~15 lines, no `decimal` dep), refusing negatives and sub-paise rather than rounding silently |
| Quantity | `int` **quantityMilli** (thousandths) | §32/§43. Stock balance is *derived on every read*, so float error compounds silently and two devices disagree on a balance |
| Dates | ISO `yyyy-MM-dd` strings end to end | Matches the sheet cell format; no timezone ambiguity |
| Sign-in | `google_sign_in` ^7.x, `serverClientId` = the **web** client id | §57. Must match the gateway's expected `aud` |
| Receipts | `image_picker` + `flutter_image_compress` | System camera app, not `camera` — a bespoke viewfinder is scope you don't need. `flutter_image_compress` is native libjpeg-turbo and handles EXIF orientation, which is what §105 actually requires |
| Secure storage | `flutter_secure_storage`, one JSON blob | §60 |
| Connectivity | `connectivity_plus` | §72. Reports *interface* state, not reachability — a **trigger, never a precondition** |
| Biometrics | `local_auth`, `biometricOnly: false` | §59. Device PIN as fallback; needs `FlutterFragmentActivity` (fails at runtime only) |
| Background | `workmanager`, opt-in, P18 | §73 — see the honesty note there |
| Config | `--dart-define-from-file=config/dev.json` (gitignored) | §60 — no secrets in the repo |
| Rejected | get_it/injectable, freezed, json_serializable, bloc, provider, retrofit, `package:decimal`, any Google Sheets client library | Each duplicates something above, or violates constraint #1 |

Commit `pubspec.lock` — this is an app, not a library. Pin versions at implementation time with
`flutter pub add`; do not copy version numbers from a plan.

---

## Phases

### Block A — Clean slate and foundation

**P0 · Scrap and scaffold.**
Delete `app/`, `apps-script/`, `docs/`, `supabase/`, `gradle/`, `gradlew`, `gradlew.bat`,
`build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `.kotlin/`, `.run/` in **one commit** —
git history is the archive; do not create a `legacy/` directory that nobody dares delete later. Then
`flutter create .` at the repo root, `flutter_lints` + `analysis_options.yaml` with `strict-casts`,
`strict-raw-types`, `strict-inference`. Commit the requirements doc as `REQUIREMENTS.md` at root — it is
now the only specification, so it must be in the repo. Rewrite `CLAUDE.md` for the Flutter build,
carrying the eight constraints above verbatim. `config/dev.json` gitignored with `GATEWAY_URL` and
`GOOGLE_WEB_CLIENT_ID`, read through an `AppConfig` const class (never `String.fromEnvironment` at call
sites).
*Exit:* `flutter doctor -v` clean for Android; `flutter run` shows a screen on a real device;
`flutter test` and `flutter analyze` both green on the scaffold. **Flutter is not currently installed on
this machine — this is a real gate.**

**P0b · Two spikes that can invalidate the design.** Neither is optional and both are cheap.

- **The Apps Script 302 trap.** Deploy a hello-world Web App and POST to it from Dart. Apps Script answers
  POST with a **302** to `script.googleusercontent.com`, and `package:http` — correctly, per HTTP spec —
  converts POST→GET on 302/303. The body vanishes, you get the `doGet` response, and **it looks like
  success.** This is the single most likely "server tests green, device silently broken" bug in the
  project. *Exit:* a POST body demonstrably reaches `doPost`, via dio with `followRedirects: false` and a
  bounded manual re-POST on 301/302/303/307.
- **Drive `drive.file` authorization, including silent renewal.** `google_sign_in` 7.x separates
  authentication (ID token) from authorization (scoped access token). Verify: (a) `drive.file` consent can
  be obtained; (b) an access token can be **silently** re-obtained after expiry via
  `authorizationForScopes` — on Android the grant lives in Play services, not in a refresh token the app
  stores, which is what makes this work; (c) whether that silent path is reachable **from a background
  isolate**. *Exit:* a JPEG lands in Drive from the app, and the background-isolate answer is written down.
  **If (c) fails, receipt uploads become foreground-only** — acceptable, since they are the Wi-Fi-only
  user-visible path anyway — and that constraint propagates to P9 and P18.

**P1 · Drift schema v1 and value types.** Every table for every domain in one migration.

A `SyncMetaColumns` mixin on every synced table: `version`, `serverSeq`, `serverUpdatedAt`, `syncStatus`,
`deleted`, `deletedAt`, `deletedBy`, `createdAt`, `updatedAt`, `createdBy`, `updatedBy`, `createdByName`.
Two rules travel with it: **there is no per-row `dirty` flag** (dirtiness *is* `syncStatus != synced`), and
`version`/`serverSeq`/`serverUpdatedAt` are **server-owned** — a local edit carries them forward untouched
or the upload conflicts with itself.

| Group | Tables |
| --- | --- |
| Ledger | `expenses` (§27), `livestock_trades`, `receipts` (§28) |
| Stock | `stock_items`, `stock_transactions` (§32) |
| Livestock | `livestock_categories`, `livestock_count_events` (§33) |
| Medicine | `medicine_records` (§34) |
| Access | `users` (§29), `roles` (§30/§31) |
| Audit | `audit_logs` (§35) — download-only mirror |
| Config | `receipt_storage_configurations` (§49) — download-only, seeded with one row |
| Sync | `sync_operations` (§18), `sync_state`, `device_identity` (§112), `pending_downloads`, `conflict_resolutions` |

Four schema decisions worth defending:

- **Stock balance and herd on-hand are Drift `View`s over `SUM`, never stored columns.** A stored balance is
  a second source of truth that sync can desynchronise, leaving two numbers and no way to know which is
  right. §43's "prefer transaction history rather than blindly overwriting" says the same thing.
- **`livestock_count_events` stores a signed `delta` + `reason`, not §33's `{previousCount, newCount}`.** Two
  devices offline both incrementing must *sum*; absolute snapshots overwrite, and one device's count is
  silently lost. `previousCount`/`newCount` become derived display values.
- **No UNIQUE constraint on `(categoryId, countedOn)`** even though one count per category per day looks
  obviously right. Two devices offline both record today's count; the constraint then makes a legitimate
  *download* unapplicable — converting a benign duplicate into data you cannot receive. Non-unique index;
  resolve in the report layer.
- **`pending_downloads` and `conflict_resolutions` are not in the doc** and are load-bearing.
  `pending_downloads` is where an incoming server row waits when the local row has an unsynced edit — the
  doc never says what happens in that collision, and the answer must be "the local edit is never silently
  overwritten". `conflict_resolutions` holds *both* payloads, which is how §87's "preserving audit history"
  is actually achieved.

Indexes per §83, written as **partial** indexes (`... WHERE deleted = 0`) since every list query filters
tombstones. `stock_transactions(stockItemId, occurredOn)` is the most important index in the schema because
balance is derived on every read. `medicine_records(nextDueOn)` for §45's due-soon. No redundant PK indexes,
no standalone index on a low-cardinality boolean.

Migrations per §84: `schemaVersion = 1`, **no destructive fallback ever** — "never require users to
uninstall to update the schema" also means never quietly dropping their unsynced records. `drift_dev schema
steps` snapshots committed under `drift_schemas/` as migration-test inputs. Additive-only in v1: a type
change is three releases (add-new → backfill + dual-write → stop-reading-old), never one, because a
rollback after a destructive migration has nowhere to go. `PRAGMA foreign_keys = ON` in `beforeOpen` (drift
does not do this by default), with `defer_foreign_keys` inside each download batch — the server orders
changes by `serverSeq`, so within one page a child row can precede its parent, and a parent can never have
a *higher* seq than its child, so deferring is safe rather than papering over.

Port `Money`, `Quantity`, `Dates` with their own test suites.
*Exit:* money parsing refuses negatives and sub-paise (asserted, not rounded); `Quantity` round-trips
exactly; `validateDatabaseSchema`; a v1→v1 migration harness; a test proving deferred FKs accept
child-before-parent and still reject a genuinely dangling reference.

**P2 · Repositories and the outbox.** Per domain, the §95 write path as **one Drift transaction**:
`upsert(row)` + `markDirty(...)`. §26 is explicit that this must be atomic, and the reason is worth stating
in code: a row without its operation never reaches the other partners; an operation without its row is an
upload of something the app cannot show. Both lose data.

- **Operations are coalesced per entity.** If an UPSERT op already exists for `(entityType, entityId)`,
  don't enqueue another. `payload` stays empty and the uploader reads current row state at send time —
  enqueuing one op per edit would give them all the same stale `baseVersion`.
- **Deletes are soft and routed through the same dirty-marking path**, so there is deliberately no separate
  DELETE operation to order against an UPDATE.
- FIFO by `createdAt` **tie-broken by `rowid`** — epoch-ms collides on a fast device.
- `sync_operations` carries `nextAttemptAt` (§68), `dependsOnOpId` (§67) and `status`.
- `advanceSeq` has a monotonic guard: the cursor never rewinds.

*Exit:* per domain — save is readable immediately; a fresh row is PENDING and queued; three edits coalesce
to **one** operation; a local edit does not reset the server-owned `version`; delete leaves a queued
tombstone; deleting an unknown id is a no-op; domain models report `pendingSync`; tombstones purge only
once synced and only up to the cursor. Plus: **an offline record and its queued operation both survive
process restart**, tested against an **on-disk** close-and-reopen — an in-memory database passes this while
proving nothing.

**P3 · Design system and component gallery.** §79/§80's Material 3 requirements, built once so no screen
invents its own.

Seed **deep green `#1B5E20`** (agriculture; reads as positive/credit), secondary turmeric amber, muted
terracotta tertiary. Generate light and dark from `ColorScheme.fromSeed` **once** and **commit the schemes
as Dart** — reviewable, stable, no runtime regeneration. **Dynamic colour off by default**, offered as a
Settings opt-in: with it on, the contrast of every money figure depends on the user's wallpaper.

A `SankranthiColors` `ThemeExtension` supplying `credit`, `debit`, `pending`, `synced`, `failed`, `offline`
and their `on*` pairs — because M3's semantic roles have no vocabulary for money, and reaching for
`colorScheme.error` to render an expense says "this is a bug", not "this is money going out".

One font family with a **Noto Sans Telugu fallback** — the fallback must exist before the first Telugu
string does — and **tabular figures** on all money and counts, or columns of rupees jitter as they update.
`MoneyText` / `QuantityText` widgets so sign colouring, tabular figures and screen-reader semantics live in
one place rather than at every call site.

**UI state holds a sealed `Failure?`, never a `String?`.** §77 forbids showing `SocketException` or
`HTTP 500`; making the failure a type rather than a string means the type system makes a raw exception
*unrepresentable* in the UI layer, instead of relying on everyone remembering. A `FailureMapper` produces
`{title, body, action}`, table-tested for exhaustiveness, plus a CI grep forbidding `toString()` on error
objects under `lib/features/`.

Components: `SummaryTile`, `SectionHeader`, `EmptyState` (§76 — with an action slot, since "start recording
your first expense" needs a button), `ErrorBanner`, `OfflineBanner` (§78), `AppSnackbar`, `ConfirmSheet`,
`DateField`, `PickerField`, sync chip (§16 — `Synced | Syncing | Pending(n) | Failed(n) | Conflicted(n) |
Offline`), dual-status row (§40), skeletons.
*Exit:* a gallery route renders every component in light and dark; component goldens green; a11y contrast
verified in both schemes.

### Block B — Backend and the core loop

**P4 · Gateway v1, written fresh.** A single Apps Script Web App in front of one spreadsheet. Deployed
"Execute as: me / Anyone", so the `/exec` URL is an endpoint, not a secret — every request is authorised
from its ID token, never from URL possession.

Sheets: `Users`, `Roles`, `Expenses`, `LivestockTrades`, `Receipts`, `StockItems`, `StockTransactions`,
`LivestockCategories`, `LivestockCountEvents`, `MedicineRecords`, `Audit`, `StorageConfigs`, `Devices`,
`Sessions`, `AppliedOps`, `_Meta`. Every entity sheet ends in `version, serverSeq, serverUpdatedAt`.

Actions: `ping`, `session`, `signOut`, `sync`, `adminUpdateUser`.

Design rules, each of which is a bug avoided:

- **One declarative `SCHEMA` + `COLUMN_TYPES` table drives everything** — column order, text-vs-number
  coercion, and which sheets are client-writable. Hardcoding a numeric-field-name list somewhere in the
  serialiser means every new domain silently returns `quantityMilli` as a string until someone notices.
  Column order **is** the wire contract; say so in a comment and test it (P5).
- **Permission lookup is a table with an explicit `deny` default.** A `requiredPermission()` if-chain that
  returns "no permission needed" for an unrecognised entity type is **fail-open**: add a sheet, forget the
  mapping, and any approved member can write it. Eleven domains make that inevitable. Deny by default.
- **Idempotency (§19/§25):** `AppliedOps` stores the *result* keyed by `opId`, and `serverSeq` is allocated
  **inside** the `LockService` lock. Replaying an `opId` returns the stored original result — which is what
  lets the client treat DUPLICATE exactly like APPLIED. Read `AppliedOps` **once per request**, not once per
  operation; a full-sheet read per op inside the lock is the first performance cliff you will hit.
- **Optimistic concurrency (§87):** compare the client's `baseVersion` to the stored `version`; on mismatch
  return `CONFLICT` **with the server row attached** so the client can resolve without a second round trip.
- **Audit is appended server-side**, inside the same lock, using server time (constraint #8).
- **`Devices` tracks `deviceId → lastSeq`**, and `_Meta.purgeHighWaterMark = min(lastSeq)` is returned on
  every sync as `purgeBelowSeq`. Without this, constraint #4's tombstone purge is unimplementable — the
  client cannot know what every *other* device has seen. `AppliedOps` purges on the same high-water mark.
- **`adminUpdateUser`** (§88) enforces that **an admin cannot change their own role or status**, so the
  partnership cannot lock itself out of its own books. §61's point exactly: hiding the button is not the
  boundary.
- **Sessions (§57):** a background sync has no Activity and therefore cannot mint a Google ID token. So
  `session` exchanges an ID token for an opaque, sliding **90-day** token; only its SHA-256 is stored;
  `SESSION_EXPIRED` is a **distinct** code from `UNAUTHORISED` so the client can silently re-mint in one
  case and sign out in the other. Without this the app signs the user out every hour.
- **Unknown failures are retryable.** A transient Sheets error must never cause the client to discard a
  pending record (§103).
- Known scaling wall, documented and deferred: reading every row of every sheet per sync. Fine at ten users;
  it is the ceiling, and the doc's 10-year retention (§2) eventually meets it.

Tested with Node's built-in test runner against a fake `SpreadsheetApp`/`LockService`/`UrlFetchApp` harness.
*Exit:* same `opId` twice → one row + DUPLICATE with the original result; two devices one row → one APPLIED
one CONFLICT; lock timeout → retryable, nothing written; unknown entity type → **denied**; every write op
rejected without its permission, for every entity; admin self-demotion refused; `purgeBelowSeq` is the
device minimum.

**P5 · Wire layer and fake gateway.** `SyncRemote` as the seam — **nothing in `lib/features/` or
`lib/domain/` may import an implementation** (§62/§94). DTOs only; the seam must not leak Drift row types
outward.

`OutboundOperation` carries a generic `Map<String, Object?> row` produced by a **`RowCodec<T>` registry**
keyed by `entityType`, not a nullable field per domain — with eleven domains, the per-domain-field shape
means editing the envelope class and three branches every time. With the registry, adding a domain is one
Drift table + one `RowCodec` + one `SCHEMA` entry, and the gateway needs no change at all because it treats
`row` as an opaque map filtered through the sheet's client-writable columns.

`sessionToken` is the credential on `sync`/`signOut`; `idToken` appears **only** on `session`. Error
taxonomy: `Transient` / `Permanent` / `SessionExpired`. The envelope is ~6 classes, so hand-write the JSON
and skip `json_serializable` entirely. Build an in-Dart fake gateway so the engine is testable without a
network.
*Exit:* round-trip encode/decode per domain; the P0b 302 re-POST behaviour asserted in a test; **a test
that parses the gateway's `SCHEMA` and asserts the Dart codecs match it in name and order** — order is the
contract, so make it a test rather than a comment.

**P6 · Auth, session, RBAC, navigation.** §57's flow: Google Sign-In → gateway `session` → 90-day token as
**one JSON blob** in secure storage (one blob, not field-by-field: "signed in as X until Y" is worth
something to an attacker even without the token).

- `SESSION_EXPIRED` **foreground:** try lightweight/silent authentication, re-mint, retry the original
  request — the user sees nothing.
- `SESSION_EXPIRED` **background:** the isolate cannot mint an ID token at all. Clear the session, set
  `needsInteractiveReauth`, post a local notification, and **stop**. Never loop: every attempt is a wasted
  request against a session that cannot come back on that path.
- `UNAUTHORISED` (not on the allowlist / rejected) → sign out and route to the right shell.
- **The secure-storage key must NOT require user presence.** A biometric-gated key means a background sync
  on a locked phone cannot read the token, so a record created offline sits unsynced — precisely the data
  loss §86 warns about. §59's biometric lock is a **UI gate only**; this needs a comment in the code saying
  so, because "make it more secure" is the obvious wrong instinct here. Every read failure resolves to "no
  credential", never a crash (a device restore leaves ciphertext whose key is gone).
- **Sign-out policy — a data-loss hole the doc doesn't cover:** sign-out does **not** wipe the local
  database unless `sync_operations` is empty. With pending ops, refuse with a clear message or require an
  explicit typed "discard N unsent records" confirmation.
- `deviceId` (§112) = v4 UUID in `device_identity`, minted on first open. **Never a hardware identifier** —
  privacy, and it changes on factory reset anyway.

RBAC: the full §90 `Permission` enum (permissions are code — a permission the client doesn't know cannot
gate anything, so a data-driven catalog buys nothing and costs type safety); `roles` as a table seeded with
exactly Worker and Admin, **no role designer in v1**; effective permissions =
`role.permissions ∪ userGrants − userRevokes`. The override layer is what makes §88's "Change permissions"
meaningful per-person rather than per-role. **"Admins implicitly hold everything" is not implemented** — the
admin role stores the full catalog explicitly as data, so a newly added permission is a deliberate grant
rather than a silent one, and the audit log records a fact rather than an inference. `isSystemAdmin` survives
only for lockout guards, refusing deletion of that role, and the first-account bootstrap. Role editing sends
a **delta** (`grant`/`revoke`), never a whole-set replacement, so an admin on an older build cannot silently
strip permissions its build doesn't know about.

Navigation: four shells via one `go_router` `redirect` — splash / sign-in / pending-or-rejected / app shell.
**Worker bottom nav is literally §39's four tabs** (Expenses · Stock · Live Count · Medicine), filtered by
`*_VIEW`. **Admin bottom nav:** Dashboard · Expenses · Records (a hub with Stock/Live Count/Medicine as
segmented tabs) · Admin — so *management* lives under Admin per §39's "admin functions should not clutter
the main worker navigation", while the bar stays at four. Mode is **derived** from holding ≥1 admin-surface
permission, not from a role check; plus a **"View as worker"** Settings toggle with a persistent banner,
documented as a client-side preview and not a privilege drop. §37's Admin Dashboard renders its management
list with each row gated on its own permission, rows stubbed. Users and Roles screens ship here with the
self-lockout and last-admin guards. A permission-denied deep link redirects home with a snackbar, never a
blank screen.
*Exit:* a permission-matrix widget test over ~8 permission sets × each screen, asserting every write
affordance appears and disappears; gate tests for every session state × mode; deep link to `/admin/users`
while pending → redirected. **Assert on stable `ValueKey`s, never on text, and put every negative assertion
in the same test as a positive control** — a `findsNothing` that passes because the finder was wrong looks
exactly like a working permission gate.

**P7 · Sync engine, foreground.** §65's responsibilities, §97's interface.

Cursor and rows advance **in the same transaction** — that is the whole reason the cursor lives in the
database rather than in preferences. `newSeq` is the highest seq actually returned, never guessed:
advancing past rows you weren't sent skips them forever.

**Download onto a locally-pending row.** The doc doesn't address this collision and it is the one that
loses work. Rule: if the target row is not synced, the incoming payload goes to `pending_downloads`
(highest-seq-wins), the cursor **still advances**, and it reconciles after the local operation uploads.

Result handling: APPLIED/DUPLICATE adopt `version` + `seq` and remove the op; CONFLICT keeps the op, writes
**both** payloads to `conflict_resolutions` — never discarding the local one — then re-applies the local
edit atop the server row with a fresh `baseVersion`; REJECTED is permanent, so remove and surface rather
than loop. **Delete-vs-edit conflicts are never auto-resolved** — either choice destroys a record or a
deliberate removal, and there is no defensible default.

**The mid-flight edit rule:** capture the row's `updatedAt` at send time and mark it synced **only if it
still matches**. Clearing unconditionally silently drops an edit the user made while the request was in
flight.

Backoff (§68) at two independent levels: per-operation `min(15s · 2^n, 6h)` with ±20 % jitter, and a
separate whole-sync `nextAttemptAt` so forty queued ops don't each burn a request against a dead endpoint.
Jitter matters even at three devices — three phones reconnecting after a power cut otherwise hammer the same
window and all hit the lock timeout. Poison-op guard at 10 attempts → `failedPermanent`, skipped in the
drain so it never blocks the queue, and **never deleted** (§103: deleting a sync operation after a failure
is data loss).

Connectivity is a **trigger, never a gate** (§72 says this explicitly): `connectivity_plus` reports
interface state, and captive-portal Wi-Fi reports connected. Debounce 2 s. If the user pressed "Sync now",
attempt regardless.

Initial device sync (§108) pages until exhausted and gates the dashboard on `initialSyncComplete` — a
half-downloaded ledger showing a wrong net position is worse than a spinner. Seed default categories, stock
units and one storage config at migration 1 so a fresh install is usable offline immediately.
*Exit:* the same `opId` twice → one row; response lost mid-flight then retried → original result, no second
row; download onto a locally-pending row → **the local edit survives**; mid-flight edit → row stays dirty
and keeps its op; cursor never rewinds; a poison op doesn't block the queue; whole-sync backoff prevents 40
requests against a dead endpoint.

**P8 · Expenses (§40–§42).** List grouped by day with filters and search; **dual Data ✓ / Receipt ⏳ status
per row**; full-screen add/edit route; local-first save; delete with a 5 s undo snackbar (undo cancels the
enqueued op locally, no round trip).

**Editors are bottom sheets or full-screen routes, never a scrolling dialog.** Rule: modal sheet for ≤3
fields and quick picks; full-screen route for keyboard-heavy forms and anything with a camera step. §41's
six fields plus a camera do not fit a dialog once the keyboard is up.

**§104, stated as a rule:** the write is one Drift transaction, so the route pops **immediately** with a
snackbar. No spinner, no `loading: true` around a local write — there is nothing to wait for.
*Exit:* offline create appears instantly as pending; save→pop under 100 ms (tested); goldens for all six
status combinations; delete-undo enqueues nothing.

**P9 · Receipts (§10, §11, §21–§25, §28, §105, §109, §110).** `image_picker` → `flutter_image_compress`
(≤1024 px, q80, per constraint #5) → app-private store → direct Drive upload with `drive.file`.

**The identity rule (§11, §122), which everything else depends on:** `receiptId` is a client-minted UUID and
is the permanent identity. `driveFileId` is nullable and is merely a remote storage reference. A receipt
captured offline has `driveFileId = NULL` and that is completely valid. `receiptId` never changes.

`storageConfigId` is populated from day one even though the rotation UI is P19 — §53/§101 make it
effectively impossible to retrofit, since the app must know which Drive each historical receipt lives in.

**§23 is enforced structurally, not by discipline:** receipt upload and ledger sync are **two separate drain
loops in two separate methods sharing nothing but the database**, and neither may write state that gates the
other. An expense whose photo failed is not a failed expense. Test: make every upload throw and assert
ledger operations still reach the server.

**§25 is enforced in three independent places**, because one is not enough for a rule this consequential:
the query is `WHERE driveFileId IS NULL AND uploadStatus != 'uploaded'`; the upload result is persisted
*before* the metadata op is enqueued, in one transaction; and the uploader itself asserts it. Collapsing
`metadataSyncStatus` (constraint #7) makes §25 a queryable invariant: `driveFileId != null && syncStatus !=
synced` means the binary is safe in Drive and only metadata needs retrying.

`localPath` is **retained after upload** (§56) so the image displays with no network round trip and a failed
upload never loses the only copy. Cleanup is a later policy, gated on all four of §56's conditions.

UI: thumbnail from cache only, viewer with cached-or-download-on-demand (§109), download status and retry
(§110), LRU cache cap, and **Wi-Fi-only auto-download by default** — these are the only large payloads in
the app.
*Exit:* upload failing forever does not stop ledger sync; `driveFileId != null` never re-uploads, proven
from all three enforcement points; a 4000×3000 EXIF-rotated input compresses under 400 KB with correct
orientation; killed mid-upload → no duplicate Drive file, no lost local file; metered scroll downloads
nothing.

**P10 · Sync visibility (§16, §69–§71, §78).** The chip becomes tappable. Sync Health (§70): last successful
sync, cursor, pending by entity, failed ops, Sync now, Retry all. Sync Details (§71) per record: data status,
receipt status, error, retry count, last attempt, per-item Retry. Discard requires a hard confirm.

Offline banner (§78): slim, persistent, below the app bar, only when offline **and** there is pending work,
never modal, connectivity flaps debounced so it doesn't strobe in a low-signal shed. **Pull-to-refresh means
"sync now"**, not "reload from the database" — the database is already live via streams; offline pull returns
immediately with a snackbar. Skeletons only on cold load with no cached rows (§75) — a skeleton over data you
already have is a regression; refreshing shows a thin top line over real content.
*Exit:* every sync state reachable and demoable; a forced failure is diagnosable and retryable from both the
record and Sync Health.

### Block C — Remaining domains

**P11 · Livestock counts (§44, §33).** `livestock_categories` + `livestock_count_events(categoryId, delta,
reason ∈ {BIRTH, DEATH, PURCHASE, SALE, CORRECTION, TRANSFER_IN, TRANSFER_OUT}, occurredOn, note?,
linkedTradeId?)`.

Big +/− counter with **64 dp targets**. §44's "every change should be recorded" taken literally means a
worker counting 40 goats writes 40 rows and 40 sync operations — a queue flood, not an audit trail. So taps
accumulate into **one event per category per ~3 s idle window**, with the pending delta ghosted next to the
count ("47 +7"); undo inside the window is local and enqueues nothing. Every *change* is still recorded with
actor and time; a counting session is one change. Plus "Set exact count", which writes a `CORRECTION` with
the computed delta and a **mandatory** note.

Trades and counts stay **separate but linked**: a BUY/SELL optionally auto-creates a matching event; the
trade owns the money, the event owns the herd. Do not derive the herd from trades — births and deaths are
not trades, and a herd count derived from trades is wrong the day a goat dies.
*Exit:* 40 taps → 1 event, 1 op; count survives app kill; on-hand includes births and deaths.

**P12 · Stock (§32, §43).** Items list with on-hand and low-stock warning; item detail = transaction history
with a running balance header; add-transaction sheet for PURCHASE/CONSUMPTION/ADJUSTMENT/TRANSFER; optional
expense linkage.
*Exit:* balance is derived, never stored; two devices appending converge; scaled-integer quantities
round-trip exactly.

**P13 · Medicine (§34, §45).** Treatments timeline with **Due & overdue at the top**; add treatment (medicine,
dosage, route, date, next-due); medicine catalog; per-subject history; optional local notification for due
treatments.
*Exit:* due/overdue computed offline and correct across a date change; "Due today" drills into the filtered
list.

**P14 · Soft delete, Deleted Records, Restore (§36, §114, §115).** Cross-domain delete with optional reason;
Admin → Deleted Records with filters by entity type, date and deleter; **Restore enqueues an explicit
`RESTORE` operation**, never a local flag flip — which means adding `RESTORE` to the gateway's op types and a
`record.restore` permission (a restore must not silently inherit the *edit* permission). Per constraint #4, a
purged tombstone can never be restored, so the view states the retention window and the purge high-water mark
respects it.
*Exit:* restore round-trips and reappears on a second device; restore without permission is rejected
server-side; retention window shown.

### Block D — Admin, reporting, polish

**P15 · Audit and activity feed (§35, §81, §113, §114).** A read-only downloaded audit mirror; Audit Logs
list with filters; entry detail with a field-level diff.

**§81's activity feed is not §35's audit log**, and they will look alike enough on screen to tempt a merge.
The feed is user-facing and **derived locally** by unioning entity tables — no new synced table, always
consistent with what this device has, works offline, costs nothing. The audit log is server-authored and
immutable. Keep them separate.
*Exit:* a test asserts the client never writes an audit row; both usable offline.

**P16 · Reports and export (§46, §91, §92).** Drift aggregate queries against the local database, so reports
work offline with no server endpoint. Five types: expense summary, stock movement, livestock count history
and trend, medicine/treatment, monthly consolidated. `REPORT_VIEW` sees; `REPORT_GENERATE` exports.
**CSV → Excel first; PDF deferred to P17** — PDF is the worst value-per-effort item in the doc, and
on-device generation of a multi-thousand-row document will jank, so cap rows and render in an isolate.
*Exit:* **exported CSV totals equal the on-screen totals** — that single assertion catches an entire class of
aggregation bug; all five run offline; export hidden without GENERATE.

**P17 · Settings, devices, dashboard, polish (§37, §58, §59, §79, §80, §111).** Settings: profile with the
effective permission list, appearance including the dynamic-colour opt-in, sync preferences, storage and
cache, biometric app lock, about. Devices list (§111) with per-device sign-out, backed by a gateway session
listing. Biometric lock on resume after a configurable idle timeout (default 5 min), **not every resume** —
a lock on every resume trains users to disable it.

**Permissions are requested contextually (§58)**, never at onboarding: camera on first receipt capture,
notifications on first opt-in. A permission wall before the app has demonstrated value is the fastest way to
a denial you cannot re-ask for.

Dashboard per §80: responsive 1/2/3-column grid; net position hero → month spend + herd on hand → low stock
+ treatments due → sync status (only when non-zero) → activity feed. **Every card is tappable and lands on
the filtered screen behind its number** — a figure you cannot drill into is decoration. Shared-axis page
transitions, container transform list→detail, hero on receipt thumbnail→viewer, honouring reduce-motion.
PDF export lands here.
*Exit:* full golden suite green in light and dark; a11y clean at 2.0 text scale with 48 dp minimum targets;
all integration scenarios green.

**P18 · Background sync, opt-in (§73).** `workmanager` plus a Drift isolate so the worker and UI share one
database server rather than two isolates fighting over the file.

**Stated honestly:** background sync **cannot** be a guarantee on Android. WorkManager's minimum periodic
interval is 15 minutes and that is a best-effort window; Doze and standby buckets stretch it arbitrarily; and
OEM battery managers on MIUI, ColorOS, FuntouchOS and Samsung kill background work outright unless the user
manually exempts the app — which are the likely handsets here. So the **guarantees** are the four foreground
paths built in P7: on app foreground, on connectivity regained while foreground, immediately after a local
write, and on manual "Sync now"/retry. `workmanager` is a fifth, opportunistic path. If P0b found that
`drive.file` cannot be silently re-authorized from a background isolate, receipt upload is excluded here.
*Exit:* a record created offline, app force-stopped, phone reconnected, **no app launch** → the row reaches
the sheet within one worker window, on at least one **non-Pixel** device; and the whole feature disables
behind one flag with every stated guarantee intact.
**"Delete it" is a legitimate exit outcome, not a failure.** Shipping a feature that works on the developer's
Pixel and nowhere else is worse than not shipping it.

### Block E — Deferred

**P19 · Receipt storage rotation (§47–§55, §99–§102).** Storage config CRUD with versioning, test-connection,
approximate quota display, config version synced to other devices, Drive history never deleted, and a flow
that **routes new uploads only**. §52 is explicit: old receipts stay in their original Drive. §102: if the old
Drive is unavailable, say so and stop — never silently rewrite a receipt's Drive reference. §101: a receipt
keeps the config that was active when it was created, even if a newer config activates before it uploads.
§50: if validation of the new Drive fails, the old one stays active.

**A `drive.file` consequence to plan for:** the scope grants access only to files the app created, so
"choose an existing folder" requires the Google Picker (picking grants access to the picked item), and
"switch to a different Google account" requires a fresh OAuth grant for that account. Neither is hard; both
are invisible until you try.
*Exit:* new receipts land on the selected config; existing receipts untouched and still viewable;
`storageConfigId` was carried correctly from P9 onward.

**P20 · Multi-device and production hardening (§13, §116, §117, §124).** §117's eight failure scenarios and
§116's full matrix, on two real devices. §124's Definition of Done as a single scripted end-to-end run.
Migration rehearsal from a populated v1 database. A security pass over the gateway's permission table and the
`Users` allowlist. Signed release build per constraint #6.

---

## Verification

Per phase, the exit criteria above. Repo-wide, before calling anything done:

```bash
flutter analyze                       # strict-casts, strict-raw-types, strict-inference
flutter test                          # unit + widget + golden
flutter test integration_test/        # needs a device or emulator
flutter build apk --release           # signed, per constraint #6
node --test gateway/test/             # the Apps Script suite
```

**Compiling is not evidence.** The storage and sync layers' real failures appear only at runtime, so any
change under `lib/data/` needs `integration_test/` on a real device or emulator. Pin the font and device size
in golden tests or they flake across machines.

The integration scenarios that matter most:

1. **§124 end to end:** offline expense + photo → app closed → phone restarted → still offline → record,
   receipt and queue all still present → online → expense syncs, receipt uploads, `driveFileId` saved,
   status Synced → second device sees both.
2. Airplane-mode counter: 40 taps → **one** event, correct count, **one** queued op.
3. Conflict: a server change lands on a locally-edited row → CONFLICT → both versions preserved, nothing
   silently discarded.
4. Delete → undo inside the window (zero ops) **vs** delete → restore from Deleted Records (RESTORE op).
5. Permission revoked mid-session: the server rejects a write → a human message, no crash, affordance gone
   after profile refresh.
6. Receipt upload failing forever does not stop ledger sync (§23), and an existing `driveFileId` is never
   re-uploaded (§25).
7. Report CSV totals equal on-screen totals.
8. Metered connection: scrolling receipts downloads nothing; explicit tap downloads; failure retries.

---

## Risks

| Risk | Handling |
| --- | --- |
| Flutter not installed on this machine | P0 is a real gate, not a formality |
| Apps Script's 302 silently converting POST→GET | P0b, before anything is built on it |
| `drive.file` silent re-authorization unavailable in a background isolate | P0b answers it; fallback is foreground-only receipt upload, which is acceptable |
| Rebuilding the gateway re-earns correctness that was previously tested | P4 carries the specific rules (lock, AppliedOps-stores-the-result, seq-inside-lock, deny-by-default) and their tests |
| Sheets reads scale linearly per sync | Documented in P4 and deferred; §2's 10-year retention eventually meets it |
| OEM battery managers killing background work | The four foreground paths are the guarantee; P18 may exit as "delete it" |
| No working client until P8 | Accepted consequence of the clean slate; P3 + P6 are demoable earlier |
| Dart `int` becomes a JS double on web | No web target. Note it in `CLAUDE.md` before anyone adds `-d chrome` and silently breaks paise arithmetic |

---

## Findings that changed the plan

Logged as they are discovered, so the reasoning survives even when the conclusion reverses.

### Finding 1 — the Apps Script 302 fails loudly here, not silently (P0b, 2026-08-29)

**The plan predicted:** `package:http` downgrades POST→GET on 302, so the body vanishes and `doGet`'s reply
arrives as a cheerful HTTP 200 — a silent success, called "the single most likely *server tests green,
device silently broken* bug in the project".

**Measured:** `dart:io`'s `HttpClient` only auto-follows redirects for GET and HEAD. A POST 302 comes
straight back **even with `followRedirects = true` and `maxRedirects = 5`** — the fake gateway recorded
exactly one request. dio then throws `DioException` (its default `validateStatus` rejects 302);
`package:http` would return an empty body that dies at `jsonDecode`.

**So:** the mitigation is still mandatory — nothing reaches `doPost` without re-issuing the POST — but it
fails loudly rather than silently, which is the good case. The silent variant is real in browsers,
`curl -L`, Python `requests` and JS `fetch`, which matters twice: don't port a recipe from those tools and
assume it maps, and adding a Flutter web target would make it live via `BrowserClient`. Two tests pin the
platform behaviour so a Dart upgrade cannot reintroduce it quietly.

### Finding 2 — REQUIREMENTS §32 and §43 contradict each other (P0, 2026-08-29)

§32's `StockItem` carries a stored `currentQuantity`. §43 says "prefer transaction history rather than
blindly overwriting stock quantities." Both cannot hold. Took §43's side: balance is a derived drift `View`
over `SUM`, and the column is dropped. Recorded in CLAUDE.md so it is not "fixed" back later.

All 28 other section citations in CLAUDE.md were verified against REQUIREMENTS.md and resolve correctly.

### Finding 3 — drift host tests need no setup (P1 de-risk, 2026-08-29)

`sqlite3_flutter_libs` ships the native library to a device only, and historically host tests needed a
hand-placed `sqlite3.dll`. Had that held, every schema, migration and repository test would have required a
device — exactly the tests that need to be fast. Measured: `package:sqlite3` resolves SQLite 3.53.4 on the
host with no setup (native assets handle it) and `NativeDatabase.memory()` opens and answers a query. So
P1–P2 proceed entirely on the host, with no JVM and no device. A smoke test guards the assumption.

### Finding 4 — Gradle cannot be bumped to dodge the JDK problem (P0, 2026-08-29)

The only JDK on the machine is Android Studio's **Java 25**; Flutter's generated Gradle 8.14 requires
`17 ≤ java < 25` and suggests either a compatible JDK or a Gradle bump. The bump is a trap: Gradle 9.x
requires AGP 9.x, and AGP 9 **fails when the `kotlin-android` plugin is applied** — which the Flutter
scaffold does apply. Flutter 3.41's Android template is validated against Gradle 8.14 + AGP 8.11.1, so the
supported fix is installing a JDK 17–24, not fighting the toolchain.
