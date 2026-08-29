# Architecture Review — offline-first Sankranthi

Review of the technical requirements dated 2026-08-29, originally the deliverable
for §32 items 1–9. Kept as the living architecture document: the backend decision
is recorded in §2, and Phases 0a and 2 are now implemented (see §9).

**Verdict:** the offline-first Android half of the design is sound and I would
build it as specified. The backend half has one finding that makes the design as
written unable to meet its own headline requirement, and it needs a structural
change. Details below.

---

## 1. The finding that changes the plan

> "User remains logged in." (§1) — and — "Google Sheets will initially act as the
> centralized structured-data store." (§11)

These two cannot both hold with the app calling the Sheets API directly.

- `https://www.googleapis.com/auth/spreadsheets` is classified by Google as a
  **sensitive scope**. Requesting it means the OAuth consent screen must either
  be verified, or stay in **Testing** publishing status.
- **In Testing status, Google expires refresh tokens after 7 days.** Every user
  gets silently logged out roughly weekly and has to re-consent.

So the app either logs everyone out every 7 days, or you submit a private
3-person app for Google OAuth verification (written justification, scope
justification, demo video, brand verification — no CASA security audit for
sensitive-only scopes, but still a review with turnaround measured in weeks).

Neither is acceptable for a WhatsApp-like experience, and neither is what the
requirements intended.

### The fix: don't let the app talk to Google Sheets at all

Put a **Google Apps Script Web App** in front of the spreadsheet as a thin sync
gateway. This is the "minimal intermediary service" §15 explicitly allows, and it
is free, serverless, and needs no container, VM or cloud account.

```text
Android app
    │  HTTPS POST + Google ID token (openid email profile only)
    ▼
Apps Script Web App  ── runs as the sheet owner
    ├── verifies the ID token (tokeninfo, checks aud/iss/exp)
    ├── checks the email against the Users allowlist
    ├── takes LockService script lock   ← serialises all writers
    ├── upserts by UUID  (real idempotency)
    ├── assigns serverSeq (monotonic)
    └── writes Sheets + Drive as itself
```

What this buys:

| Problem | Resolved how |
| --- | --- |
| 7-day logout | App requests only `openid email profile` — **non-sensitive**. Consent screen publishes to production with no verification, so refresh/ID tokens behave normally. |
| Secrets in the APK (§15) | App ships only the OAuth **client ID**, which is public by design. No service-account key, no client secret, no sheet credentials. |
| Duplicate rows on retry (§21) | `LockService.getScriptLock()` gives real mutual exclusion, so "does UUID exist? → else append" stops being a race. |
| Users needing Sheets/Drive permission | They never do. The script owns the sheet and the Drive folder. |
| Incremental download (§17) | Gateway assigns a monotonic `serverSeq`; client asks for `seq > lastSeq`. |
| Migration path (§22, §28) | The gateway *is* an HTTP API. Swapping it for Supabase is one `RemoteDataSource` implementation. |

Honest costs of Apps Script, at consumer-account quotas:

- **6 minutes hard cap per execution.** Sync batches must be bounded; do not
  design an unbounded "upload everything" call.
- **90 minutes/day total trigger runtime**, **20,000 UrlFetch calls/day.** Each
  ID-token verification is one UrlFetch — cache the verification result in
  `CacheService` keyed by a token hash so a sync burst costs one call, not ten.
  At three users neither ceiling is reachable.
- **Cold starts are slow** — commonly 1–3 s. This is invisible for writes
  *precisely because* the architecture is offline-first, which is the strongest
  argument for the offline-first design being right.
- `LockService` serialises writers. Correct at 3 users; would be a bottleneck at 300.

---

## 2. The larger question: this repo already has the "future version"

§28 names Supabase/PostgreSQL as the future target. **That target is already
built and committed here** (commit `0b3a3e3`): Supabase Auth with Google ID
token sign-in, Postgres schema, row-level security, and an admin approval
workflow that is a strict superset of the §14 allowlist.

Comparing the two backends on the requirements that actually matter here:

| Requirement | Sheets + Apps Script | Supabase (already in repo) |
| --- | --- | --- |
| Idempotent upsert by UUID (§21) | Needs `LockService`; correct but hand-rolled | `PRIMARY KEY` + `INSERT … ON CONFLICT` — free and atomic |
| Transactions / partial failure (§8) | None. Multi-row writes can half-apply | Real transactions |
| Optimistic concurrency (§10) | Hand-rolled compare-and-set inside the lock | `UPDATE … WHERE version = ?` |
| Authorization (§14) | Gateway checks a sheet column | RLS enforced by the database on every query |
| Receipt storage (§12) | Drive via gateway, 6-min/payload limits | Supabase Storage, 1 GB free, resumable uploads |
| Incremental sync (§17) | `serverSeq` column you maintain | Indexed `updated_at` / sequence, server-side filtering |
| Growth ceiling | 10 M cells per spreadsheet | 500 MB Postgres |
| Ongoing cost | £0 | £0 on free tier |
| **Free-tier gotcha** | Google API per-project quotas | **Projects pause after ~7 days of low activity, and long-paused projects are eventually deleted** |

The Supabase pausing risk is real and worth stating plainly — but it is mitigated
by a scheduled ping (a free GitHub Actions cron, or just the app's own periodic
sync), which is a few lines. Compare that with mitigating the Sheets path's
7-day token expiry, which requires a Google verification review.

### Decision taken (2026-08-29)

**Google Sheets will be the writable source of truth, behind the Apps Script
gateway.** My recommendation was the other way round, and the trade-offs above
stand on the record, but a spreadsheet that partners can edit directly is a
product requirement I can't substitute away. The rest of this document is written
for that choice.

Consequences to go in with open eyes:

1. **Idempotency and optimistic concurrency become our code, not the database's.**
   `LockService` plus an applied-operations log replaces `PRIMARY KEY` and
   `ON CONFLICT`. §5.1 below specifies it. This is the single largest piece of
   correctness risk in the project and deserves the most testing.
2. **The committed Supabase backend gets shelved.** `SupabaseRepositories.kt`,
   the `supabase-kt` dependencies and `supabase/migrations/0001_init.sql` stop
   being the live path. I recommend **leaving them in the tree, unwired**, rather
   than deleting: the schema is a working, reviewed Postgres model and it is
   exactly the §28 migration target. It costs nothing to keep and saves redoing
   the design later. The `RemoteDataSource` seam is what makes that cheap.
3. **The Google sign-in flow already committed is reused unchanged.** It obtains a
   Google ID token via Credential Manager, which is precisely what the gateway
   needs. What changes is where the token goes — the gateway instead of Supabase.
4. **Sheets ceilings are now real limits, not trivia.** 10 M cells per
   spreadsheet, and at ~13 columns that is roughly 750 k rows — comfortable, but
   the tombstone purge policy in §3.4 stops being optional book-keeping.

Room, the outbox, sync metadata, WorkManager and the receipt pipeline are
unaffected by this decision and remain ~80% of the effort.

---

## 3. Corrections to the specification

These apply regardless of which backend wins.

### 3.1 `syncStatus` on the row is not enough — use an outbox (§7, §9)

A single `syncStatus` column cannot express *ordering* when a row is edited twice
offline, cannot record which fields changed, and gives retry logic nowhere to
store `attemptCount` or `lastError`.

Add a dedicated queue table:

```text
pending_operations
  opId          UUID   -- idempotency key for the OPERATION, not the row
  entityType    TEXT
  entityId      UUID
  opType        INSERT | UPDATE | DELETE | UPLOAD_RECEIPT
  payload       TEXT (JSON)
  baseVersion   INT
  attemptCount  INT
  lastError     TEXT
  createdAt     INTEGER
```

§21 says the record UUID is the idempotency key. That is necessary but not
sufficient: two different edits to the same row are two operations that must not
be deduplicated against each other. Dedupe on `opId`.

### 3.2 Client clocks are not a conflict-resolution mechanism (§10)

"Latest `updatedAt` wins" using device clocks is unsafe — clock skew and
user-settable clocks mean a stale write can win permanently. Use **optimistic
concurrency instead**: the client sends `baseVersion`, the server compares, and
rejects with a conflict if it moved. The server owns `version` and
`serverUpdatedAt`; keep the client's `updatedAt` for display only.

### 3.3 Pending local edits vs. incoming remote changes (§7 vs §17)

The spec doesn't say what happens when a download arrives for a row that is
locally `PENDING`. Without a rule you will silently destroy the user's offline
edit — a direct violation of §20. Proposed rule: **a locally pending edit is
never overwritten by a download**; the download is stashed and reconciled after
the local operation uploads.

### 3.4 Soft deletes need a purge policy (§10)

`deleted = true` forever means the store grows without bound, and a spreadsheet
has a hard 10 M cell ceiling. Track a per-device sync high-water mark and purge
tombstones once every device has synced past them. At this scale you can defer
the implementation, but the ceiling should be documented rather than discovered.

### 3.5 Room + KSP under AGP 9 is a live risk (§6)

This project is on AGP 9 with built-in Kotlin. Room needs KSP, and:

- KSP added AGP 9 built-in-Kotlin support in **2.3.1**; 2.3.10 fixed R-class
  resolution under it.
- There is an **open KSP issue (#3053)** where Room entities annotated
  `@Parcelize` fail resolution under AGP 9 built-in Kotlin, still failing as of
  2.3.10.
- `kapt` is **incompatible** with AGP 9 — KSP or `com.android.legacy-kapt` only.

Mitigations: pin KSP ≥ 2.3.11, never put `@Parcelize` on a Room entity (use a
separate UI model), and keep `com.android.legacy-kapt` as the escape hatch.
**This should be proven by a throwaway spike before committing to Phase 2** — it
is the one unknown that could invalidate the local-database plan.

### 3.6 Skip Hilt for now (§23)

WorkManager workers need dependency injection, which is the usual trigger for
Hilt. I recommend **not** adding it yet: it means a second KSP processor on a
toolchain where KSP already has known AGP 9 rough edges, and §31.14 says don't
over-engineer. The existing `ServiceLocator` plus a ~20-line custom
`WorkerFactory` covers it. Revisit at Phase 6 if the graph gets awkward.

### 3.7 The app has almost nothing left to protect (§16)

Worth noticing: with the gateway design the app never holds a long-lived secret.
The Google ID token is short-lived (~1 hour) and silently re-obtainable through
Credential Manager. So §16's "do not store tokens in plain text" largely
dissolves — there is no refresh token to store.

That leaves app-unlock, which should be `androidx.biometric`'s `BiometricPrompt`
with `DEVICE_CREDENTIAL` as the fallback. Do not implement a custom PIN.

One caution: `androidx.security:security-crypto` (`EncryptedSharedPreferences`)
has been deprecated by Google — check its status before adopting it. Given the
point above, you probably need neither it nor a Keystore key at all in v1.

### 3.8 Distribute a signed release build, not a debug APK (§29)

Debug builds set `debuggable=true`, skip R8, and are signed with the shared debug
key. For three real people holding real financial records on real phones, create
your own keystore now (kept out of git, backed up — losing it means no upgrades
ever) and hand out `assembleRelease` output. Play Store publishing stays out of
scope as §29 says.

### 3.9 VS Code as primary editor (§4)

Workable — Gradle CLI builds already work here. Two practical limits: no Compose
`@Preview` rendering, and weaker Kotlin language support than IntelliJ. Keep
Android Studio for previews, Logcat, the profiler and the emulator, and edit in
VS Code. On PowerShell the wrapper is `gradlew.bat`, not `./gradlew`.

### 3.10 Receipt sizes must be bounded before upload (§13)

Apps Script's 6-minute cap and payload limits, and base64's 33% inflation, make
unbounded camera JPEGs (often 4–8 MB) a real failure mode. Downscale client-side
to ~1024 px on the long edge at JPEG q80 (typically < 300 KB) and use
`androidx.exifinterface` to respect orientation. Do this even on Supabase — it
keeps you inside the 1 GB storage and 5 GB egress free tier for years.

---

## 4. What §30's phases already have done

The repo is further along than the phase plan assumes:

| Phase | Status |
| --- | --- |
| **1 — basic Compose app, navigation, theme, installable APK** | **Done.** Committed and verified. |
| **2 — Room, SQLite, entity, CRUD, offline** | **Not started.** Currently online-only. This is the real next task. |
| **3 — Google login, authorized users, persistent login, logout** | **Done**, via Supabase Auth + an admin approval queue that is stronger than a flat allowlist. Needs re-pointing at the gateway only if you choose Sheets. |
| **4 — backend sync, one entity** | Not started. |
| **5 — WorkManager, pending queue, retry, idempotency** | Not started. |
| **6 — Drive receipts** | Not started. |
| **7 — multi-user testing** | Not started. |

I would insert a **Phase 0**: prove Room + KSP compiles under AGP 9, and prove one
authenticated round-trip to the chosen backend. Both are the plan's real unknowns
and both are an afternoon.

---

## 5. Recommended architecture

```text
                        ANDROID PHONE
        ┌──────────────────────────────────────────┐
        │  Jetpack Compose UI  (Material 3)        │
        ├──────────────────────────────────────────┤
        │  ViewModel   — exposes one UiState flow  │
        ├──────────────────────────────────────────┤
        │  Repository  — reads Room, writes Room   │
        │                + enqueues an outbox op   │
        ├───────────────┬──────────────────────────┤
        │  Room/SQLite  │  pending_operations      │
        │  (UI truth)   │  (outbox)                │
        ├───────────────┴──────────────────────────┤
        │  SyncManager  ← invoked by SyncWorker     │
        └───────────────┬──────────────────────────┘
                        │ WorkManager, network-constrained,
                        │ exponential backoff
                        ▼
              RemoteDataSource  (interface)   ← the migration seam
                        │
                        ▼
            SheetsGatewayDataSource            ← the live path
              plain HTTPS + JSON
                        │
                        ▼
            Apps Script Web App
            ├── ID-token verification + allowlist
            ├── LockService (serialises writers)
            ├── idempotent upsert by opId
            ├── serverSeq assignment
            ├── Google Sheets  (structured data)
            └── Google Drive   (receipt files)

            SupabaseDataSource                 ← kept, unwired, §28 target
```

Non-negotiables carried over from §31, all of which I endorse:

1. UI reads Room only, never the network. Writes hit Room and return immediately.
2. The cloud is the shared source of truth; Room is the local cache plus outbox.
3. The SQLite file is never shared through Drive.
4. Every record carries a client-generated UUID.
5. Every sync operation is idempotent on `opId`.
6. `RemoteDataSource` is the only type that knows what the backend is. Nothing in
   `ui/` or `domain/` may import a Sheets or Supabase type.

---

## 5b. Apps Script gateway design

Now the chosen path, this needs specifying rather than sketching.

### 5b.1 Idempotent upsert — the correctness core

Apps Script Web Apps expose only `doGet`/`doPost`, so the gateway is **one POST
endpoint with an action field**. One round trip does upload *and* download, which
keeps cold starts and the 6-minute cap out of the way.

```text
POST <deployment-url>/exec
{
  "idToken":    "<Google ID token>",
  "action":     "sync",
  "deviceId":   "<per-install UUID>",
  "lastSeq":    1234,
  "operations": [
    { "opId": "<uuid>", "entityType": "transaction", "entityId": "<uuid>",
      "opType": "UPDATE", "baseVersion": 3, "payload": { ... } }
  ]
}

→ 200
{
  "ok": true,
  "profile": { "email": "...", "name": "...", "role": "member", "active": true },
  "results": [
    { "opId": "<uuid>", "status": "APPLIED",  "version": 4, "seq": 1291 }
    // status ∈ APPLIED | DUPLICATE | CONFLICT | REJECTED
  ],
  "changes": [ /* rows with seq > lastSeq, capped */ ],
  "newSeq":  1291,
  "more":    false        // true ⇒ client should call again immediately
}
```

The write path, entirely inside the script lock:

```text
lock = LockService.getScriptLock()
lock.waitLock(30_000)                  // fail the request rather than racing
try:
    seq = readCounter("_Meta!B1")
    for op in operations:
        if op.opId in AppliedOps sheet:
            → DUPLICATE (return the stored result; do NOT re-apply)
        row = index[op.entityId]
        if row exists and row.version != op.baseVersion:
            → CONFLICT (return the server's current row; client reconciles)
        write row with version = row.version + 1, serverSeq = ++seq
        append op.opId to AppliedOps
    writeCounter("_Meta!B1", seq)
finally:
    lock.releaseLock()
```

Three things make this correct rather than merely plausible:

- **`waitLock` bounds the wait and throws.** On timeout the gateway returns a
  retryable error and the client's outbox tries again later. It must never
  proceed without the lock.
- **`AppliedOps` stores the *result*, not just the id.** A retry after a
  half-received response returns the original outcome instead of a false
  `CONFLICT`.
- **`serverSeq` is allocated inside the lock**, so it is genuinely monotonic and
  usable as the download cursor.

`AppliedOps` needs the same purge policy as tombstones (§3.4) — prune entries
older than the oldest device high-water mark.

### 5b.1a Session tokens — a constraint found while building Phase 5

Writing the WorkManager side exposed a hole in the design as reviewed: **a
background worker has no Activity, and Credential Manager cannot reliably mint a
Google ID token without UI.** ID tokens also last about an hour. So a worker
waking overnight would have no valid credential, and a record created offline
could sit unsynced until the user next opened the app — the exact failure §20
forbids, arrived at from a direction the review missed.

Fix, now implemented in `Session.gs`: the app calls `action: "session"` **once,
while it has UI**, exchanging a verified Google ID token for a long-lived opaque
token it can use headlessly. Sliding 90-day expiry, so an actively syncing device
never signs itself out. Two useful side effects:

- Background syncs spend **no `UrlFetch`** at all, which is the scarcest quota.
- Only the SHA-256 of each token is stored, so accidentally sharing the
  spreadsheet does not hand over live sessions.

`SESSION_EXPIRED` is deliberately a distinct error code from `UNAUTHORISED`: the
client must silently re-exchange an ID token and retry, not show the user an error.

**This creates a Phase 5 obligation.** The app now holds a long-lived bearer
token, which is precisely what §16 says must not sit in plain text. It has to be
encrypted with an Android Keystore key — note that `androidx.security:security-crypto`
is deprecated (§3.7), so this needs a small hand-rolled Keystore-backed wrapper
rather than `EncryptedSharedPreferences`.

### 5b.2 Authentication in the script

```text
UrlFetchApp.fetch("https://oauth2.googleapis.com/tokeninfo?id_token=" + token)
  → verify aud == <our OAuth web client id>
    verify iss ∈ { accounts.google.com, https://accounts.google.com }
    verify exp is in the future
    verify email_verified == true
  → look up email in Users sheet, require active == TRUE
```

Cache the verification in `CacheService.getScriptCache()` keyed by a SHA-256 of
the token, for `min(remaining exp, 300s)`. Without this a sync burst spends one
of the 20,000 daily `UrlFetch` calls per request; with it, one per token.

Deployment must be **"Execute as: me"**, **"Who has access: Anyone"** — the
Android app cannot easily make Google-authenticated Apps Script calls, so access
control rests entirely on the ID-token check above. Two consequences worth being
blunt about:

- **The deployment URL is not a secret** and must not be treated as one. It goes
  in `local.properties` for configurability, not for secrecy.
- **Anyone who learns the URL can burn your daily quota**, because Apps Script
  does not expose the caller's IP and so cannot rate-limit per client. Reject
  malformed requests *before* the `UrlFetch` verification so an unauthenticated
  flood costs nothing but script runtime. At three users the 20 k/day ceiling
  makes this an annoyance rather than a threat, but it is a real property of the
  design, not an oversight.

Redeployment gotcha: creating a *new* deployment changes the URL. Always update
the existing deployment so the installed apps keep working.

### 5b.3 Sheet schema, extending §11

Every entity sheet gains three server-owned columns beyond the §11 list:

| Column | Owner | Purpose |
| --- | --- | --- |
| `version` | server | Optimistic concurrency (§3.2) |
| `serverSeq` | server | Monotonic download cursor |
| `serverUpdatedAt` | server | Authoritative timestamp; client `updatedAt` is display-only |

Plus two bookkeeping sheets:

```text
_Meta        seqCounter, schemaVersion, purgeHighWaterMark
AppliedOps   opId, deviceId, entityType, entityId, appliedAt, resultJson
Sessions     tokenHash, userId, email, deviceId, createdAt, expiresAt, lastUsedAt
```

### 5b.4 Receipts

A separate `uploadReceipt` action, one image per request:

```text
{ "idToken": ..., "action": "uploadReceipt",
  "receiptId": "<uuid>", "expenseId": "<uuid>",
  "mimeType": "image/jpeg", "dataBase64": "..." }
→ { "ok": true, "driveFileId": "..." }
```

Client-side compression (§3.10) is what makes this viable: ~1024 px long edge at
JPEG q80 lands under 300 KB, and base64's 33% inflation keeps it well clear of
Apps Script's payload limits. `receiptId` is the idempotency key, so a retry
after a lost response returns the existing Drive file id instead of creating a
second copy — check for an existing file named `<receiptId>.jpg` in the target
folder before writing.

Drive folders follow §12 (`Receipts/YYYY/MM/`), created by the script on demand.
Because the script owns every file, all users can view every receipt — which is
the behaviour the requirements want, and which client-side `drive.file` uploads
could *not* have delivered, since that scope only ever sees files the uploading
install created.

---

## 6. Proposed project structure

Deliberately an evolution of what is already committed rather than the
from-scratch tree in §23 — the existing `data/`, `ui/`, `util/` packages already
match, so nothing needs to move.

```text
app/src/main/java/com/sankranthi/ledger/
├── MainActivity.kt
├── SankranthiApplication.kt          NEW  Application, WorkManager config
│
├── data/
│   ├── AppConfig.kt                       exists
│   ├── ServiceLocator.kt                  exists — extend with Room/Sync
│   ├── GoogleSignInClient.kt              exists
│   │
│   ├── local/                        NEW
│   │   ├── AppDatabase.kt                 Room database + migrations
│   │   ├── entity/                        TransactionEntity, ReceiptEntity,
│   │   │                                  ProfileEntity, PendingOperationEntity
│   │   ├── dao/                           one DAO per entity, Flow-returning
│   │   └── converter/                     type converters (enums, instants)
│   │
│   ├── remote/                       NEW
│   │   ├── RemoteDataSource.kt            THE SEAM — interface only
│   │   ├── dto/                           wire shapes, kept separate from entities
│   │   ├── supabase/SupabaseDataSource.kt
│   │   └── sheets/SheetsGatewayDataSource.kt
│   │
│   ├── model/                             exists — domain models
│   └── repository/                        Room-backed, outbox-enqueuing
│
├── sync/                             NEW
│   ├── SyncManager.kt                     upload / download / retry orchestration
│   ├── SyncWorker.kt                      CoroutineWorker
│   ├── SyncScheduler.kt                   periodic + expedited enqueue
│   ├── ConflictResolver.kt                version-based, swappable
│   └── SyncState.kt                       Synced / Syncing / Pending(n) / Failed / Offline
│
├── auth/                             NEW
│   ├── AuthManager.kt                     session + allowlist status
│   └── AppLockManager.kt                  BiometricPrompt + device credential
│
├── media/                            NEW
│   ├── ImageCompressor.kt                 downscale + EXIF orientation
│   └── LocalReceiptStore.kt               app-private file storage
│
├── ui/
│   ├── nav/                               exists
│   ├── auth/, dashboard/, admin/          exists
│   ├── transactions/                      list, add, detail
│   ├── receipts/                          picker, viewer
│   ├── settings/                          NEW
│   ├── sync/                         NEW  the sync indicator
│   └── common/, theme/                    exists
│
└── util/                                  exists — Money, Dates
```

```text
apps-script/          NEW — only if the Sheets gateway is chosen
├── Code.gs                sheet upsert, LockService, serverSeq
├── Auth.gs                ID-token verification + allowlist
├── Drive.gs               receipt writes
└── README.md              deployment steps

supabase/migrations/  exists — schema + RLS
docs/                 this review
```

---

## 7. Dependencies

Versions verified against Maven Central / Google's Maven on 2026-08-29. Existing
entries are already in [gradle/libs.versions.toml](../gradle/libs.versions.toml).

### New — local database

| Artifact | Version | Why |
| --- | --- | --- |
| `androidx.room:room-runtime` | 2.8.4 | Local store |
| `androidx.room:room-ktx` | 2.8.4 | Coroutines + `Flow` DAOs |
| `androidx.room:room-compiler` (ksp) | 2.8.4 | Code generation |
| `androidx.room:room-testing` | 2.8.4 | Migration tests |
| `com.google.devtools.ksp` (plugin) | **2.3.11** | Must be ≥ 2.3.1 for AGP 9 built-in Kotlin; take the newest for the R-class fix |

### New — background sync

| Artifact | Version | Why |
| --- | --- | --- |
| `androidx.work:work-runtime-ktx` | 2.11.2 | `CoroutineWorker`, constraints, backoff |
| `androidx.datastore:datastore-preferences` | 1.2.1 | `lastSyncedSeq`, sync prefs |

### New — receipts

| Artifact | Version | Why |
| --- | --- | --- |
| `io.coil-kt.coil3:coil-compose` | 3.6.0 | Thumbnails and the receipt viewer |
| `androidx.exifinterface:exifinterface` | 1.4.2 | Orientation before compression |
| `androidx.activity:activity-compose` | 1.13.0 (have) | Photo Picker / `TakePicture` contracts |

### New — app lock

| Artifact | Version | Why |
| --- | --- | --- |
| `androidx.biometric:biometric` | 1.1.0 | `BiometricPrompt` + device credential |

### Backend — Sheets gateway (chosen)

| Artifact | Version | Note |
| --- | --- | --- |
| `io.ktor:ktor-client-okhttp` | 3.5.2 | Already present. The gateway is plain HTTPS + JSON. |
| `io.ktor:ktor-client-content-negotiation` | 3.5.2 | **Add** — JSON (de)serialisation for the gateway calls |
| `io.ktor:ktor-serialization-kotlinx-json` | 3.5.2 | **Add** — pairs with the above |
| — | — | **No Google API client library needed**, and none should be added. The app never calls Sheets or Drive directly. That is the entire point of the gateway. |

Supabase artifacts (`bom` 3.8.0, `auth-kt`, `postgrest-kt`) stay in the catalog
but move to the unwired `SupabaseDataSource` per §2.2. Do **not** add
`storage-kt` — receipts go through the gateway to Drive.

New Gradle config needed: `buildConfigField` for the gateway URL, read from
`local.properties` exactly as the Supabase keys already are, so the deployment
URL is never committed.

### Already present

Compose BOM 2026.08.00 · navigation-compose 2.10.0 · lifecycle 2.11.0 ·
credentials 1.6.0 · googleid 1.2.0 · kotlinx-serialization-json 1.11.0 ·
kotlinx-coroutines 1.11.0 · desugar_jdk_libs 2.1.5

### Deliberately excluded

- **Hilt / Dagger** — see §3.6. `ServiceLocator` + a custom `WorkerFactory`.
- **`androidx.security:security-crypto`** — deprecated, and §3.7 argues it is
  unnecessary here.
- **Retrofit** — Ktor is already in the project; two HTTP stacks is waste.
- **Google API Java client (`google-api-services-sheets`)** — heavyweight,
  drags in bulky transitive dependencies, and the gateway design removes the need.

---

## 8. What can realistically stay free

Genuinely free at three users, indefinitely:

- Kotlin, Compose, Gradle, Android SDK, Room, WorkManager, VS Code — all free, no ceiling.
- Google Sheets + Drive on a personal account — free within your existing 15 GB.
- Apps Script — free; the consumer quotas in §1 are orders of magnitude above three users.
- Supabase free tier — free, subject to the pausing caveat.
- Sideloaded APK distribution — free.

Costs that appear later, so you can decide with open eyes:

- **Google Play Console: $25 one-time**, only if you ever publish. §29 correctly defers this.
- **Supabase Pro: ~$25/month**, only if you exceed 500 MB of data or want to stop
  worrying about pausing. At a few thousand transactions a year you will not
  approach this.
- Google Cloud OAuth verification is free but costs *time*, and the gateway
  design is what lets you avoid it.

---

## 9. Approved architecture and proposed next step

Backend decision is settled (§2): **Sheets as the writable source of truth behind
an Apps Script gateway.** Awaiting approval before writing code.

### Revised phase plan

| Phase | Work | Why here |
| --- | --- | --- |
| **0a** | Spike: Room entity + DAO compiling under AGP 9 + KSP 2.3.11 | **Done — passes.** KSP 2.3.11 generates all DAOs and the schema under built-in Kotlin. No `@Parcelize` on entities, per §3.5. |
| **0b** | Spike: Apps Script Web App with `LockService` + ID-token verification, one authenticated round trip from the app | Proves the whole backend premise before anything is built on it. Reuses the committed sign-in flow. |
| **1** | — | **Already done.** Compose app, navigation, theme, installable APK. |
| **2** | Room, entities, DAOs, outbox table, repositories; offline CRUD | **Done.** 22 instrumented tests green on device, including durability across a database close/reopen. |
| **3** | Re-point auth at the gateway; Users-sheet allowlist; app lock | Sign-in exists; only the destination changes |
| **4** | `SheetsGatewayDataSource` + gateway sync for **one** entity | **Gateway side done** — `apps-script/`, 34 Node tests green. Client side next. |
| **5** | WorkManager, retry/backoff, network detection, sync indicator, idempotency tests | Where the correctness risk in §5b.1 gets tested |
| **6** | Receipts: capture, compress, local store, gateway upload to Drive | Depends on 4 and 5 |
| **7** | Multi-device testing per §30 Phase 7 | Needs 2–3 real devices |

I'd start with **0a and 0b together** — both are cheap, both are pure risk
reduction, and if either fails the plan changes before any real code is written.

### Recommended tests to write alongside, not after

The §5b.1 upsert logic is where silent data loss would live, so it should be
pinned by tests from the start:

- Same `opId` submitted twice → one row, `DUPLICATE` on the retry
- Two devices submitting different edits to one row → one `APPLIED`, one `CONFLICT`
- Response lost mid-flight, client retries → original result returned, no second row
- Download with a locally-`PENDING` row → local edit survives (§3.3)
- Lock timeout → retryable error, nothing written
- Airplane mode → create → force-stop → restart phone → reconnect → row syncs (§30 Phase 5)

### What I need from you before Phase 0b

- A Google account to own the spreadsheet and Drive folder (a dedicated one is
  tidier than a personal account, and free)
- Confirmation you can create a Google Cloud project and an **OAuth web client
  ID** — needed for the `aud` check in §5b.2
- The 2–3 Gmail addresses for the initial `Users` allowlist
