/**
 * Spreadsheet access. Every read and write goes through here so the column
 * order in Config.SCHEMA stays the single source of truth.
 */

function spreadsheet() {
  return SpreadsheetApp.openById(cfg('SPREADSHEET_ID', true));
}

/**
 * Returns the named sheet, creating and formatting it on first use.
 *
 * Idempotent, so the gateway bootstraps an empty spreadsheet on its first
 * request and there is nothing for a human to set up by hand.
 */
function sheetFor(name) {
  var ss = spreadsheet();
  var sheet = ss.getSheetByName(name);
  var headers = SCHEMA[name];
  if (!headers) throw new Error('Unknown sheet: ' + name);

  if (!sheet) {
    sheet = ss.insertSheet(name);
  }

  if (sheet.getLastRow() === 0) {
    sheet.getRange(1, 1, 1, headers.length).setValues([headers]);
    sheet.setFrozenRows(1);
    sheet.getRange(1, 1, 1, headers.length).setFontWeight('bold');

    // Force text format on columns Sheets would otherwise reinterpret, so an
    // ISO date stays a string and a UUID keeps its leading zeroes.
    for (var c = 0; c < headers.length; c++) {
      if (TEXT_COLUMNS.indexOf(headers[c]) >= 0) {
        sheet.getRange(2, c + 1, sheet.getMaxRows() - 1, 1).setNumberFormat('@');
      }
    }
  }
  return sheet;
}

/** Reads a whole sheet into objects. `rows[i]` corresponds to sheet row `i + 2`. */
function readTable(name) {
  var sheet = sheetFor(name);
  var headers = SCHEMA[name];
  var lastRow = sheet.getLastRow();
  if (lastRow < 2) {
    return { sheet: sheet, headers: headers, rows: [] };
  }
  var values = sheet.getRange(2, 1, lastRow - 1, headers.length).getValues();
  var rows = values.map(function (value) {
    var row = {};
    for (var c = 0; c < headers.length; c++) {
      row[headers[c]] = value[c];
    }
    return row;
  });
  return { sheet: sheet, headers: headers, rows: rows };
}

function rowToValues(name, row) {
  return SCHEMA[name].map(function (header) {
    var v = row[header];
    return v === undefined || v === null ? '' : v;
  });
}

function appendRow(name, row) {
  var sheet = sheetFor(name);
  sheet.appendRow(rowToValues(name, row));
}

/** Overwrites the row at zero-based [index] as returned by readTable. */
function writeRow(name, index, row) {
  var sheet = sheetFor(name);
  var headers = SCHEMA[name];
  sheet.getRange(index + 2, 1, 1, headers.length).setValues([rowToValues(name, row)]);
}

/**
 * Allocates the next server sequence number.
 *
 * Callers must already hold the script lock. The counter is what makes
 * incremental download possible — a client asks for everything with
 * `serverSeq > lastSeq` instead of re-reading the whole spreadsheet.
 */
function nextSeq() {
  var table = readTable(SHEET_META);
  var index = -1;
  for (var i = 0; i < table.rows.length; i++) {
    if (String(table.rows[i].key) === 'seqCounter') {
      index = i;
      break;
    }
  }
  if (index < 0) {
    var seeded = { key: 'seqCounter', value: 1 };
    appendRow(SHEET_META, seeded);
    return 1;
  }
  var next = Number(table.rows[index].value || 0) + 1;
  writeRow(SHEET_META, index, { key: 'seqCounter', value: next });
  return next;
}

/** Highest sequence issued so far, without allocating a new one. */
function currentSeq() {
  var table = readTable(SHEET_META);
  for (var i = 0; i < table.rows.length; i++) {
    if (String(table.rows[i].key) === 'seqCounter') {
      return Number(table.rows[i].value || 0);
    }
  }
  return 0;
}

/**
 * Looks up a previously applied operation.
 *
 * Returns the *stored result*, not merely "seen before". A retry after a
 * response was lost in flight must be told the original outcome — reporting a
 * bare duplicate with no version would leave the client unable to mark the row
 * synced, and reapplying would create a second row.
 */
function findAppliedOp(opId) {
  var table = readTable(SHEET_APPLIED);
  for (var i = 0; i < table.rows.length; i++) {
    if (String(table.rows[i].opId) === String(opId)) {
      var raw = table.rows[i].resultJson;
      try {
        return JSON.parse(raw);
      } catch (e) {
        return { opId: opId, status: 'DUPLICATE' };
      }
    }
  }
  return null;
}

function recordAppliedOp(op, result) {
  appendRow(SHEET_APPLIED, {
    opId: op.opId,
    deviceId: op.deviceId || '',
    entityType: op.entityType,
    entityId: op.entityId,
    appliedAt: nowIso(),
    resultJson: JSON.stringify(result),
  });
}

/** Index of the row whose `id` matches, or -1. */
function findRowIndexById(table, id) {
  for (var i = 0; i < table.rows.length; i++) {
    if (String(table.rows[i].id) === String(id)) return i;
  }
  return -1;
}

function nowIso() {
  return Utilities.formatDate(new Date(), 'UTC', "yyyy-MM-dd'T'HH:mm:ss'Z'");
}
