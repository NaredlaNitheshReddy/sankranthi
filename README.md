# Sankranthi

A local-first Android app for keeping the books of a small livestock organisation: expenses and receipts,
stock, livestock counts, medicine and treatments, with roles and permissions, reports and an audit trail.

**It works with no internet.** Records are written to a local SQLite database and appear immediately;
synchronisation to the shared backend happens quietly in the background. You should never have to care
whether you are online.

## Status

Rebuilt from scratch in Flutter, starting 2026-08-29. Progress and phase status live in [PLAN.md](PLAN.md)
and [TASKS.md](TASKS.md); the architecture is in [CLAUDE.md](CLAUDE.md). An earlier Kotlin/Compose client lives in git
history before the clean-slate commit.

## Specification

[REQUIREMENTS.md](REQUIREMENTS.md) is the specification of record — a 125-section document covering the
architecture, schemas, sync engine, screens, permission model and acceptance scenarios.

[CLAUDE.md](CLAUDE.md) holds the eight design constraints that **correct or extend** the spec, and the rules
that lose data if broken. Read it before changing anything under `lib/data/`.

## Architecture

```
UI  ──►  Riverpod (StreamProvider over a drift .watch())
             ▲ reads only, never the network
Controller ──►  Repository  ──►  one drift transaction { upsert row; enqueue operation }
                                        │
                                        ▼
                            drift/SQLite  +  sync_operations outbox
                                        │
                            SyncEngine  ──►  SyncRemote (the seam)
                                                  │
                          Apps Script gateway (business data)  +  Google Drive (receipt files)
```

The local database is the only thing the UI reads. Business metadata syncs through a thin Apps Script Web App
in front of a Google Sheet; receipt images go straight to Google Drive. Total infrastructure cost: ₹0.

## Requirements

- Flutter (stable channel) with the Android toolchain — `flutter doctor` must be clean for Android
- An Android device or emulator. **Do not launch an emulator with `-gpu swiftshader_indirect`.**

## Running it

```bash
flutter pub get
cp config/dev.example.json config/dev.json     # fill in the two values; see config/README.md
flutter run --dart-define-from-file=config/dev.json
```

## Commands

```bash
flutter analyze                    # strict-casts, strict-inference, strict-raw-types
flutter test                       # unit + widget + golden
flutter test integration_test/     # needs a device or emulator
flutter build apk --release        # signed
node --test gateway/test/          # the Apps Script gateway suite
```

`flutter analyze && flutter test` must pass before a change is done. Compiling is not evidence — the storage
and sync layers only fail at runtime, so anything under `lib/data/` needs the integration tests on a real
device.

## Notes

- **Money is stored as integer paise, quantities as integer thousandths.** Never a `double` — stock balances
  are derived on every read, so float error would compound silently.
- **Deletes are soft** so removals propagate to other devices. Admins can view and restore deleted records
  within the retention window.
- **A receipt captured offline has no Drive file id, and that is valid.** Its local UUID is its permanent
  identity; the Drive id is only a remote reference.
