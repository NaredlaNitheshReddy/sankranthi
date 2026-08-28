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

## Backend: Supabase, with a demo fallback

Supabase Auth (Google ID token) plus Postgres via PostgREST.

**If `local.properties` has no Supabase credentials, the app runs on an
in-memory demo backend** ([DemoBackend.kt](app/src/main/java/com/example/sankranthi/data/repo/demo/DemoBackend.kt))
instead of failing to launch. It implements the same repository interfaces and the
same approval rules, seeded with an admin, an approved member and one pending
request. The sign-in screen then offers role buttons instead of Google. This is
what makes a fresh clone runnable and the flow testable — keep it working when
you change a repository interface.

`ServiceLocator` picks the implementation from `AppConfig.hasSupabase`. It is a
plain object, not Hilt; it is the single seam to replace if DI becomes worthwhile.

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
| Backend | `supabase-kt` 3.8.0 (auth + postgrest), Ktor OkHttp engine |
| Sign-in | `androidx.credentials` + `googleid` → Supabase `IDToken` provider |
| Serialization | `kotlinx.serialization` |

## Layout

```
supabase/migrations/0001_init.sql   schema, triggers, RLS — the real rulebook
gradle/libs.versions.toml           version catalog; ALL dependency versions
app/src/main/java/com/example/sankranthi/
  MainActivity.kt                   inits ServiceLocator, hosts SankranthiApp
  data/
    AppConfig.kt                    BuildConfig-backed settings
    ServiceLocator.kt               picks Supabase vs demo, builds the client
    GoogleSignInClient.kt           Credential Manager -> Google ID token + nonce
    model/Access.kt                 Role, AccessStatus, Permission, Profile
    model/Ledger.kt                 TradeKind, LivestockEntry, Expense, LedgerSummary
    repo/Repositories.kt            SessionState + the three interfaces
    repo/supabase/                  live implementations
    repo/demo/                      in-memory implementations
  ui/
    nav/SankranthiApp.kt            session gate + bottom nav + NavHost
    auth/                           SessionViewModel, SignInScreen, PendingApprovalScreen
    dashboard/DashboardScreen.kt    read-only overview
    livestock/                      list + editor dialog
    expenses/                       list + editor dialog
    admin/                          AdminViewModel, AdminScreen (Pending / Members tabs)
    ledger/LedgerViewModel.kt       shared by dashboard, livestock, expenses
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

must pass; add `compileDebugAndroidTestKotlin` when you touch `src/androidTest`,
and `assembleRelease` when you change dependencies or ProGuard rules. Lint is
configured to fail on errors — do not skip it.

Gradle's configuration cache is on. If a build fails oddly right after you move
or rename resource files, incremental state is the likely cause — rerun with
`--rerun-tasks`, or `./gradlew clean` first.

## Package name

Everything is under `com.example.sankranthi` (`namespace` and `applicationId` in
[app/build.gradle.kts](app/build.gradle.kts)). Renaming means updating both,
the `src/*/java` directory paths, every `package`/`import`, and the Android OAuth
client registered with Google.
