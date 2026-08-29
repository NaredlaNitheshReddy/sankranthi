/**
 * Gateway behaviour tests. Run with:  node --test apps-script/test/
 *
 * These cover the scenarios called out in docs/ARCHITECTURE_REVIEW.md §9 as the
 * ones where silent data loss or duplication would live.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createGateway, SHEETS } from './harness.mjs';

/**
 * Verification is cached by token hash, which is correct — a given token always
 * carries the same claims. So each identity in these tests needs its own token
 * string, exactly as separate real users would have.
 */
let currentToken = 'token-default';

function asUser(gw, email, opts = {}) {
  gw.setClaims(claimsFor(email, opts));
  currentToken = 'token-' + email + '-' + (opts.variant || 'ok');
}

function asUserWithClaims(gw, variant, claims) {
  gw.setClaims(claims);
  currentToken = 'token-' + variant;
}

function claimsFor(email, { sub = null, aud = 'client-1.apps.googleusercontent.com' } = {}) {
  return {
    sub: sub ?? 'sub-' + email,
    email,
    email_verified: 'true',
    name: email.split('@')[0],
    aud,
    iss: 'https://accounts.google.com',
    exp: String(Math.floor(Date.now() / 1000) + 3600),
  };
}

function syncBody(operations = [], lastSeq = 0) {
  return { idToken: currentToken, action: 'sync', deviceId: 'device-A', lastSeq, operations };
}

function livestockOp(opId, entityId, overrides = {}) {
  return {
    opId,
    entityType: 'livestock',
    entityId,
    opType: 'UPSERT',
    baseVersion: 0,
    row: {
      kind: 'buy',
      animal: 'Goat',
      headCount: 12,
      amountMinor: 9600000,
      counterparty: 'Kurnool mandi',
      occurredOn: '2026-08-01',
      notes: '',
      deleted: false,
      updatedAt: '2026-08-01T00:00:00Z',
      ...overrides,
    },
  };
}

/** An admin plus an approved member holding only edit_livestock. */
function gatewayWithAdmin() {
  const gw = createGateway();
  asUser(gw, 'admin@example.com');
  gw.post(syncBody());
  return gw;
}

test('the first account to sign in becomes an approved admin', () => {
  const gw = createGateway();
  asUser(gw, 'admin@example.com');

  const res = gw.post(syncBody());

  assert.equal(res.ok, true);
  assert.equal(res.profile.role, 'admin');
  assert.equal(res.profile.status, 'approved');
  assert.equal(res.profile.active, true);
});

test('later accounts land in the pending queue and receive no data', () => {
  const gw = gatewayWithAdmin();
  gw.post(syncBody([livestockOp('op-1', 'entry-1')]));

  asUser(gw, 'newcomer@example.com');
  const res = gw.post(syncBody());

  assert.equal(res.profile.status, 'pending');
  assert.equal(res.profile.active, false);
  // Critically: the books are not disclosed to an unapproved account.
  assert.deepEqual(res.changes.livestock, []);
  assert.deepEqual(res.changes.expenses, []);
  assert.equal(res.results.length, 0);
});

test('ADMIN_EMAIL pins the admin instead of first-come', () => {
  const gw = createGateway({
    properties: {
      SPREADSHEET_ID: 'sheet-1',
      WEB_CLIENT_ID: 'client-1.apps.googleusercontent.com',
      ADMIN_EMAIL: 'boss@example.com',
    },
  });

  asUser(gw, 'early@example.com');
  assert.equal(gw.post(syncBody()).profile.status, 'pending');

  asUser(gw, 'boss@example.com');
  assert.equal(gw.post(syncBody()).profile.role, 'admin');
});

test('an upsert is applied, versioned from 1 and given a sequence', () => {
  const gw = gatewayWithAdmin();

  const res = gw.post(syncBody([livestockOp('op-1', 'entry-1')]));

  assert.equal(res.results.length, 1);
  assert.equal(res.results[0].status, 'APPLIED');
  assert.equal(res.results[0].version, 1);
  assert.ok(res.results[0].seq > 0);

  const stored = gw.rows(SHEETS.livestock);
  assert.equal(stored.length, 1);
  assert.equal(stored[0].id, 'entry-1');
  assert.equal(stored[0].animal, 'Goat');
  assert.equal(stored[0].amountMinor, 9600000);
});

test('replaying the same opId returns the original result and creates no second row', () => {
  const gw = gatewayWithAdmin();
  const first = gw.post(syncBody([livestockOp('op-1', 'entry-1')]));

  // Simulates the response being lost in flight and the client retrying.
  const retry = gw.post(syncBody([livestockOp('op-1', 'entry-1')]));

  assert.equal(retry.results[0].status, 'DUPLICATE');
  assert.equal(retry.results[0].version, first.results[0].version);
  assert.equal(retry.results[0].seq, first.results[0].seq);
  assert.equal(gw.rows(SHEETS.livestock).length, 1, 'must not duplicate the row');
});

test('a stale baseVersion is reported as a conflict with the server row attached', () => {
  const gw = gatewayWithAdmin();
  gw.post(syncBody([livestockOp('op-1', 'entry-1')]));           // version -> 1
  gw.post(syncBody([{ ...livestockOp('op-2', 'entry-1', { headCount: 20 }), baseVersion: 1 }]));

  // Third edit still claims baseVersion 0, i.e. it never saw version 1 or 2.
  const stale = { ...livestockOp('op-3', 'entry-1'), baseVersion: 0 };
  const res = gw.post(syncBody([stale]));

  assert.equal(res.results[0].status, 'CONFLICT');
  assert.ok(res.results[0].version >= 1);
  assert.equal(res.results[0].server.id, 'entry-1');
});

test('a correct baseVersion updates in place and bumps the version', () => {
  const gw = gatewayWithAdmin();
  gw.post(syncBody([livestockOp('op-1', 'entry-1')]));

  const update = { ...livestockOp('op-2', 'entry-1', { headCount: 20 }), baseVersion: 1 };
  const res = gw.post(syncBody([update]));

  assert.equal(res.results[0].status, 'APPLIED');
  assert.equal(res.results[0].version, 2);

  const stored = gw.rows(SHEETS.livestock);
  assert.equal(stored.length, 1, 'update must not append a second row');
  assert.equal(stored[0].headCount, 20);
});

test('the client cannot spoof attribution or server-owned columns', () => {
  const gw = gatewayWithAdmin();

  const spoofed = livestockOp('op-1', 'entry-1', {
    createdBy: 'someone.else@example.com',
    createdByName: 'Someone Else',
    version: 99,
    serverSeq: 12345,
  });
  gw.post(syncBody([spoofed]));

  const stored = gw.rows(SHEETS.livestock)[0];
  assert.equal(stored.createdBy, 'admin@example.com');
  assert.equal(stored.createdByName, 'admin');
  assert.equal(stored.version, 1, 'version is server-assigned');
  assert.notEqual(stored.serverSeq, 12345);
});

test('a member without the permission is rejected, not retried forever', () => {
  const gw = gatewayWithAdmin();

  // Approve a member with no permissions at all.
  asUser(gw, 'member@example.com');
  gw.post(syncBody());
  const users = gw.rows(SHEETS.users);
  const index = users.findIndex((u) => u.email === 'member@example.com');
  const sheet = gw.spreadsheet.getSheetByName(SHEETS.users);
  const headers = gw.sandbox.SCHEMA[SHEETS.users];
  const updated = { ...users[index], status: 'approved', permissions: '' };
  sheet.getRange(index + 2, 1, 1, headers.length)
    .setValues([headers.map((h) => updated[h] ?? '')]);

  const res = gw.post(syncBody([livestockOp('op-1', 'entry-1')]));

  assert.equal(res.results[0].status, 'REJECTED');
  assert.match(res.results[0].message, /edit_livestock/);
  assert.equal(gw.rows(SHEETS.livestock).length, 0);
});

test('a granted permission lets the member write', () => {
  const gw = gatewayWithAdmin();
  asUser(gw, 'member@example.com');
  gw.post(syncBody());

  const users = gw.rows(SHEETS.users);
  const index = users.findIndex((u) => u.email === 'member@example.com');
  const sheet = gw.spreadsheet.getSheetByName(SHEETS.users);
  const headers = gw.sandbox.SCHEMA[SHEETS.users];
  const updated = { ...users[index], status: 'approved', permissions: 'edit_livestock' };
  sheet.getRange(index + 2, 1, 1, headers.length)
    .setValues([headers.map((h) => updated[h] ?? '')]);

  const res = gw.post(syncBody([livestockOp('op-1', 'entry-1')]));

  assert.equal(res.results[0].status, 'APPLIED');
  assert.equal(gw.rows(SHEETS.livestock)[0].createdBy, 'member@example.com');
});

test('a delete is a soft delete so it can propagate', () => {
  const gw = gatewayWithAdmin();
  gw.post(syncBody([livestockOp('op-1', 'entry-1')]));

  const del = {
    ...livestockOp('op-2', 'entry-1', { deleted: true }),
    opType: 'DELETE',
    baseVersion: 1,
  };
  const res = gw.post(syncBody([del]));

  assert.equal(res.results[0].status, 'APPLIED');
  const stored = gw.rows(SHEETS.livestock);
  assert.equal(stored.length, 1, 'the tombstone must remain');
  assert.equal(stored[0].deleted, true);
});

test('download returns only rows past the cursor, and newSeq is what was sent', () => {
  const gw = gatewayWithAdmin();
  const first = gw.post(syncBody([livestockOp('op-1', 'entry-1')]));
  const seqAfterFirst = first.results[0].seq;

  gw.post(syncBody([livestockOp('op-2', 'entry-2')]));

  const res = gw.post(syncBody([], seqAfterFirst));

  const ids = res.changes.livestock.map((r) => r.id);
  assert.ok(!ids.includes('entry-1'), 'already-seen row must not be resent');
  assert.ok(ids.includes('entry-2'));
  assert.ok(res.newSeq > seqAfterFirst);
  assert.equal(res.hasMore, false);
});

test('a fresh device with cursor 0 receives everything', () => {
  const gw = gatewayWithAdmin();
  gw.post(syncBody([livestockOp('op-1', 'entry-1')]));
  gw.post(syncBody([livestockOp('op-2', 'entry-2')]));

  const res = gw.post(syncBody([], 0));

  assert.equal(res.changes.livestock.length, 2);
  // The Users row is created with a sequence too, so the cursor covers it.
  assert.ok(res.newSeq >= 3);
});

test('a lock timeout is reported as retryable and writes nothing', () => {
  const gw = gatewayWithAdmin();
  gw.setLockAvailable(false);

  const res = gw.post(syncBody([livestockOp('op-1', 'entry-1')]));

  assert.equal(res.ok, false);
  assert.equal(res.error.retryable, true, 'client must retry, not discard the record');
  assert.equal(gw.rows(SHEETS.livestock).length, 0);
});

test('a token minted for another application is refused', () => {
  const gw = gatewayWithAdmin();
  asUser(gw, 'attacker@example.com', { aud: 'someone-elses-client', variant: 'badaud' });

  const res = gw.post(syncBody());

  assert.equal(res.ok, false);
  assert.equal(res.error.code, 'UNAUTHORISED');
  assert.equal(res.error.retryable, false);
});

test('an unverified email is refused', () => {
  const gw = gatewayWithAdmin();
  const claims = claimsFor('unverified@example.com');
  claims.email_verified = 'false';
  asUserWithClaims(gw, 'unverified', claims);

  const res = gw.post(syncBody());

  assert.equal(res.ok, false);
  assert.equal(res.error.code, 'UNAUTHORISED');
});

test('an expired token is refused', () => {
  const gw = gatewayWithAdmin();
  const claims = claimsFor('stale@example.com');
  claims.exp = String(Math.floor(Date.now() / 1000) - 60);
  asUserWithClaims(gw, 'stale', claims);

  assert.equal(gw.post(syncBody()).error.code, 'UNAUTHORISED');
});

test('token verification is cached so a sync burst costs one UrlFetch', () => {
  const gw = createGateway();
  asUser(gw, 'admin@example.com');

  gw.post(syncBody());
  const afterFirst = gw.state.fetchCount;
  gw.post(syncBody());
  gw.post(syncBody());

  assert.equal(afterFirst, 1);
  assert.equal(gw.state.fetchCount, 1, 'repeat calls with the same token must not refetch');
});

test('an oversized batch is refused rather than risking the execution ceiling', () => {
  const gw = gatewayWithAdmin();
  const many = [];
  for (let i = 0; i < 201; i++) many.push(livestockOp('op-' + i, 'entry-' + i));

  const res = gw.post(syncBody(many));

  assert.equal(res.ok, false);
  assert.equal(res.error.code, 'BAD_REQUEST');
  assert.equal(res.error.retryable, false);
});

test('a malformed operation is rejected without aborting the whole batch', () => {
  const gw = gatewayWithAdmin();

  const res = gw.post(syncBody([
    { opId: 'op-bad' },
    livestockOp('op-good', 'entry-1'),
  ]));

  assert.equal(res.results[0].status, 'REJECTED');
  assert.equal(res.results[1].status, 'APPLIED');
  assert.equal(gw.rows(SHEETS.livestock).length, 1);
});

test('a receipt upload is idempotent on receiptId', () => {
  const gw = gatewayWithAdmin();
  const body = {
    idToken: currentToken,
    action: 'uploadReceipt',
    receiptId: 'receipt-1',
    mimeType: 'image/jpeg',
    dataBase64: Buffer.from('pretend-jpeg-bytes').toString('base64'),
  };

  const first = gw.post(body);
  const second = gw.post(body);

  assert.equal(first.ok, true);
  assert.equal(first.duplicate, false);
  assert.equal(second.duplicate, true, 'a retry must not create a second Drive file');
  assert.equal(second.driveFileId, first.driveFileId);
  assert.equal(gw.rows(SHEETS.receipts).length, 1);
});

test('expenses use their own permission, independent of livestock', () => {
  const gw = gatewayWithAdmin();
  asUser(gw, 'member@example.com');
  gw.post(syncBody());

  const users = gw.rows(SHEETS.users);
  const index = users.findIndex((u) => u.email === 'member@example.com');
  const sheet = gw.spreadsheet.getSheetByName(SHEETS.users);
  const headers = gw.sandbox.SCHEMA[SHEETS.users];
  const updated = { ...users[index], status: 'approved', permissions: 'edit_livestock' };
  sheet.getRange(index + 2, 1, 1, headers.length)
    .setValues([headers.map((h) => updated[h] ?? '')]);

  const expenseOp = {
    opId: 'op-exp',
    entityType: 'expense',
    entityId: 'exp-1',
    opType: 'UPSERT',
    baseVersion: 0,
    row: { category: 'feed', amountMinor: 4250000, description: 'Maize', occurredOn: '2026-08-02', deleted: false },
  };

  const res = gw.post(syncBody([expenseOp]));

  assert.equal(res.results[0].status, 'REJECTED');
  assert.match(res.results[0].message, /edit_expenses/);
});

test('deleting requires delete_entries even with edit rights', () => {
  const gw = gatewayWithAdmin();
  gw.post(syncBody([livestockOp('op-1', 'entry-1')]));

  asUser(gw, 'member@example.com');
  gw.post(syncBody());
  const users = gw.rows(SHEETS.users);
  const index = users.findIndex((u) => u.email === 'member@example.com');
  const sheet = gw.spreadsheet.getSheetByName(SHEETS.users);
  const headers = gw.sandbox.SCHEMA[SHEETS.users];
  const updated = { ...users[index], status: 'approved', permissions: 'edit_livestock' };
  sheet.getRange(index + 2, 1, 1, headers.length)
    .setValues([headers.map((h) => updated[h] ?? '')]);

  const del = { ...livestockOp('op-del', 'entry-1'), opType: 'DELETE', baseVersion: 1 };
  const res = gw.post(syncBody([del]));

  assert.equal(res.results[0].status, 'REJECTED');
  assert.match(res.results[0].message, /delete_entries/);
  const untouched = gw.rows(SHEETS.livestock)[0];
  assert.ok(!untouched.deleted, 'row must not have been tombstoned');
  assert.equal(untouched.version, 1, 'version must not have moved');
});

test('sequence numbers are strictly increasing across writes', () => {
  const gw = gatewayWithAdmin();
  const seqs = [];
  for (let i = 0; i < 5; i++) {
    const res = gw.post(syncBody([livestockOp('op-' + i, 'entry-' + i)]));
    seqs.push(res.results[0].seq);
  }
  for (let i = 1; i < seqs.length; i++) {
    assert.ok(seqs[i] > seqs[i - 1], 'seq must never repeat or go backwards');
  }
});

test('an empty body is a non-retryable bad request', () => {
  const gw = gatewayWithAdmin();
  const output = gw.sandbox.doPost({});
  const res = JSON.parse(output.getContent());

  assert.equal(res.ok, false);
  assert.equal(res.error.code, 'BAD_REQUEST');
  assert.equal(res.error.retryable, false);
});

// --- Session tokens ---------------------------------------------------------
// Background sync has no Activity, so it cannot mint a Google ID token. These
// cover the exchange that makes headless sync possible at all.

test('an ID token can be exchanged for a session token', () => {
  const gw = createGateway();
  asUser(gw, 'admin@example.com');

  const res = gw.post({ idToken: currentToken, action: 'session', deviceId: 'device-A' });

  assert.equal(res.ok, true);
  assert.ok(res.sessionToken.length >= 32);
  assert.ok(new Date(res.expiresAt).getTime() > Date.now());
  assert.equal(res.profile.role, 'admin');
});

test('only the hash of a session token is stored', () => {
  const gw = createGateway();
  asUser(gw, 'admin@example.com');
  const res = gw.post({ idToken: currentToken, action: 'session', deviceId: 'device-A' });

  const stored = gw.rows(SHEETS.sessions);
  assert.equal(stored.length, 1);
  assert.notEqual(stored[0].tokenHash, res.sessionToken);
  assert.equal(stored[0].tokenHash.length, 64, 'sha-256 hex');
  const flat = JSON.stringify(stored);
  assert.ok(!flat.includes(res.sessionToken), 'raw token must never be persisted');
});

test('a session token authenticates sync with no ID token at all', () => {
  const gw = createGateway();
  asUser(gw, 'admin@example.com');
  const session = gw.post({ idToken: currentToken, action: 'session', deviceId: 'device-A' });

  const res = gw.post({
    sessionToken: session.sessionToken,
    action: 'sync',
    deviceId: 'device-A',
    lastSeq: 0,
    operations: [livestockOp('op-1', 'entry-1')],
  });

  assert.equal(res.ok, true);
  assert.equal(res.results[0].status, 'APPLIED');
  assert.equal(gw.rows(SHEETS.livestock)[0].createdBy, 'admin@example.com');
});

test('session sync costs no UrlFetch, protecting the scarcest quota', () => {
  const gw = createGateway();
  asUser(gw, 'admin@example.com');
  const session = gw.post({ idToken: currentToken, action: 'session', deviceId: 'device-A' });
  const afterExchange = gw.state.fetchCount;

  for (let i = 0; i < 5; i++) {
    gw.post({
      sessionToken: session.sessionToken,
      action: 'sync',
      deviceId: 'device-A',
      lastSeq: 0,
      operations: [livestockOp('op-' + i, 'entry-' + i)],
    });
  }

  assert.equal(gw.state.fetchCount, afterExchange, 'no token verification calls');
});

test('a revoked session stops working', () => {
  const gw = createGateway();
  asUser(gw, 'admin@example.com');
  const session = gw.post({ idToken: currentToken, action: 'session', deviceId: 'device-A' });

  gw.post({ sessionToken: session.sessionToken, action: 'signOut' });

  const res = gw.post({
    sessionToken: session.sessionToken,
    action: 'sync',
    deviceId: 'device-A',
    lastSeq: 0,
    operations: [],
  });

  assert.equal(res.ok, false);
  assert.equal(res.error.code, 'SESSION_EXPIRED');
  assert.equal(res.error.retryable, false, 'client should re-exchange, not blind-retry');
});

test('an unknown session token is refused', () => {
  const gw = createGateway();
  asUser(gw, 'admin@example.com');
  gw.post({ idToken: currentToken, action: 'session', deviceId: 'device-A' });

  const res = gw.post({
    sessionToken: 'not-a-real-token',
    action: 'sync',
    deviceId: 'device-A',
    lastSeq: 0,
    operations: [],
  });

  assert.equal(res.error.code, 'SESSION_EXPIRED');
});

test('a session cannot be used to mint another session', () => {
  const gw = createGateway();
  asUser(gw, 'admin@example.com');
  const session = gw.post({ idToken: currentToken, action: 'session', deviceId: 'device-A' });

  const res = gw.post({ sessionToken: session.sessionToken, action: 'session' });

  assert.equal(res.ok, false, 'creating a session must require a Google ID token');
});

test('a session inherits the account it was minted for, not a chosen one', () => {
  const gw = createGateway();
  asUser(gw, 'admin@example.com');
  gw.post(syncBody());

  asUser(gw, 'member@example.com');
  const memberSession = gw.post({ idToken: currentToken, action: 'session', deviceId: 'device-B' });

  const res = gw.post({
    sessionToken: memberSession.sessionToken,
    action: 'sync',
    deviceId: 'device-B',
    lastSeq: 0,
    operations: [],
  });

  assert.equal(res.profile.email, 'member@example.com');
  assert.equal(res.profile.status, 'pending', 'a session grants no extra authority');
});

test('a request with neither credential is refused cheaply', () => {
  const gw = createGateway();

  const res = gw.post({ action: 'sync', deviceId: 'device-A', lastSeq: 0, operations: [] });

  assert.equal(res.ok, false);
  assert.equal(res.error.code, 'BAD_REQUEST');
  assert.equal(gw.state.fetchCount, 0, 'must not spend a UrlFetch on an unauthenticated call');
});
