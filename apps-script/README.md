# Sankranthi sync gateway (Google Apps Script)

The only thing that touches the spreadsheet. The Android app holds **no Sheets or
Drive scope at all** — it sends a Google ID token, this script verifies it and
then reads and writes as the script owner.

That indirection is not architectural taste. `.../auth/spreadsheets` is a
*sensitive* scope, which forces the OAuth consent screen to stay in Testing
status, where Google expires refresh tokens every 7 days. Routing through here
means the app needs only `openid email profile` — non-sensitive, publishable
without review, no weekly logout.

## Files

| File | Contents |
| --- | --- |
| `Config.gs` | Script properties, sheet schema, batch limits |
| `Auth.gs` | ID-token verification, allowlist, permission checks |
| `Store.gs` | All spreadsheet reads and writes, sequence counter |
| `Session.gs` | Long-lived session tokens, so background sync works headlessly |
| `Code.gs` | `doPost` entry point, the locked idempotent upsert, downloads |
| `Drive.gs` | Receipt files |
| `appsscript.json` | Manifest: scopes and Web App access |
| `test/` | Node tests — see below |

## Deploying

1. <https://script.google.com> → **New project**, signed in as the account that
   should own the spreadsheet and receipts.
2. Create one file per `.gs` above and paste the contents. Apps Script hides
   `appsscript.json` by default: **Project Settings → "Show appsscript.json"**,
   then replace it.
3. **Project Settings → Script Properties**, add:

   | Property | Value |
   | --- | --- |
   | `SPREADSHEET_ID` | from the spreadsheet URL |
   | `WEB_CLIENT_ID` | the OAuth **web** client id |
   | `ADMIN_EMAIL` | *optional* — pins the admin instead of "first to sign in" |

4. **Deploy → New deployment → Web app**
   - Execute as: **Me**
   - Who has access: **Anyone**
5. Authorise when prompted. It asks for Sheets and Drive access **as the script**
   — that is the whole point; the app never gets those scopes.
6. Copy the `/exec` URL into `local.properties` as `gateway.url`.

Check it responds:

```bash
curl -L "<your /exec URL>"
# {"ok":true,"service":"sankranthi-gateway","version":1}
```

`doGet` returns no data and requires no auth deliberately — it is a liveness
probe. Everything real goes through `doPost` with a token.

The spreadsheet needs no manual setup. The script creates and formats every tab
(`Users`, `Livestock`, `Expenses`, `Receipts`, `_Meta`, `AppliedOps`) on first
request.

### Publishing a change

**Deploy → Manage deployments → edit the existing deployment.** Creating a *new*
deployment issues a new URL and every installed app stops working.

## Tests

The upsert path hand-rolls the idempotency and optimistic concurrency a real
database would give you for free, so it is tested directly. `test/harness.mjs`
stubs the Apps Script runtime — Sheets, Drive, `LockService`, `UrlFetchApp`,
`CacheService` — and runs the real `.gs` sources under Node. No Google account,
deployment or network needed.

```bash
node --test apps-script/test/gateway.test.mjs
```

34 tests covering, among others:

- first account becomes admin; later accounts are pending **and receive no data**
- the same `opId` replayed returns the original result and creates no second row
- a stale `baseVersion` yields `CONFLICT` with the server row attached
- a client cannot spoof `createdBy`, `version` or `serverSeq`
- each permission is enforced separately, and deleting needs `delete_entries`
- a lock timeout is reported as **retryable** and writes nothing
- a token minted for another app, an unverified email, and an expired token are all refused
- token verification is cached, so a sync burst costs one `UrlFetch`
- receipt upload is idempotent on `receiptId`
- sequence numbers strictly increase
- an ID token exchanges for a session token; only its **hash** is stored
- a session authenticates sync with no ID token and spends no `UrlFetch`
- a revoked or unknown session is refused as `SESSION_EXPIRED`, not retried
- a session cannot mint another session, nor grant authority its account lacks

Run them after any change to a `.gs` file. If you add a column, add it to the
**end** of the relevant `SCHEMA` array — order is the contract with the app.

## Sessions, and why they exist

Background sync runs in a WorkManager worker with **no Activity**, and Credential
Manager cannot reliably mint a Google ID token without UI. ID tokens also last
about an hour, so a worker waking overnight would have nothing valid to send and
a record created offline could sit unsynced until the user next opened the app —
exactly the failure §20 forbids.

So the app calls `action: "session"` **once, while it has UI**, exchanging a
Google ID token for a long-lived opaque token it can use headlessly. Sliding
90-day expiry, so an actively syncing device never signs itself out.

Only the SHA-256 of each token is stored. The spreadsheet is a plausible thing to
share by accident, and a hash means doing so does not hand over live sessions.

The client must treat `SESSION_EXPIRED` as "silently re-exchange an ID token and
retry", not as an error to show the user.

## Things worth knowing

- **The `/exec` URL is not a secret.** Security is the ID-token check in
  `Auth.gs`, not obscurity. It lives in `local.properties` for configurability.
- **You cannot rate-limit by client.** Apps Script does not expose the caller's
  IP, so anyone with the URL can consume your daily quota. Malformed requests are
  rejected *before* the `UrlFetch` verification so an unauthenticated flood costs
  only script runtime. At three users the 20 000/day ceiling makes this an
  annoyance, not a threat — but it is a real property of the design.
- **Do not share the spreadsheet with the partners.** They reach it only through
  this gateway; direct edit access bypasses the allowlist and every permission
  check.
- **Consumer-account quotas:** 6 minutes per execution, 90 minutes/day total,
  20 000 `UrlFetch`/day. `MAX_OPS_PER_REQUEST` and `MAX_CHANGES_PER_RESPONSE` in
  `Config.gs` exist to keep a single request well inside the 6-minute cap; do not
  raise them without measuring.
- **`AppliedOps` grows forever** until a purge is added. It is the duplicate-
  prevention log, so it can only be pruned past the point where every device has
  synced — see §3.4 of the architecture review.
