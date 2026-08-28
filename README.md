# Sankranthi

Android app — Kotlin, Jetpack Compose, Material 3.

## Requirements

- Android Studio (recent stable) or a JDK 17+ on `JAVA_HOME`
- Android SDK with **API 37** platform and build-tools 36.0.0

## Setup

1. Clone, then open the project root in Android Studio — it will create
   `local.properties` and sync Gradle.

   To build from the command line instead, create `local.properties` yourself:

   ```properties
   sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk
   ```

   (Escape the drive colon; use forward slashes.)

2. Build and run:

   ```bash
   ./gradlew assembleDebug     # APK -> app/build/outputs/apk/debug/
   ./gradlew installDebug      # install on a connected device/emulator
   ```

## Tests and checks

```bash
./gradlew testDebugUnitTest           # JVM unit tests
./gradlew connectedDebugAndroidTest   # instrumented tests (needs a device)
./gradlew lintDebug                   # Android Lint
```

## Project structure

Single Gradle module, `:app`. Dependency versions are centralised in
[gradle/libs.versions.toml](gradle/libs.versions.toml). See
[CLAUDE.md](CLAUDE.md) for conventions and the full command reference.
