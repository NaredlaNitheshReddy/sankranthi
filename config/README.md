# config/

Runtime configuration, injected at build time and **never committed**.

```bash
cp config/dev.example.json config/dev.json   # then fill in real values
flutter run --dart-define-from-file=config/dev.json
```

`config/*.json` is gitignored; only `*.example.json` is tracked. Read these values through `AppConfig`,
never `String.fromEnvironment` at a call site.

| Key | What it is |
| --- | --- |
| `GATEWAY_URL` | The Apps Script Web App `/exec` URL. Not a secret — every request is authorised from its token, never from URL possession — but it is environment-specific, so it does not belong in the repo. |
| `GOOGLE_WEB_CLIENT_ID` | The **Web application** OAuth client id, used as `serverClientId`. Must match the `aud` the gateway verifies. Not the Android client id — you also need one of those registered with the package name and signing SHA-1 for on-device sign-in, but it is not referenced here. |

Per REQUIREMENTS §60, no service-account key, API secret or private credential goes in this file or in the
APK. These two values are both public identifiers.
