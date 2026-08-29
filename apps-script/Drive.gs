/**
 * Receipt storage.
 *
 * Files are created by *this script*, so the script owner owns every one of
 * them. That is what lets every approved partner view every receipt: had the app
 * uploaded directly with the `drive.file` scope, each install could only ever
 * see files it had created itself.
 */

/**
 * Stores one receipt image and records its metadata.
 *
 * Idempotent on `receiptId`, in two layers: the applied-operations log catches a
 * retry that reached us before, and a filename lookup catches the case where the
 * upload succeeded but the response was lost before we logged it. Without the
 * second check a lost response would silently produce a duplicate file.
 */
function handleReceiptUpload(identity, body) {
  var profile = resolveProfile(identity);
  if (!hasPermission(profile, 'edit_expenses') && !hasPermission(profile, 'edit_livestock')) {
    throw unauthorised('You do not have permission to attach receipts');
  }

  var receiptId = body.receiptId;
  if (!receiptId) throw badRequest('Missing receiptId');
  if (!body.dataBase64) throw badRequest('Missing dataBase64');

  var mimeType = body.mimeType || 'image/jpeg';
  var filename = receiptId + extensionFor(mimeType);

  var folder = receiptFolder(new Date());
  var existing = folder.getFilesByName(filename);
  if (existing.hasNext()) {
    var found = existing.next();
    return {
      ok: true,
      driveFileId: found.getId(),
      duplicate: true,
    };
  }

  var bytes = Utilities.base64Decode(body.dataBase64);
  var blob = Utilities.newBlob(bytes, mimeType, filename);
  var file = folder.createFile(blob);

  recordReceiptRow(receiptId, file, mimeType, profile);

  return { ok: true, driveFileId: file.getId(), duplicate: false };
}

/** `SankranthiApp/Receipts/YYYY/MM/`, created on demand (§12). */
function receiptFolder(date) {
  var root = folderByName(DriveApp.getRootFolder(), DRIVE_ROOT);
  var receipts = folderByName(root, 'Receipts');
  var year = folderByName(receipts, Utilities.formatDate(date, 'UTC', 'yyyy'));
  return folderByName(year, Utilities.formatDate(date, 'UTC', 'MM'));
}

function folderByName(parent, name) {
  var existing = parent.getFoldersByName(name);
  return existing.hasNext() ? existing.next() : parent.createFolder(name);
}

function recordReceiptRow(receiptId, file, mimeType, profile) {
  var table = readTable(SHEET_RECEIPTS);
  var index = findRowIndexById(table, receiptId);

  var row = index >= 0 ? shallowCopy(table.rows[index]) : {};
  row.id = receiptId;
  row.driveFileId = file.getId();
  row.mimeType = mimeType;
  row.sizeBytes = file.getSize();
  if (index < 0) {
    row.createdBy = profile.email;
    row.createdAt = nowIso();
    row.deleted = false;
  }
  row.updatedAt = nowIso();
  row.version = Number(index >= 0 ? Number(table.rows[index].version || 0) + 1 : 1);
  row.serverSeq = nextSeq();
  row.serverUpdatedAt = nowIso();

  if (index >= 0) {
    writeRow(SHEET_RECEIPTS, index, row);
  } else {
    appendRow(SHEET_RECEIPTS, row);
  }
}

function extensionFor(mimeType) {
  if (mimeType === 'image/png') return '.png';
  if (mimeType === 'image/webp') return '.webp';
  if (mimeType === 'application/pdf') return '.pdf';
  return '.jpg';
}
