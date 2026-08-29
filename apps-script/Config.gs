/**
 * Sankranthi sync gateway — configuration and sheet schema.
 *
 * This Web App is the only thing that touches the spreadsheet. The Android app
 * holds no Sheets or Drive scope at all: it sends a Google ID token, this script
 * verifies it and then reads and writes as the script owner.
 *
 * Set these in Project Settings -> Script Properties (never hard-code them):
 *   SPREADSHEET_ID  the spreadsheet this gateway owns
 *   WEB_CLIENT_ID   the OAuth *web* client id, checked against the token's `aud`
 *   ADMIN_EMAIL     optional; seeds this address as admin instead of first-signin
 */

var PROP = PropertiesService.getScriptProperties();

function cfg(key, required) {
  var value = PROP.getProperty(key);
  if (required && !value) {
    throw new Error('Missing Script Property: ' + key);
  }
  return value;
}

/** Bounded so one request can never exceed the six-minute execution ceiling. */
var MAX_OPS_PER_REQUEST = 200;
var MAX_CHANGES_PER_RESPONSE = 500;

/** Milliseconds to wait for the script lock before giving up and asking for a retry. */
var LOCK_TIMEOUT_MS = 30000;

var SHEET_USERS = 'Users';
var SHEET_LIVESTOCK = 'Livestock';
var SHEET_EXPENSES = 'Expenses';
var SHEET_RECEIPTS = 'Receipts';
var SHEET_META = '_Meta';
var SHEET_APPLIED = 'AppliedOps';

var DRIVE_ROOT = 'SankranthiApp';

/**
 * Column order per sheet. Order is the contract — the app's DTOs are built from
 * these names, so append new columns at the end and never reorder.
 *
 * `version`, `serverSeq` and `serverUpdatedAt` are owned by this script. The
 * client echoes them back but must never invent them.
 */
var SCHEMA = {};

SCHEMA[SHEET_USERS] = [
  'userId', 'email', 'name', 'role', 'status', 'permissions',
  'requestedAt', 'updatedAt', 'version', 'serverSeq', 'serverUpdatedAt',
];

SCHEMA[SHEET_LIVESTOCK] = [
  'id', 'kind', 'animal', 'headCount', 'amountMinor', 'counterparty',
  'occurredOn', 'notes', 'receiptId', 'createdBy', 'createdByName',
  'createdAt', 'updatedAt', 'deleted', 'version', 'serverSeq', 'serverUpdatedAt',
];

SCHEMA[SHEET_EXPENSES] = [
  'id', 'category', 'amountMinor', 'description', 'occurredOn', 'receiptId',
  'createdBy', 'createdByName', 'createdAt', 'updatedAt', 'deleted',
  'version', 'serverSeq', 'serverUpdatedAt',
];

SCHEMA[SHEET_RECEIPTS] = [
  'id', 'driveFileId', 'mimeType', 'sizeBytes', 'createdBy',
  'createdAt', 'updatedAt', 'deleted', 'version', 'serverSeq', 'serverUpdatedAt',
];

SCHEMA[SHEET_META] = ['key', 'value'];

SCHEMA[SHEET_APPLIED] = [
  'opId', 'deviceId', 'entityType', 'entityId', 'appliedAt', 'resultJson',
];

/**
 * Columns that must be stored as plain text.
 *
 * Without this Sheets helpfully reinterprets `2026-08-01` as a date and
 * `0001-...` UUID fragments as numbers, and the value the app reads back is not
 * the value it wrote. Forcing the `@` number format on these columns is the fix.
 */
var TEXT_COLUMNS = [
  'id', 'userId', 'opId', 'entityId', 'receiptId', 'driveFileId',
  'occurredOn', 'requestedAt', 'serverUpdatedAt', 'permissions',
];

var ENTITY_SHEETS = {
  livestock: SHEET_LIVESTOCK,
  expense: SHEET_EXPENSES,
  receipt: SHEET_RECEIPTS,
};
