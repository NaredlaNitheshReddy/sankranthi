# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

**Sankranthi** — an Android app for keeping the books of a small livestock
partnership. Partners record livestock **purchases and sales**, and the
**maintenance expenses** of running the operation (feed, veterinary, labour,
transport, shed repairs, utilities). The overview screen nets it all out.

Access is deliberately narrow: you sign in with a Google account, which creates
an **access request**. An **admin** approves or rejects it and chooses which
edit rights that person gets. Nobody self-serves.

## Access model

Three independent axes on each `profiles` row:

| Axis | Values | Meaning |
| --- | --- | --- |
| `status` | `pending` / `approved` / `rejected` | May this account see the books at all? |
| `role` | `admin` / `member` | Admins reach the admin panel and implicitly hold every permission. |
| `permissions` | `edit_livestock`, `edit_expenses`, `delete_entries` | What a *member* may change. |

Rules that matter:

- **Reading is all-or-nothing.** Any approved account sees every entry. The
  permissions only gate writes.
- **Permissions are meaningless unless approved.** `Profile.can()` checks
  `isApproved` first, so a pending account with every permission set can still do
  nothing.
- **Admins get all permissions implicitly** — their `permissions` array is
  ignored (`Profile.grantedPermissions`).
- **The first account to ever sign in becomes an approved admin.** Otherwise
  there would be nobody able to approve anyone. See `handle_new_user` in the
  migration.
- **An admin cannot change their own role or status** — enforced by the
  `profiles_guard_update` trigger and mirrored in the UI, so the partnership
  can't be locked out of its own books.

**The database is the enforcement point, not the UI.** Row-level security
policies in [supabase/migrations/0001_init.sql](supabase/migrations/0001_init.sql)
decide every read and write. The Compose screens hide buttons the user cannot
use, but that is courtesy — never the security boundary. When you add a feature
that writes data, add the RLS policy too.

## Architecture: offline-first

**The UI reads Room and only Room.** Writes go to the local database and enqueue an
outbox operation in one transaction, then return — nothing waits for the network.
Reads are Room `Flow`s, so a write updates the screen because the *database*
changed, not because a callback fired. See
[docs/ARCHITECTURE_REVIEW.md](docs/ARCHITECTURE_REVIEW.md) for the full design and
the reasoning behind the backend choice.

Rules that are load-bearing, not stylistic:

- **Never read from the network in a ViewModel or composable.** The only path to a
  backend is `sync/` → `RemoteDataSource`.
- **Row + outbox operation commit together.** A row without its operation is a
  record that never reaches the other partners; an operation without its row is an
  upload of something the app cannot show. Both lose data.
- **`version`, `serverSeq` and `serverUpdatedAt` are server-owned.** A local edit
  must carry them forward untouched, or the upload will conflict with itself.
- **Deletes are soft** (`deleted = 1`) so removals propagate, and are routed
  through the same dirty-marking path as an edit — there is deliberately no
  separate delete operation to order against an update.
- **Outbox operations are coalesced per entity.** One operation means "this row is
  dirty"; the uploader reads current state at send time. Enqueuing one per edit
  would give them all the same stale `baseVersion`.
- **`opId` is generated once and reused across retries.** That reuse is the entire
  duplicate-prevention mechanism.

### Chosen backend: Google Sheets behind an Apps Script gateway

Decided 2026-08-29. The app will **never** call the Sheets or Drive APIs directly —
the `spreadsheets` scope is *sensitive*, which forces the OAuth consent screen to
stay in Testing status, where Google expires refresh tokens every 7 days. A thin
Apps Script Web App fronts the spreadsheet instead, so the app needs only
`openid email profile` and `LockService` provides the mutual exclusion that makes
idempotent upsert-by-UUID actually safe. Do not add a Google API client library.

`data/remote/RemoteDataSource.kt` is the seam. **Nothing in `ui/` or `domain/` may
import an implementation of it.**

## Authentication: Supabase, with a demo fallback

Supabase Auth (Google ID token) plus Postgres via PostgREST.

**If `local.properties` has no Supabase credentials, the app runs on an
in-memory demo backend** ([DemoBackend.kt](app/src/main/java/com/sankranthi/ledger/data/repo/demo/DemoBackend.kt))
instead of failing to launch. It implements the same repository interfaces and the
same approval rules, seeded with an admin, an approved member and one pending
request. The sign-in screen then offers role buttons instead of Google. This is
what makes a fresh clone runnable and the flow testable — keep it working when
you change a repository interface.

`ServiceLocator` picks the implementation from `AppConfig.hasSupabase`. It is a
plain object, not Hilt; it is the single seam to replace if DI becomes worthwhile.

Note the asymmetry: **only authentication varies by backend.** The ledger is
always Room-backed, whatever is configured. Supabase is still the live auth path
and will be re-pointed at the gateway in Phase 3; the Postgres schema in
`supabase/migrations/` is retained as the §28 migration target.

## Setup

### 1. `local.properties` (not committed)

```properties
sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk
supabase.url=https://<project-ref>.supabase.co
supabase.anonKey=<anon public key>
google.webClientId=<OAuth *web* client id>.apps.googleusercontent.com
```

Java `.properties` escaping applies: **escape the drive colon** and use forward
slashes, or lint's `PropertyEscape` check fails the build. Omit the last three
keys to stay in demo mode. They reach Kotlin as `BuildConfig` fields via
[app/build.gradle.kts](app/build.gradle.kts) — read them through `AppConfig`,
never `BuildConfig` directly.

### 2. Supabase project

1. Create a project, then run
   [supabase/migrations/0001_init.sql](supabase/migrations/0001_init.sql) in the
   SQL Editor (or `supabase db push`).
2. Authentication → Providers → enable **Google**, and paste the Google OAuth
   **web** client id and secret.
3. Sign in from the app. The first account becomes the admin; to hand that to
   someone else, run the `update public.profiles ...` snippet at the bottom of
   the migration.

### 3. Google OAuth

`google.webClientId` must be the **Web application** client id from Google Cloud
— the same one given to Supabase — *not* the Android client id. Supabase verifies
the ID token against it. You still need an Android OAuth client registered with
your package name and signing SHA-1 for Credential Manager to work on-device.

## Stack

| Piece | Version / choice |
| --- | --- |
| Gradle | 9.7.1 (wrapper committed) |
| Android Gradle Plugin | 9.3.2 |
| Kotlin | built into AGP 9 (see gotcha below) |
| Compose BOM | 2026.08.00 |
| compileSdk / targetSdk | 37 |
| minSdk | 24, with core library desugaring for `java.time` |
| Java / JVM target | 17 |
| UI | Jetpack Compose + Material 3, `navigation-compose` |
| Local store | Room 2.8.4 via KSP 2.3.11 (see gotcha below) |
| Shared backend | Google Sheets + Drive behind an Apps Script gateway (Phase 4) |
| Auth (current) | `supabase-kt` 3.8.0, to be re-pointed at the gateway in Phase 3 |
| Sign-in | `androidx.credentials` + `googleid` → Supabase `IDToken` provider |
| Serialization | `kotlinx.serialization` |

## Layout

```
supabase/migrations/0001_init.sql   schema, triggers, RLS — the real rulebook
gradle/libs.versions.toml           version catalog; ALL dependency versions
app/src/main/java/com/sankranthi/ledger/
  MainActivity.kt                   inits ServiceLocator, hosts SankranthiApp
  data/
    AppConfig.kt                    BuildConfig-backed settings
    ServiceLocator.kt               builds Room + picks the auth backend
    GoogleSignInClient.kt           Credential Manager -> Google ID token + nonce
    model/Access.kt                 Role, AccessStatus, Permission, Profile
    model/Ledger.kt                 domain models — no serialisation annotations
    local/AppDatabase.kt            Room database (v1); schemas/ is committed
    local/Converters.kt             every stored enum needs one here
    local/entity/                   storage shapes + SyncMeta + the outbox
    local/dao/                      Flow-returning DAOs
    local/Mappers.kt                entity <-> domain
    repository/LedgerRepository.kt  offline-first writes: Room + outbox, atomically
    remote/RemoteDataSource.kt      THE SEAM — interface only, no impl yet
    repo/Repositories.kt            auth + members interfaces
    repo/supabase/                  auth + members implementations
    repo/demo/                      in-memory auth + members
  ui/
    nav/SankranthiApp.kt            session gate + bottom nav + NavHost
    auth/                           SessionViewModel, SignInScreen, PendingApprovalScreen
    dashboard/DashboardScreen.kt    read-only overview
    livestock/                      list + editor dialog
    expenses/                       list + editor dialog
    admin/                          AdminViewModel, AdminScreen (Pending / Members tabs)
    ledger/LedgerViewModel.kt       Room Flows; no load(), nothing to refresh
    sync/SyncIndicator.kt           the §19 status chip
    common/Components.kt            SummaryTile, DateField, PickerField, ErrorBanner…
    theme/                          SankranthiTheme
  util/Money.kt, util/Dates.kt
```

Navigation has exactly three shells, chosen by `SessionState` in `SankranthiApp`:
sign-in → waiting room → the books. An unapproved account cannot reach a data
screen because those composables are never created for it.

## Commands

```bash
./gradlew assembleDebug                  # debug APK -> app/build/outputs/apk/debug/
./gradlew assembleRelease                # minified, unsigned release APK
./gradlew testDebugUnitTest              # JVM unit tests
./gradlew connectedDebugAndroidTest      # instrumented tests (needs device/emulator)
./gradlew compileDebugAndroidTestKotlin  # compile instrumented tests without a device
./gradlew lintDebug                      # Android Lint (fails the build on errors)
./gradlew installDebug
./gradlew clean
```

Gradle needs a JDK 17+ on `JAVA_HOME`; the Android Studio runtime works:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # Git Bash
```

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # PowerShell
```

## Gotchas worth knowing before you edit

- **AGP 9 has built-in Kotlin support.** Do *not* add the
  `org.jetbrains.kotlin.android` plugin — AGP 9 fails the build if it is applied.
  The Compose compiler and `kotlinx.serialization` plugins *are* still applied
  normally.
- **Every enum stored by a Room entity needs a converter in `Converters.kt`.**
  Without one Room silently stores the enum by `name` (`PENDING`) while queries
  written against `wire` look for `pending`; they never match, and reading the row
  back throws `Can't convert value to enum`. DAO parameters are typed as the enum,
  not `String`, so there is only one representation to get wrong.
- **KSP must be ≥ 2.3.1 for AGP 9 built-in Kotlin** (pinned at 2.3.11). `kapt` is
  incompatible with AGP 9 entirely. Do not put `@Parcelize` on a Room entity —
  KSP still fails to resolve that combination under built-in Kotlin.
- **Room's exported schemas in `app/schemas/` are committed** and are inputs to
  migration tests. `fallbackToDestructiveMigration` is deliberately absent: losing
  a partner's unsynced records on an app update would violate §20.
- **Money is stored as `Long` paise**, never a `Double`. All conversion goes
  through `util/Money.kt`; `parseToMinor` refuses negatives and sub-paise
  precision rather than rounding silently. Postgres columns are `bigint`.
- **Dates are ISO `yyyy-MM-dd` strings** end to end, matching Postgres `date`.
  `util/Dates.kt` owns parsing, display and the epoch-millis conversion the
  Material date picker needs.
- **The client never sets `created_by` / `created_by_name`.** The
  `stamp_author` trigger does, and treats authorship as immutable on update.
  The `*Payload` types in the Supabase repository deliberately omit those fields.
- **Postgrest decoding is lenient** (`ignoreUnknownKeys`), configured in
  `ServiceLocator`. Adding an audit column will not break existing models.
- Enum values crossing the wire have an explicit `wire` string *and* a
  `@SerialName`. Change one and you must change the other, plus the Postgres enum.

## Conventions

- **Dependencies go in [gradle/libs.versions.toml](gradle/libs.versions.toml)**,
  never hardcoded in a build script.
- **Compose only.** New UI is a `@Composable`; no XML layouts, `Fragment`s, or
  `AppCompatActivity`. `res/values/themes.xml` is only the pre-Compose launch
  theme; real theming is `ui/theme/Theme.kt`.
- Screens take state and callbacks as parameters and hold no ViewModel reference,
  so they stay previewable and testable. ViewModels are wired in
  `ui/nav/SankranthiApp.kt`.
- Every screen-level composable takes `modifier: Modifier = Modifier` and has a
  `@Preview` where it is cheap to build one.
- ViewModels expose a single immutable `UiState` `StateFlow`; writes re-read from
  the repository afterwards so the UI shows what the database actually stored
  (including an RLS refusal).
- Gate write affordances on `profile.can(Permission.X)`, and add the matching RLS
  policy in the same change.
- User-facing text lives inline in composables today; move it to
  `res/values/strings.xml` if localisation is ever needed.
- Keep `debug` on `applicationIdSuffix = ".debug"` so both builds coexist on a
  device.

## Before saying a change works

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
```

must pass; add `assembleRelease` when you change dependencies or ProGuard rules.
Lint is configured to fail on errors — do not skip it.

**Compiling the instrumented tests is not evidence.** `./gradlew
connectedDebugAndroidTest` (or `adb shell am instrument`) on a real device or
emulator is, and it is required for any change under `data/local/`,
`data/repository/` or `sync/` — the storage layer's real failures only appear at
runtime. Two traps that cost real time here:

- **Do not launch the emulator with `-gpu swiftshader_indirect`.** Compose
  surfaces fail to initialise, the host activity is destroyed immediately, and
  every UI test fails with "No compose hierarchies found" — which looks exactly
  like a code bug and is not one.
- **Assert against merged-tree-invisible nodes with `useUnmergedTree = true`.**
  `ExtendedFloatingActionButton` merges its descendants and exposes no `Text`, so
  a plain `onNodeWithText(...).assertDoesNotExist()` on a FAB label passes whether
  or not the button is there — a silent false pass on a permission gate.

Gradle's configuration cache is on. If a build fails oddly right after you move
or rename resource files, incremental state is the likely cause — rerun with
`--rerun-tasks`, or `./gradlew clean` first.

## Package name

Everything is under `com.sankranthi.ledger` (`namespace` and `applicationId` in
[app/build.gradle.kts](app/build.gradle.kts)). Renaming means updating both,
the `src/*/java` directory paths, every `package`/`import`, and the Android OAuth
client registered with Google.
