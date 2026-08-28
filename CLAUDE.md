# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

**Sankranthi** — a single-module Android app written in Kotlin with Jetpack Compose
and Material 3. It is currently a starter scaffold: one activity, one screen.

## Stack

| Piece | Version / choice |
| --- | --- |
| Gradle | 9.7.1 (wrapper committed) |
| Android Gradle Plugin | 9.3.2 |
| Kotlin | built into AGP 9 (see note below) |
| Compose BOM | 2026.08.00 |
| compileSdk / targetSdk | 37 |
| minSdk | 24 |
| Java / JVM target | 17 |
| UI toolkit | Jetpack Compose + Material 3 (no XML layouts, no View system) |
| Build scripts | Gradle Kotlin DSL (`.kts`) + version catalog |

**AGP 9 has built-in Kotlin support.** Do *not* add the
`org.jetbrains.kotlin.android` plugin — AGP 9 fails the build if it is applied.
The Compose compiler plugin (`org.jetbrains.kotlin.plugin.compose`) *is* still
required and is applied in [app/build.gradle.kts](app/build.gradle.kts).

## Layout

```
build.gradle.kts              root build script (plugins declared, not applied)
settings.gradle.kts           repositories + module list
gradle/libs.versions.toml     version catalog — ALL dependency versions live here
gradle.properties            JVM args, AndroidX flags, config cache
app/
  build.gradle.kts            app module config
  proguard-rules.pro          R8 rules for the release build
  src/main/
    AndroidManifest.xml
    java/com/example/sankranthi/
      MainActivity.kt         entry point; hosts HomeScreen
      ui/theme/               Color.kt, Type.kt, Theme.kt (SankranthiTheme)
    res/
      values/                 strings.xml, colors.xml, themes.xml (launch theme only)
      drawable/               ic_launcher_foreground.xml
      mipmap-anydpi/          flat launcher icon, API 24-25 fallback
      mipmap-anydpi-v26/      adaptive launcher icon, API 26+
      xml/                    backup_rules.xml, data_extraction_rules.xml
  src/test/                   JVM unit tests
  src/androidTest/            instrumented / Compose UI tests
```

## Commands

Run from the repo root. On Windows use `./gradlew` from Git Bash, or
`gradlew.bat` from PowerShell/cmd.

```bash
./gradlew assembleDebug                  # build debug APK -> app/build/outputs/apk/debug/
./gradlew assembleRelease                # build minified, unsigned release APK
./gradlew testDebugUnitTest              # JVM unit tests
./gradlew connectedDebugAndroidTest      # instrumented tests (needs device/emulator)
./gradlew compileDebugAndroidTestKotlin  # compile instrumented tests without a device
./gradlew lintDebug                      # Android Lint (fails the build on lint errors)
./gradlew installDebug                   # install on the connected device
./gradlew clean
```

### JDK

Gradle needs a JDK 17+ on `JAVA_HOME`. The Android Studio bundled runtime works:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # Git Bash
```

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # PowerShell
```

### local.properties

Not committed (it is in `.gitignore`) — Android Studio creates it, or write it
by hand. Java `.properties` escaping applies: **escape the drive colon** and use
forward slashes, or lint's `PropertyEscape` check fails the build.

```properties
sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk
```

## Conventions

- **Dependencies go in [gradle/libs.versions.toml](gradle/libs.versions.toml)**, never
  hardcoded in a build script. Reference them as `libs.some.library`.
- **Compose only.** New UI is a `@Composable`; don't introduce XML layouts,
  `Fragment`s, or `AppCompatActivity`. `res/values/themes.xml` exists solely as
  the pre-Compose launch theme — theming lives in `ui/theme/Theme.kt`.
- Every screen-level composable takes `modifier: Modifier = Modifier` as its
  first optional parameter and has a `@Preview` sibling.
- Wrap previews and instrumented UI tests in `SankranthiTheme { }` so they match
  the real app.
- User-facing text goes in `res/values/strings.xml`, not inline in Kotlin.
- Keep `debug` on `applicationIdSuffix = ".debug"` so debug and release builds can
  coexist on one device.
- Release builds run R8 with resource shrinking. If you add reflection,
  serialization, or JNI entry points, add keep rules to `app/proguard-rules.pro`.
- The debug build type is unsigned-for-release: `assembleRelease` produces
  `app-release-unsigned.apk`. Add a `signingConfigs` block when a real release is needed.

## Before saying a change works

`./gradlew assembleDebug testDebugUnitTest lintDebug` must pass. Lint is
configured to fail on errors, so don't skip it. Add
`compileDebugAndroidTestKotlin` when you touch `src/androidTest`.

Gradle's configuration cache is on. If a build fails oddly right after you move
or rename resource files, incremental state is the likely cause — rerun with
`--rerun-tasks`, or `./gradlew clean` first.

## Package name

Everything is under `com.example.sankranthi` (the `namespace` and `applicationId`
in [app/build.gradle.kts](app/build.gradle.kts)). If you rename it, update both
of those, the `src/*/java` directory paths, and every `package` / `import` line.
