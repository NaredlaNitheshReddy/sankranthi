/**
 * Identity and authorisation.
 *
 * The Web App is deployed "Execute as: me / Who has access: Anyone", because an
 * Android app cannot easily make Google-authenticated Apps Script calls. That
 * means the deployment URL is public and **every** security guarantee rests on
 * verifyIdToken below. Treat it accordingly.
 */

/**
 * Verifies a Google ID token and returns its claims.
 *
 * Checks, in order: signature and freshness (delegated to Google's tokeninfo
 * endpoint), that the token was minted for *our* client (`aud`), that Google
 * issued it (`iss`), and that the address is verified. Skipping the `aud` check
 * would be the classic hole — any Google ID token from any app would then work.
 *
 * Results are cached by token hash for the token's remaining life, capped at
 * five minutes. Each verification costs one UrlFetch call against a 20 000/day
 * quota, and a sync burst would otherwise spend one per request.
 */
function verifyIdToken(idToken) {
  if (!idToken || typeof idToken !== 'string') {
    throw badRequest('Missing idToken');
  }

  var expectedAud = cfg('WEB_CLIENT_ID', true);
  var cache = CacheService.getScriptCache();
  var cacheKey = 'tok_' + sha256Hex(idToken);

  var cached = cache.get(cacheKey);
  if (cached) {
    return JSON.parse(cached);
  }

  var response = UrlFetchApp.fetch(
    'https://oauth2.googleapis.com/tokeninfo?id_token=' + encodeURIComponent(idToken),
    { muteHttpExceptions: true }
  );

  if (response.getResponseCode() !== 200) {
    throw unauthorised('Token rejected by Google');
  }

  var claims = JSON.parse(response.getContentText());

  if (claims.aud !== expectedAud) {
    // Token is valid but was issued to a different application.
    throw unauthorised('Token audience mismatch');
  }
  if (claims.iss !== 'accounts.google.com' && claims.iss !== 'https://accounts.google.com') {
    throw unauthorised('Unexpected token issuer');
  }
  var expSeconds = parseInt(claims.exp, 10);
  var nowSeconds = Math.floor(Date.now() / 1000);
  if (!expSeconds || expSeconds <= nowSeconds) {
    throw unauthorised('Token expired');
  }
  if (claims.email_verified !== 'true' && claims.email_verified !== true) {
    throw unauthorised('Google account email is not verified');
  }
  if (!claims.email) {
    throw unauthorised('Token carries no email');
  }

  var identity = {
    userId: claims.sub,
    email: String(claims.email).toLowerCase(),
    name: claims.name || '',
  };

  var ttl = Math.min(expSeconds - nowSeconds, 300);
  if (ttl > 0) {
    cache.put(cacheKey, JSON.stringify(identity), ttl);
  }
  return identity;
}

/**
 * Resolves the caller's profile row, creating an access request on first sight.
 *
 * The very first account ever to sign in becomes an approved admin — without
 * that rule there would be nobody able to approve anyone and the whole thing
 * would deadlock. Set the ADMIN_EMAIL script property to pin that to a specific
 * address instead of "whoever got there first".
 *
 * Must be called with the script lock already held: it can write.
 */
function resolveProfile(identity) {
  var table = readTable(SHEET_USERS);
  var rowIndex = -1;
  for (var i = 0; i < table.rows.length; i++) {
    if (String(table.rows[i].userId) === identity.userId ||
        String(table.rows[i].email).toLowerCase() === identity.email) {
      rowIndex = i;
      break;
    }
  }

  if (rowIndex >= 0) {
    var existing = table.rows[rowIndex];
    // Keep the display name fresh, but never touch role or status here.
    if (identity.name && existing.name !== identity.name) {
      existing.name = identity.name;
      existing.updatedAt = nowIso();
      writeRow(SHEET_USERS, rowIndex, existing);
    }
    return existing;
  }

  var adminEmail = cfg('ADMIN_EMAIL', false);
  var isFirstAdmin = adminEmail
    ? identity.email === String(adminEmail).toLowerCase()
    : table.rows.length === 0;

  var created = {
    userId: identity.userId,
    email: identity.email,
    name: identity.name || identity.email.split('@')[0],
    role: isFirstAdmin ? 'admin' : 'member',
    status: isFirstAdmin ? 'approved' : 'pending',
    permissions: '',
    requestedAt: nowIso(),
    updatedAt: nowIso(),
    version: 1,
    serverSeq: nextSeq(),
    serverUpdatedAt: nowIso(),
  };
  appendRow(SHEET_USERS, created);
  return created;
}

/** Admins implicitly hold every permission; members hold what was granted. */
function hasPermission(profile, permission) {
  if (String(profile.status) !== 'approved') return false;
  if (String(profile.role) === 'admin') return true;
  var granted = String(profile.permissions || '')
    .split(',')
    .map(function (p) { return p.trim(); });
  return granted.indexOf(permission) >= 0;
}

/** Which permission a write to this entity requires. */
function requiredPermission(entityType, opType) {
  if (opType === 'DELETE') return 'delete_entries';
  if (entityType === 'livestock') return 'edit_livestock';
  if (entityType === 'expense') return 'edit_expenses';
  if (entityType === 'receipt') return 'edit_expenses';
  return null;
}

function sha256Hex(value) {
  var bytes = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, value);
  return bytes
    .map(function (b) {
      var v = (b < 0 ? b + 256 : b).toString(16);
      return v.length === 1 ? '0' + v : v;
    })
    .join('');
}
