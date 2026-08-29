# What you need to provide — Google setup for Phases 3 & 4

Everything Claude cannot do for you, in the order it needs doing. Values already
known are filled in.

**Send me back the three items marked ➜ SEND ME.** Nothing else is needed.

---

## 0. Package name — already settled

Renamed on 2026-08-29 from `com.example.sankranthi` to **`com.sankranthi.ledger`**,
done *before* any OAuth registration precisely so you never have to redo it.
Google Play rejects `com.example.*`, so this keeps the Play Store open as an
option.

Register these exact strings in step 2d:

| Build | Package name |
| --- | --- |
| Debug (what you'll test with) | `com.sankranthi.ledger.debug` |
| Release (later, for distribution) | `com.sankranthi.ledger` |

If you'd prefer a different name, say so **before** doing step 2 — it is free to
change now and a nuisance afterwards.

---

## 1. Google account to own everything

One account owns the spreadsheet, the Drive folder and the Apps Script. Everyone
else's access is granted by the script, not by Drive sharing.

A **dedicated account** is tidier than a personal one — the script runs as this
account, and its Apps Script quotas (90 min/day runtime, 20 000 UrlFetch/day) are
per account. Either works; free either way.

➜ **SEND ME:** nothing. Just know which account you used.

---

## 2. Google Cloud project + OAuth clients

At <https://console.cloud.google.com>, signed in as the account from step 1.

### 2a. Create the project

**Select a project → New Project.** Name it anything (`Sankranthi`).

### 2b. Configure the consent screen

**APIs & Services → OAuth consent screen** (newer consoles: **Google Auth
Platform → Branding / Audience**).

- User type: **External**
- App name, user support email, developer contact email
- **Scopes: add only these three.** Do not add anything Sheets- or Drive-related.

  ```
  openid
  .../auth/userinfo.email
  .../auth/userinfo.profile
  ```

- **Then click "Publish app" / set Publishing status to "In production".**

**This step is the whole reason the architecture looks the way it does.** Those
three scopes are *non-sensitive*, so publishing needs **no Google verification
and no review**. If you instead leave the app in **Testing**, Google expires
refresh tokens after **7 days** and every partner gets logged out weekly —
breaking the "stays logged in like WhatsApp" requirement. Publishing with only
these scopes is instant and free.

You will see an "unverified app" warning only if sensitive scopes are added
later. With these three, there is nothing to verify.

### 2c. Create the Web OAuth client

**APIs & Services → Credentials → Create Credentials → OAuth client ID**

- Application type: **Web application**
- Name: `Sankranthi gateway`
- Authorised redirect URIs: **leave empty.** The app never does a browser OAuth
  redirect; this client exists only so the ID token has an `aud` value the
  gateway can verify.

➜ **SEND ME (1):** the **Client ID** — looks like
`123456789012-abcdef....apps.googleusercontent.com`

This becomes `google.webClientId`. It is **not** a secret; it ships in the APK by
design. Do not send me the client *secret* — it is not needed and must not be in
the app.

### 2d. Create the Android OAuth client

Same screen → **Create Credentials → OAuth client ID → Android**.

This one has no ID to copy; it just authorises this app to use the web client.

- Package name: **`com.sankranthi.ledger.debug`**
  (the debug build adds a `.debug` suffix so it can sit alongside a release build)
- SHA-1 certificate fingerprint:

  ```
  9D:4F:6F:73:D4:40:1A:08:52:82:DA:E6:B6:EB:B4:65:E4:85:F9:76
  ```

  That is your machine's debug keystore, read from
  `~/.android/debug.keystore`. It is only valid on this computer — if another
  developer builds, they need their own entry.

Later, for the release APK you actually hand out, add a **second** Android client
with package `com.sankranthi.ledger` and the SHA-1 of your release keystore.
I'll walk you through creating that keystore when we get to distribution; back it
up, because losing it means never being able to update the installed app.

➜ **SEND ME:** nothing. Just confirm it saved.

---

## 3. The spreadsheet

Create a new Google Sheet, owned by the step-1 account. Name it anything
(`Sankranthi ApplicationData`). Leave it empty — the gateway script creates and
formats the tabs (`Users`, `Livestock`, `Expenses`, `Receipts`, `_Meta`,
`AppliedOps`) on first run.

Its ID is the long string in the URL:

```
https://docs.google.com/spreadsheets/d/  <-- THIS PART -->  /edit
```

➜ **SEND ME (2):** the spreadsheet ID.

**Do not share the sheet with the other partners.** They reach it only through
the gateway, which is what lets the script enforce the allowlist and the
permission rules. Anyone with direct edit access bypasses all of it.

---

## 4. Drive folder for receipts

Nothing to do. The script creates `SankranthiApp/Receipts/YYYY/MM/` under the
owning account's Drive on first upload, and it owns every file — which is exactly
why all partners can view every receipt.

➜ **SEND ME:** nothing.

---

## 5. The Apps Script gateway — after I write it

I write `apps-script/*.gs` first. Then you:

1. Go to <https://script.google.com> as the step-1 account → **New project**
2. Paste in the files I give you
3. **Project Settings → Script Properties**, add:
   - `SPREADSHEET_ID` = the ID from step 3
   - `WEB_CLIENT_ID` = the client ID from step 2c
4. **Deploy → New deployment → Web app**
   - Execute as: **Me**
   - Who has access: **Anyone**
5. Authorise it when prompted (it needs Sheets + Drive access — as *the script*,
   not as the app; this is the point)
6. Copy the deployment URL, ending in `/exec`

➜ **SEND ME (3):** the `/exec` URL.

Two things to know about that URL:

- **It is not a secret.** Security comes from the ID-token check inside the
  script, not from the URL being obscure. It goes in `local.properties` for
  configurability only.
- **Never create a *new* deployment to publish a change** — use **Deploy → Manage
  deployments → edit the existing one**. A new deployment gets a new URL and the
  installed apps stop working.

---

## 6. Who gets in

Nothing to send. The first account to sign in becomes the approved admin
automatically — otherwise nobody could approve anyone. Everyone after that lands
in your **Pending requests** tab, and you approve them and tick their edit rights
in the app.

So: **you sign in first**, then hand the APK to the others.

➜ **SEND ME:** nothing — unless you'd rather a specific address be the admin
regardless of who signs in first. Tell me and I'll seed it.

---

## Summary — the three things

| # | Item | Where from |
| --- | --- | --- |
| 1 | Web OAuth **Client ID** | Step 2c |
| 2 | **Spreadsheet ID** | Step 3 |
| 3 | Gateway **`/exec` URL** | Step 5, after I write the script |

Items 1 and 2 you can get now. Item 3 comes after I hand you the script.

They land in `local.properties`, which is gitignored, so none of it is committed:

```properties
sdk.dir=C\:/Users/NNAREDLA/AppData/Local/Android/Sdk
google.webClientId=<item 1>
gateway.url=<item 3>
```

---

## What I'm doing meanwhile

None of the following needs anything from you:

- The Apps Script gateway itself — `LockService` upsert, ID-token verification,
  `serverSeq`, receipt writes
- `SheetsGatewayDataSource`, the `RemoteDataSource` implementation
- **Phase 5**: `SyncManager`, `SyncWorker`, WorkManager scheduling, retry and
  backoff, connectivity detection
- The idempotency and conflict tests, run against a fake gateway — including
  duplicate-`opId` submission, mid-flight edits, lost responses and lock timeouts

So the work continues while you do the Google side; the credentials are only
needed to point it at the real thing and test end to end on a device.
