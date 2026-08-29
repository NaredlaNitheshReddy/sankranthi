/**
 * Runs the gateway's .gs sources under Node by stubbing the Apps Script runtime.
 *
 * The upsert path is the single largest piece of correctness risk in this
 * project — it hand-rolls the idempotency and optimistic concurrency that a real
 * database would provide — so it needs tests that do not depend on having a
 * Google account, a deployment, or a network.
 *
 * The stubs are deliberately literal about the awkward parts of Sheets: a range
 * is 1-based, `getLastRow` counts the header, and every stored cell comes back
 * as whatever JavaScript value was written. Where the real API would coerce
 * types, the tests assert on the coerced form rather than pretending otherwise.
 */

import { readFileSync } from 'node:fs';
import { createContext, runInContext } from 'node:vm';
import { createHash, randomUUID } from 'node:crypto';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const SRC = join(HERE, '..');

/** Load order matters only because Config.gs reads a script property at load. */
const SOURCES = ['Config.gs', 'Store.gs', 'Auth.gs', 'Session.gs', 'Drive.gs', 'Code.gs'];

class MockRange {
  constructor(sheet, row, col, numRows, numCols) {
    Object.assign(this, { sheet, row, col, numRows, numCols });
  }

  getValues() {
    const out = [];
    for (let r = 0; r < this.numRows; r++) {
      const row = [];
      for (let c = 0; c < this.numCols; c++) {
        const cell = this.sheet.cells[this.row - 1 + r]?.[this.col - 1 + c];
        row.push(cell === undefined ? '' : cell);
      }
      out.push(row);
    }
    return out;
  }

  setValues(values) {
    for (let r = 0; r < values.length; r++) {
      const target = this.row - 1 + r;
      this.sheet.cells[target] ??= [];
      for (let c = 0; c < values[r].length; c++) {
        this.sheet.cells[target][this.col - 1 + c] = values[r][c];
      }
    }
    return this;
  }

  setNumberFormat() { return this; }
  setFontWeight() { return this; }
}

class MockSheet {
  constructor(name) {
    this.name = name;
    this.cells = [];
  }

  getLastRow() {
    for (let i = this.cells.length - 1; i >= 0; i--) {
      if (this.cells[i]?.some((v) => v !== '' && v !== undefined && v !== null)) return i + 1;
    }
    return 0;
  }

  getMaxRows() { return Math.max(1000, this.cells.length + 10); }
  getRange(row, col, numRows = 1, numCols = 1) {
    return new MockRange(this, row, col, numRows, numCols);
  }

  appendRow(values) {
    this.cells[this.getLastRow()] = [...values];
  }

  setFrozenRows() { return this; }
}

class MockSpreadsheet {
  constructor() { this.sheets = new Map(); }
  getSheetByName(name) { return this.sheets.get(name) ?? null; }
  insertSheet(name) {
    const sheet = new MockSheet(name);
    this.sheets.set(name, sheet);
    return sheet;
  }
}

class MockFile {
  constructor(name, bytes, mimeType) {
    this.name = name;
    this.bytes = bytes;
    this.mimeType = mimeType;
    this.id = 'drive-' + name;
  }
  getId() { return this.id; }
  getSize() { return this.bytes.length; }
}

class MockFolder {
  constructor(name) {
    this.name = name;
    this.folders = new Map();
    this.files = new Map();
  }
  getFoldersByName(name) {
    const hit = this.folders.get(name);
    let done = !hit;
    return { hasNext: () => !done, next: () => { done = true; return hit; } };
  }
  createFolder(name) {
    const folder = new MockFolder(name);
    this.folders.set(name, folder);
    return folder;
  }
  getFilesByName(name) {
    const hit = this.files.get(name);
    let done = !hit;
    return { hasNext: () => !done, next: () => { done = true; return hit; } };
  }
  createFile(blob) {
    const file = new MockFile(blob.name, blob.bytes, blob.mimeType);
    this.files.set(blob.name, file);
    return file;
  }
}

/**
 * Builds a fresh sandbox.
 *
 * @param {object} options
 * @param {object} options.properties  script properties
 * @param {object} options.claims      what tokeninfo returns for any token
 * @param {boolean} options.lockAvailable  false simulates a lock timeout
 */
export function createGateway(options = {}) {
  const {
    properties = { SPREADSHEET_ID: 'sheet-1', WEB_CLIENT_ID: 'client-1.apps.googleusercontent.com' },
    claims = null,
    lockAvailable = true,
  } = options;

  const spreadsheet = new MockSpreadsheet();
  const driveRoot = new MockFolder('root');
  const cache = new Map();
  const state = { tokenClaims: claims, lockAvailable, lockHeld: false, fetchCount: 0 };

  const defaultClaims = () => ({
    sub: 'user-sub-1',
    email: 'first@example.com',
    email_verified: 'true',
    name: 'First Partner',
    aud: properties.WEB_CLIENT_ID,
    iss: 'https://accounts.google.com',
    exp: String(Math.floor(Date.now() / 1000) + 3600),
  });

  const sandbox = {
    console,
    Date,
    Math,
    JSON,
    String,
    Number,
    Object,
    Array,
    Error,
    encodeURIComponent,
    parseInt,
    parseFloat,

    PropertiesService: {
      getScriptProperties: () => ({
        getProperty: (k) => (k in properties ? properties[k] : null),
        setProperty: (k, v) => { properties[k] = v; },
      }),
    },

    CacheService: {
      getScriptCache: () => ({
        get: (k) => (cache.has(k) ? cache.get(k) : null),
        put: (k, v) => { cache.set(k, v); },
        remove: (k) => { cache.delete(k); },
      }),
    },

    SpreadsheetApp: { openById: () => spreadsheet },

    DriveApp: { getRootFolder: () => driveRoot },

    LockService: {
      getScriptLock: () => ({
        tryLock: () => {
          if (!state.lockAvailable) return false;
          state.lockHeld = true;
          return true;
        },
        releaseLock: () => { state.lockHeld = false; },
      }),
    },

    UrlFetchApp: {
      fetch: () => {
        state.fetchCount++;
        const body = state.tokenClaims ?? defaultClaims();
        return {
          getResponseCode: () => (body.__httpError ? body.__httpError : 200),
          getContentText: () => JSON.stringify(body),
        };
      },
    },

    Utilities: {
      DigestAlgorithm: { SHA_256: 'SHA_256' },
      computeDigest: (_algo, value) => {
        const digest = createHash('sha256').update(value).digest();
        // Apps Script hands back signed bytes; mirror that so the hex helper is
        // exercised the same way it will be in production.
        return Array.from(digest).map((b) => (b > 127 ? b - 256 : b));
      },
      formatDate: (date, _tz, format) => {
        const iso = new Date(date).toISOString();
        if (format === 'yyyy') return iso.slice(0, 4);
        if (format === 'MM') return iso.slice(5, 7);
        return iso.slice(0, 19) + 'Z';
      },
      getUuid: () => randomUUID(),
      base64Decode: (b64) => Array.from(Buffer.from(b64, 'base64')),
      newBlob: (bytes, mimeType, name) => ({ bytes, mimeType, name }),
    },

    ContentService: {
      MimeType: { JSON: 'application/json' },
      // The real TextOutput is chainable and setMimeType returns `this`.
      createTextOutput: (text) => {
        const output = {
          getContent: () => text,
          setMimeType: () => output,
        };
        return output;
      },
    },
  };

  const context = createContext(sandbox);
  for (const file of SOURCES) {
    runInContext(readFileSync(join(SRC, file), 'utf8'), context, { filename: file });
  }

  /** Calls doPost with a JSON body and returns the parsed response. */
  const post = (body) => {
    const output = sandbox.doPost({ postData: { contents: JSON.stringify(body) } });
    return JSON.parse(output.getContent());
  };

  return {
    post,
    state,
    spreadsheet,
    driveRoot,
    /** Rows of a sheet as objects, for asserting on what was actually stored. */
    rows(sheetName) {
      const sheet = spreadsheet.getSheetByName(sheetName);
      if (!sheet) return [];
      const headers = sandbox.SCHEMA[sheetName];
      const last = sheet.getLastRow();
      if (last < 2) return [];
      return sheet.getRange(2, 1, last - 1, headers.length).getValues().map((values) => {
        const row = {};
        headers.forEach((h, i) => { row[h] = values[i]; });
        return row;
      });
    },
    setClaims(next) { state.tokenClaims = next; },
    setLockAvailable(available) { state.lockAvailable = available; },
    sandbox,
  };
}

export const SHEETS = {
  sessions: 'Sessions',
  users: 'Users',
  livestock: 'Livestock',
  expenses: 'Expenses',
  receipts: 'Receipts',
  meta: '_Meta',
  applied: 'AppliedOps',
};
