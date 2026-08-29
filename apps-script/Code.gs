/**
 * Sankranthi sync gateway — HTTP entry point.
 *
 * Apps Script Web Apps expose only doGet/doPost, so this is one POST endpoint
 * dispatched on an `action` field. A single "sync" round trip uploads a bounded
 * batch *and* downloads everything newer than the client's cursor, which keeps
 * the number of cold starts down and stays inside the six-minute cap.
 */

function doPost(e) {
  try {
    var body = parseBody(e);
    var action = body.action || 'sync';

    if (!body.idToken && !body.sessionToken) {
      // Cheap rejection before anything expensive, so an unauthenticated flood
      // against this public URL costs as little quota as possible.
      throw badRequest('Missing idToken or sessionToken');
    }

    switch (action) {
      case 'session':
        // Creating a session is the one action that *requires* a Google ID
        // token; a session cannot mint another session.
        return json(withLock(function () {
          return handleCreateSession(verifyIdToken(body.idToken), body);
        }));

      case 'signOut':
        return json(withLock(function () { return handleRevokeSession(body); }));

      case 'sync':
        return json(withLock(function () {
          return handleSync(resolveIdentity(body), body);
        }));

      case 'uploadReceipt':
        return json(withLock(function () {
          return handleReceiptUpload(resolveIdentity(body), body);
        }));

      case 'ping':
        return json({ ok: true, email: resolveIdentity(body).email });

      default:
        throw badRequest('Unknown action: ' + action);
    }
  } catch (err) {
    return json(errorPayload(err));
  }
}

/** Deployment smoke test. Never returns data — auth happens on POST only. */
function doGet() {
  return json({ ok: true, service: 'sankranthi-gateway', version: 1 });
}

/**
 * Runs [fn] under the script lock.
 *
 * This lock is what makes upsert-by-UUID safe. Without it, two phones syncing at
 * once could both read "this id is absent" and both append, producing exactly
 * the duplicate the design forbids. `tryLock` is bounded and failure is reported
 * as retryable — proceeding without the lock would be worse than failing.
 */
function withLock(fn) {
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(LOCK_TIMEOUT_MS)) {
    throw retryable('Gateway busy, try again');
  }
  try {
    return fn();
  } finally {
    lock.releaseLock();
  }
}

/**
 * Identity for a data request: a session token if present, otherwise a Google ID
 * token. Sessions are preferred because background sync has no way to obtain a
 * fresh ID token (see Session.gs).
 */
function resolveIdentity(body) {
  if (body.sessionToken) return identityFromSession(body.sessionToken);
  return verifyIdToken(body.idToken);
}

function handleSync(identity, body) {
  var profile = resolveProfile(identity);

  // Signed in but not admitted: report status and nothing else. Returning rows
  // here would leak the books to an unapproved account.
  if (String(profile.status) !== 'approved') {
    return {
      ok: true,
      profile: profilePayload(profile),
      results: [],
      changes: { livestock: [], expenses: [], receipts: [] },
      newSeq: Number(body.lastSeq || 0),
      hasMore: false,
    };
  }

  var operations = body.operations || [];
  if (operations.length > MAX_OPS_PER_REQUEST) {
    throw badRequest('Too many operations; send at most ' + MAX_OPS_PER_REQUEST);
  }

  var results = [];
  for (var i = 0; i < operations.length; i++) {
    results.push(applyOperation(profile, operations[i], body.deviceId));
  }

  var lastSeq = Number(body.lastSeq || 0);
  var download = collectChanges(lastSeq);

  return {
    ok: true,
    profile: profilePayload(profile),
    results: results,
    changes: download.changes,
    newSeq: download.newSeq,
    hasMore: download.hasMore,
  };
}

/**
 * Applies one operation, idempotently.
 *
 * Order of checks matters:
 *   1. already applied?      -> return the original result, do not reapply
 *   2. permitted?            -> REJECTED, so a retry does not loop forever
 *   3. baseVersion current?  -> CONFLICT, with the server row attached
 *   4. write, bump version, allocate seq, log the op
 */
function applyOperation(profile, op, deviceId) {
  if (!op || !op.opId || !op.entityType || !op.entityId) {
    return { opId: op && op.opId, status: 'REJECTED', message: 'Malformed operation' };
  }

  var previous = findAppliedOp(op.opId);
  if (previous) {
    previous.status = 'DUPLICATE';
    return previous;
  }

  var sheetName = ENTITY_SHEETS[op.entityType];
  if (!sheetName) {
    return { opId: op.opId, status: 'REJECTED', message: 'Unknown entityType' };
  }

  var permission = requiredPermission(op.entityType, op.opType);
  if (permission && !hasPermission(profile, permission)) {
    return {
      opId: op.opId,
      status: 'REJECTED',
      message: 'You do not have the ' + permission + ' permission',
    };
  }

  var table = readTable(sheetName);
  var index = findRowIndexById(table, op.entityId);
  var existing = index >= 0 ? table.rows[index] : null;
  var baseVersion = Number(op.baseVersion || 0);

  if (existing && Number(existing.version || 0) !== baseVersion) {
    return {
      opId: op.opId,
      status: 'CONFLICT',
      version: Number(existing.version || 0),
      seq: Number(existing.serverSeq || 0),
      server: rowPayload(sheetName, existing),
      message: 'This entry changed on the server since you edited it',
    };
  }

  var incoming = op.row || {};
  var row = existing ? shallowCopy(existing) : {};

  // Copy only the fields the client owns. `createdBy`, `version`, `serverSeq`
  // and `serverUpdatedAt` are assigned below, never taken from the request —
  // otherwise a member could attribute an entry to somebody else.
  var clientFields = clientOwnedFields(sheetName);
  for (var f = 0; f < clientFields.length; f++) {
    var key = clientFields[f];
    if (incoming[key] !== undefined) row[key] = incoming[key];
  }

  row.id = op.entityId;
  if (!existing) {
    row.createdBy = profile.email;
    row.createdByName = profile.name;
    row.createdAt = incoming.createdAt || nowIso();
  }
  row.updatedAt = nowIso();
  if (op.opType === 'DELETE') row.deleted = true;

  row.version = Number(existing ? Number(existing.version || 0) + 1 : 1);
  row.serverSeq = nextSeq();
  row.serverUpdatedAt = nowIso();

  if (existing) {
    writeRow(sheetName, index, row);
  } else {
    appendRow(sheetName, row);
  }

  var result = {
    opId: op.opId,
    status: 'APPLIED',
    version: row.version,
    seq: row.serverSeq,
  };
  recordAppliedOp(
    { opId: op.opId, deviceId: deviceId, entityType: op.entityType, entityId: op.entityId },
    result
  );
  return result;
}

/**
 * Everything with `serverSeq > sinceSeq`, capped.
 *
 * `newSeq` is the highest sequence actually returned, not the global counter —
 * advancing the client's cursor past rows it was not sent would silently skip
 * them forever. When the cap truncates, `hasMore` tells the client to come back.
 */
function collectChanges(sinceSeq) {
  var buckets = [
    { key: 'livestock', sheet: SHEET_LIVESTOCK },
    { key: 'expenses', sheet: SHEET_EXPENSES },
    { key: 'receipts', sheet: SHEET_RECEIPTS },
  ];

  var pending = [];
  for (var b = 0; b < buckets.length; b++) {
    var table = readTable(buckets[b].sheet);
    for (var i = 0; i < table.rows.length; i++) {
      var seq = Number(table.rows[i].serverSeq || 0);
      if (seq > sinceSeq) {
        pending.push({ key: buckets[b].key, sheet: buckets[b].sheet, seq: seq, row: table.rows[i] });
      }
    }
  }

  pending.sort(function (a, b2) { return a.seq - b2.seq; });

  var hasMore = pending.length > MAX_CHANGES_PER_RESPONSE;
  var slice = pending.slice(0, MAX_CHANGES_PER_RESPONSE);

  var changes = { livestock: [], expenses: [], receipts: [] };
  var newSeq = sinceSeq;
  for (var s = 0; s < slice.length; s++) {
    changes[slice[s].key].push(rowPayload(slice[s].sheet, slice[s].row));
    if (slice[s].seq > newSeq) newSeq = slice[s].seq;
  }

  return { changes: changes, newSeq: newSeq, hasMore: hasMore };
}

/** Fields a client is allowed to set, i.e. the schema minus server-owned columns. */
function clientOwnedFields(sheetName) {
  var serverOwned = [
    'id', 'createdBy', 'createdByName', 'createdAt',
    'version', 'serverSeq', 'serverUpdatedAt',
  ];
  return SCHEMA[sheetName].filter(function (h) {
    return serverOwned.indexOf(h) < 0;
  });
}

function rowPayload(sheetName, row) {
  var payload = {};
  var headers = SCHEMA[sheetName];
  for (var i = 0; i < headers.length; i++) {
    var key = headers[i];
    var value = row[key];
    if (key === 'deleted') {
      payload[key] = value === true || value === 'true' || value === 'TRUE';
    } else if (key === 'amountMinor' || key === 'headCount' || key === 'sizeBytes' ||
               key === 'version' || key === 'serverSeq') {
      payload[key] = Number(value || 0);
    } else {
      payload[key] = value === undefined || value === null ? '' : String(value);
    }
  }
  return payload;
}

function profilePayload(profile) {
  return {
    email: String(profile.email),
    displayName: String(profile.name || ''),
    role: String(profile.role || 'member'),
    status: String(profile.status || 'pending'),
    permissions: String(profile.permissions || '')
      .split(',')
      .map(function (p) { return p.trim(); })
      .filter(function (p) { return p.length > 0; }),
    active: String(profile.status) === 'approved',
  };
}

function shallowCopy(source) {
  var copy = {};
  for (var key in source) {
    if (Object.prototype.hasOwnProperty.call(source, key)) copy[key] = source[key];
  }
  return copy;
}

function parseBody(e) {
  if (!e || !e.postData || !e.postData.contents) {
    throw badRequest('Empty request body');
  }
  try {
    return JSON.parse(e.postData.contents);
  } catch (err) {
    throw badRequest('Body is not valid JSON');
  }
}

function json(payload) {
  return ContentService
    .createTextOutput(JSON.stringify(payload))
    .setMimeType(ContentService.MimeType.JSON);
}

/**
 * Errors carry `retryable` so the client can tell "come back later" from "this
 * will never work". Blind retries on a permanent failure would burn the daily
 * quota; giving up on a transient one would strand a record.
 */
function badRequest(message) {
  var e = new Error(message);
  e.code = 'BAD_REQUEST';
  e.retryable = false;
  return e;
}

function unauthorised(message) {
  var e = new Error(message);
  e.code = 'UNAUTHORISED';
  e.retryable = false;
  return e;
}

function retryable(message) {
  var e = new Error(message);
  e.code = 'BUSY';
  e.retryable = true;
  return e;
}

function errorPayload(err) {
  var code = err && err.code ? err.code : 'INTERNAL';
  // Unknown failures are treated as retryable: a genuine transient Sheets error
  // must not cause the client to discard a pending record.
  var isRetryable = err && err.retryable !== undefined ? err.retryable : true;
  return {
    ok: false,
    error: { code: code, message: String(err && err.message ? err.message : err), retryable: isRetryable },
  };
}
