# Sankranthi

Android app for the books of a small livestock partnership: record livestock
purchases and sales, track the maintenance expenses of running the operation, and
see the net position. Kotlin, Jetpack Compose, Material 3, Supabase.

Access is invite-only in practice — you sign in with Google, which creates an
access request, and an admin approves it and decides what you may edit.

## Screens

| | |
| --- | --- |
| **Overview** | Net position, sales vs purchases vs expenses, head count, recent activity. Every approved member. |
| **Livestock** | Purchases and sales. Editing needs the *livestock* right. |
| **Expenses** | Feed, veterinary, labour, transport, shed repairs, utilities. Editing needs the *expenses* right. |
| **Admin** | Admins only. **Pending requests** tab to approve or reject, **Members** tab to grant and revoke edit rights. |

## Requirements

- Android Studio (recent stable), or a JDK 17+ on `JAVA_HOME`
- Android SDK with the **API 37** platform
- A Supabase project (optional — see demo mode below)

## Run it without a backend

Clone and run. With no Supabase credentials configured the app starts on an
in-memory demo backend, and the sign-in screen offers three buttons — admin,
approved member, and a brand-new account — so you can walk the whole
request → approve → grant-permissions flow immediately. Nothing is persisted.

```bash
./gradlew installDebug
```

## Connect it to Supabase

1. **Create the schema.** Run
   [supabase/migrations/0001_init.sql](supabase/migrations/0001_init.sql) in your
   project's SQL Editor. It creates the profile/approval tables, the two ledgers,
   the audit triggers, and the row-level security policies that enforce
   permissions server-side.

2. **Enable Google auth.** Supabase dashboard → Authentication → Providers →
   Google. Paste your Google Cloud OAuth **web** client id and secret.

3. **Fill in `local.properties`** (not committed):

   ```properties
   sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk
   supabase.url=https://<project-ref>.supabase.co
   supabase.anonKey=<anon public key>
   google.webClientId=<web client id>.apps.googleusercontent.com
   ```

   Escape the drive colon and use forward slashes — Java `.properties` rules, and
   lint will fail the build otherwise.

4. **Sign in.** The first account to sign in becomes the approved admin;
   everyone after that lands in the pending queue. To put someone else in charge,
   use the `update public.profiles ...` snippet at the end of the migration.

The `google.webClientId` must be the **Web application** client id (the one
Supabase has), not the Android one. You still need an Android OAuth client
registered with your package name and signing SHA-1 for on-device sign-in.

## Build and test

```bash
./gradlew assembleDebug                # debug APK
./gradlew assembleRelease              # minified, unsigned release APK
./gradlew testDebugUnitTest            # JVM unit tests
./gradlew connectedDebugAndroidTest    # instrumented tests (needs a device)
./gradlew lintDebug                    # Android Lint
```

## Notes

- Money is stored as integer paise so the books stay exact; conversion lives in
  `util/Money.kt`.
- Permissions are enforced by Postgres row-level security. The UI hides what you
  cannot do, but the database is what actually refuses it.

Single Gradle module, `:app`. Dependency versions are centralised in
[gradle/libs.versions.toml](gradle/libs.versions.toml). See
[CLAUDE.md](CLAUDE.md) for the access model, architecture and conventions.
