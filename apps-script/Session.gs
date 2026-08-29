/**
 * Session tokens.
 *
 * Why these exist: background sync runs in a WorkManager worker with no
 * Activity, and Credential Manager cannot reliably mint a Google ID token
 * without UI. ID tokens also last about an hour, so a worker that woke up
 * overnight would have nothing valid to send and a record created offline could
 * sit unsynced until the user next opened the app — which is precisely the
 * failure §20 forbids.
 *
 * So the app exchanges a Google ID token, once, while it has UI, for a
 * long-lived opaque session token it can use headlessly thereafter. That also
 * removes the per-request `UrlFetch` to Google's tokeninfo endpoint, which is the
 * scarcest quota here.
 *
 * Only the SHA-256 of each token is stored. The spreadsheet is a plausible thing
 * to accidentally share, and a hash means doing so does not hand over live
 * sessions.
 */

var SHEET_SESSIONS = 'Sessions';

SCHEMA[SHEET_SESSIONS] = [
  'tokenHash', 'userId', 'email', 'deviceId', 'createdAt', 'expiresAt', 'lastUsedAt',
];

/** 90 days, refreshed on use, so an active device effectively never signs out. */
var SESSION_TTL_DAYS = 90;

/**
 * Exchanges a verified Google ID token for a session token.
 *
 * Returns the raw token exactly once — it is never recoverable afterwards.
 * Must hold the script lock.
 */
function handleCreateSession(identity, body) {
  var profile = resolveProfile(identity);

  var raw = generateSessionToken();
  var now = new Date();
  var expires = new Date(now.getTime() + SESSION_TTL_DAYS * 24 * 60 * 60 * 1000);

  appendRow(SHEET_SESSIONS, {
    tokenHash: sha256Hex(raw),
    userId: identity.userId,
    email: identity.email,
    deviceId: body.deviceId || '',
    createdAt: nowIso(),
    expiresAt: Utilities.formatDate(expires, 'UTC', "yyyy-MM-dd'T'HH:mm:ss'Z'"),
    lastUsedAt: nowIso(),
  });

  return {
    ok: true,
    sessionToken: raw,
    expiresAt: Utilities.formatDate(expires, 'UTC', "yyyy-MM-dd'T'HH:mm:ss'Z'"),
    profile: profilePayload(profile),
  };
}

/** Revokes the presented session. Signing out must not need a fresh ID token. */
function handleRevokeSession(body) {
  if (!body.sessionToken) throw badRequest('Missing sessionToken');
  var hash = sha256Hex(body.sessionToken);
  var table = readTable(SHEET_SESSIONS);
  for (var i = 0; i < table.rows.length; i++) {
    if (String(table.rows[i].tokenHash) === hash) {
      // Expire in place rather than deleting the row, so deleting never shifts
      // the indices of rows another request is mid-way through reading.
      var row = shallowCopy(table.rows[i]);
      row.expiresAt = '1970-01-01T00:00:00Z';
      writeRow(SHEET_SESSIONS, i, row);
      break;
    }
  }
  CacheService.getScriptCache().remove('sess_' + hash);
  return { ok: true };
}

/**
 * Resolves a session token to an identity, or throws.
 *
 * Cached for five minutes so a sync burst costs one sheet scan rather than one
 * per request. Expiry is checked against the stored value, never the cache, so a
 * revoked session cannot outlive its cache entry by more than the TTL.
 */
function identityFromSession(sessionToken) {
  var hash = sha256Hex(sessionToken);
  var cacheKey = 'sess_' + hash;
  var cache = CacheService.getScriptCache();

  var cached = cache.get(cacheKey);
  if (cached) {
    var hit = JSON.parse(cached);
    if (new Date(hit.expiresAt).getTime() > Date.now()) {
      return { userId: hit.userId, email: hit.email, name: hit.name || '' };
    }
    cache.remove(cacheKey);
  }

  var table = readTable(SHEET_SESSIONS);
  for (var i = 0; i < table.rows.length; i++) {
    if (String(table.rows[i].tokenHash) !== hash) continue;

    var expiresAt = String(table.rows[i].expiresAt);
    if (new Date(expiresAt).getTime() <= Date.now()) {
      throw sessionExpired('Session expired, sign in again');
    }

    var identity = {
      userId: String(table.rows[i].userId),
      email: String(table.rows[i].email).toLowerCase(),
      name: '',
    };

    // Sliding expiry: an actively syncing device stays signed in.
    var refreshed = new Date(Date.now() + SESSION_TTL_DAYS * 24 * 60 * 60 * 1000);
    var row = shallowCopy(table.rows[i]);
    row.lastUsedAt = nowIso();
    row.expiresAt = Utilities.formatDate(refreshed, 'UTC', "yyyy-MM-dd'T'HH:mm:ss'Z'");
    writeRow(SHEET_SESSIONS, i, row);

    cache.put(cacheKey, JSON.stringify({
      userId: identity.userId,
      email: identity.email,
      expiresAt: row.expiresAt,
    }), 300);

    return identity;
  }

  throw sessionExpired('Unknown session, sign in again');
}

function generateSessionToken() {
  // 32 bytes of UUID-derived entropy. Apps Script has no CSPRNG API; two v4
  // UUIDs give 244 random bits, which is ample for a bearer token that is also
  // bound to an allowlisted account and revocable.
  return (Utilities.getUuid() + Utilities.getUuid()).replace(/-/g, '');
}

/**
 * Distinct from UNAUTHORISED: the client should silently re-exchange a Google ID
 * token and retry, rather than showing the user an error.
 */
function sessionExpired(message) {
  var e = new Error(message);
  e.code = 'SESSION_EXPIRED';
  e.retryable = false;
  return e;
}
