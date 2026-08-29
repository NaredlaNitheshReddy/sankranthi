
# Livestock Management Android App

## Complete Architecture & Implementation Specification

**Document Version:** 3.0
**Platform:** Android
**Architecture:** Offline-First / Local-First
**Primary Goal:** A fast, attractive, reliable livestock-management application for a small organization with near-zero infrastructure cost.

---

# 1. Executive Summary

The application is a small-organization livestock management system designed to manage:

* Expenses
* Receipts
* Stock
* Livestock counts
* Medicine and medical records
* Users
* Roles and permissions
* Reports
* Audit history
* Synchronization
* Receipt storage configuration

The most important architectural requirement is:

> **The application must work like a local-first application rather than a cloud-dependent application.**

Users should be able to use the application normally even when there is no internet connection.

The application should behave conceptually like:

```text
User
  ↓
Android App
  ↓
Local SQLite Database
  ↓
Immediate UI Update
  ↓
Persistent Sync Queue
  ↓
Background Synchronization
  ├── Remote Business Data
  └── Google Drive Receipts
```

The cloud is used for synchronization and backup rather than being required for every user action.

---

# 2. Business Requirements

The application is intended for a small livestock organization.

Expected scale:

| Requirement           |                        Expected |
| --------------------- | ------------------------------: |
| Users                 |                     Maximum ~10 |
| New records           |                      ~500/month |
| Data retention        |                       10+ years |
| Devices               |        Multiple Android devices |
| Receipt storage       | Potentially thousands of images |
| Internet              |               May be unreliable |
| Infrastructure budget |                   Preferably ₹0 |

The application should therefore prioritize:

1. Reliability
2. Offline capability
3. Simplicity
4. Low operating cost
5. Data safety
6. Ease of maintenance
7. Good user experience

It should **not** introduce unnecessary enterprise infrastructure.

---

# 3. Primary Architectural Principle

The application must **not** be designed as:

```text
Android App
     ↓
Google Sheets
     ↓
Google Drive
```

Instead:

```text
                 Android App
                     │
                     ▼
              Local SQLite DB
                     │
                     ▼
               Sync Engine
                /         \
               /           \
              ▼             ▼
     Remote Business     Google Drive
         Data/API          Receipts
```

The local database is the immediate source for the application's UI.

The remote services provide synchronization, backup, and multi-device data sharing.

---

# 4. Why Local-First Architecture?

A traditional cloud-first application behaves like:

```text
User
 ↓
Internet
 ↓
Server
 ↓
Database
 ↓
Response
 ↓
UI
```

If internet fails, the application becomes unreliable.

The proposed architecture behaves like:

```text
User
 ↓
SQLite
 ↓
UI
```

and separately:

```text
SQLite
 ↓
Sync Queue
 ↓
Internet
 ↓
Cloud
```

Therefore, internet problems do not prevent the user from entering data.

---

# 5. Target User Experience

The application should feel:

* Fast
* Modern
* Responsive
* Smooth
* Attractive
* Simple
* Reliable

It should **not look like a spreadsheet wrapped inside a mobile application**.

The UI should provide immediate feedback.

For example:

```text
User taps:

SAVE EXPENSE
```

The application should:

```text
Validate
   ↓
Write to SQLite
   ↓
Commit transaction
   ↓
Update UI
   ↓
Show "Saved"
   ↓
Synchronize in background
```

The user should not wait for Google APIs.

---

# 6. Recommended Technology Stack

## 6.1 Mobile Framework

### Flutter

Use:

```text
Flutter
Dart
Material 3
```

Reasons:

* Excellent Android support
* Good performance
* Modern UI
* Strong animation support
* Mature ecosystem
* SQLite support
* Camera support
* File picker support
* Biometric support
* Easy future expansion

---

# 7. Local Database

Use:

```text
SQLite
```

Preferably through:

```text
Drift
```

Drift provides:

* Type-safe queries
* Reactive streams
* Database migrations
* Transactions
* Good Flutter integration

---

# 8. Remote Storage Strategy

The architecture should separate:

### Business Data

Examples:

```text
Expenses
Stock
Livestock
Medicine
Users
Roles
Audit logs
```

from:

### File Data

Examples:

```text
Receipts
Animal photographs
Other attachments
```

Recommended:

```text
Business metadata
        ↓
Remote data provider/API

Receipt files
        ↓
Google Drive
```

---

# 9. Google Drive Receipt Storage

Receipts should be stored in Google Drive rather than inside the primary database.

Example:

```text
Expense
   │
   └── receiptId
          │
          ▼
     Google Drive
```

The local database maintains the relationship.

---

# 10. Critical Receipt Design

A receipt captured while offline does **not** have a Google Drive File ID yet.

Therefore:

```text
receiptId       = local UUID
driveFileId     = NULL
```

This is completely valid.

Example:

```text
receiptId:
91ab7e34-....

driveFileId:
NULL

localPath:
/receipts/91ab7e34.jpg

uploadStatus:
PENDING
```

When internet returns:

```text
Local Receipt
      ↓
Google Drive Upload
      ↓
Drive returns File ID
      ↓
Save driveFileId locally
      ↓
Synchronize metadata
```

---

# 11. Never Use Drive File ID as Primary ID

Incorrect:

```text
Receipt ID = Google Drive File ID
```

This fails when the user is offline.

Correct:

```text
Receipt
 ├── id              → Local UUID
 └── driveFileId     → Nullable remote ID
```

The local UUID is the permanent identity.

The Drive ID is merely the remote storage reference.

---

# 12. UUID Strategy

Every synchronizable entity must have a UUID.

Examples:

```text
Expense:
550e8400-e29b-41d4-a716-446655440000

Receipt:
6ba7b810-9dad-11d1-80b4-00c04fd430c8
```

Do not use:

```text
1
2
3
4
```

as globally shared IDs.

Do not use:

```text
Google Sheet Row Number
```

as an application identifier.

---

# 13. Multi-Device Architecture

Each device has its own local database.

```text
                 Remote Store
                 /           \
                /             \
               ▼               ▼
           Phone A           Phone B
           SQLite            SQLite
               │               │
               ▼               ▼
            Sync              Sync
```

Example:

```text
Phone A:
Add Expense
   ↓
SQLite
   ↓
Sync
   ↓
Remote
   ↓
Phone B
   ↓
SQLite
   ↓
UI
```

---

# 14. Eventual Consistency

The application intentionally uses eventual consistency.

Suppose Phone A creates an expense.

Immediately:

```text
Phone A
Expense = visible
Sync = pending
```

After synchronization:

```text
Remote
Expense = available
```

Then Phone B synchronizes:

```text
Phone B
Expense = visible
```

There may therefore be a short delay between devices.

This is expected.

---

# 15. Local Source vs Remote Source

The application should distinguish:

### Local state

```text
Saved on this phone
```

from:

### Remote synchronization state

```text
Available to other devices
```

Example:

```text
Expense

Local:
✓ Saved

Remote:
⟳ Syncing
```

---

# 16. Sync Status

Every synchronizable record should have a sync status.

Recommended:

```text
LOCAL_ONLY
PENDING
SYNCING
SYNCED
FAILED
CONFLICT
```

Example UI:

```text
Feed Purchase
₹2,500

⟳ Syncing
```

or:

```text
Feed Purchase
₹2,500

✓ Synced
```

or:

```text
Feed Purchase
₹2,500

⚠ Sync failed

[Retry]
```

---

# 17. Persistent Sync Queue

Synchronization operations must be stored in SQLite.

Do not keep the queue only in application memory.

Example:

```text
Sync Queue

Operation 1
Expense CREATE
Pending

Operation 2
Receipt UPLOAD
Pending

Operation 3
Expense UPDATE
Failed
```

If the application is:

* killed
* restarted
* crashed
* backgrounded

the queue remains.

---

# 18. Sync Operation Model

```text
SyncOperation {
    id

    entityType
    entityId

    operationType

    payload

    status

    retryCount

    createdAt
    lastAttemptAt

    errorMessage

    idempotencyKey
}
```

Possible operations:

```text
CREATE
UPDATE
DELETE
RESTORE
UPLOAD_RECEIPT
SYNC_RECEIPT_METADATA
```

---

# 19. Idempotency

Every operation must be safe to retry.

Example:

```text
Receipt Upload

idempotencyKey:
RECEIPT_UPLOAD:91ab7e34
```

If the same operation is accidentally executed twice, the system must not create two receipts.

---

# 20. Offline Expense Flow

Example:

```text
User
 ↓
Add Expense
 ↓
Amount = ₹2,500
Reason = Feed
Receipt = Camera image
```

Application:

```text
Generate Expense UUID
Generate Receipt UUID
        ↓
SQLite Transaction
        ↓
Save Expense
Save Receipt metadata
Save local image reference
Create Sync Operations
        ↓
Commit
        ↓
Update UI
```

The user immediately sees:

```text
Feed Purchase
₹2,500

Data: Pending
Receipt: Pending
```

---

# 21. Offline Receipt Handling

While offline:

```text
receiptId = REC-91AB
driveFileId = NULL
localPath = /receipts/REC-91AB.jpg
uploadStatus = PENDING
```

The receipt image remains on the device.

Nothing needs to be uploaded immediately.

---

# 22. When Internet Returns

The sync engine detects connectivity.

```text
Connectivity restored
        ↓
Read pending queue
        ↓
Process expense
        ↓
Upload receipt
        ↓
Google Drive returns driveFileId
        ↓
Persist driveFileId locally
        ↓
Synchronize receipt metadata
        ↓
Mark receipt SYNCED
```

Final state:

```text
receiptId:
REC-91AB

driveFileId:
1A2B3C4D...

uploadStatus:
SYNCED
```

---

# 23. Receipt and Expense Synchronization Must Be Independent

This is a critical requirement.

An expense can be:

```text
Data:
✓ Synced

Receipt:
⟳ Uploading
```

or:

```text
Data:
✓ Synced

Receipt:
⚠ Upload failed
```

The expense itself should not be considered failed merely because its receipt upload failed.

---

# 24. Receipt State Machine

Recommended:

```text
LOCAL_ONLY
     │
     ▼
PENDING_UPLOAD
     │
     ▼
UPLOADING
     │
 ┌───┴────┐
 │        │
 ▼        ▼
FAILED   UPLOADED
 │          │
 │          ▼
 │    METADATA_PENDING
 │          │
 │          ▼
 │        SYNCED
 │
 └────────> RETRY
```

---

# 25. Partial Failure Scenario

Consider:

```text
Receipt upload → SUCCESS
```

Google Drive creates:

```text
driveFileId = 1ABC...
```

But then:

```text
Metadata update → FAILED
```

The application must not upload the receipt again.

Local state:

```text
driveFileId = 1ABC...
uploadStatus = UPLOADED
metadataSyncStatus = FAILED
```

Retry:

```text
Skip upload
      ↓
Retry metadata synchronization
```

This prevents duplicate files.

---

# 26. Local Database Transaction

When creating an expense with a receipt:

```text
BEGIN TRANSACTION

Create Expense
Create Receipt
Create Expense SyncOperation
Create Receipt SyncOperation

COMMIT
```

The entire local operation must succeed atomically.

---

# 27. Expense Schema

```text
Expense {
    id                  // Local UUID
    remoteId            // Optional remote identifier

    date
    amount
    reason
    category

    addedBy
    createdAt

    updatedAt
    updatedBy

    version

    receiptId

    syncStatus

    isDeleted
    deletedAt
    deletedBy
}
```

---

# 28. Receipt Schema

```text
Receipt {
    id                  // Local UUID

    expenseId

    localPath

    driveFileId         // Nullable

    storageConfigId

    fileName
    mimeType
    size

    uploadStatus
    metadataSyncStatus

    createdAt
    uploadedAt

    retryCount
    lastError
}
```

---

# 29. User Schema

```text
User {
    id
    googleSubjectId

    name
    email

    roleId

    isActive

    createdAt
    updatedAt
}
```

---

# 30. Role Schema

```text
Role {
    id
    name
    description
}
```

---

# 31. Permission Schema

```text
Permission {
    id
    name
    description
}
```

---

# 32. Stock Schema

Suggested structure:

```text
StockItem {
    id
    name
    category
    unit
    currentQuantity
    minimumQuantity
    isActive
}
```

Stock transactions:

```text
StockTransaction {
    id
    stockItemId

    type
    quantity

    reason
    date

    addedBy

    createdAt

    syncStatus
}
```

Types:

```text
PURCHASE
CONSUMPTION
ADJUSTMENT
TRANSFER
```

---

# 33. Livestock Schema

```text
LivestockCategory {
    id
    name
}
```

Count history:

```text
LivestockCount {
    id

    categoryId

    previousCount
    newCount

    change

    reason

    addedBy
    createdAt

    syncStatus
}
```

---

# 34. Medicine Schema

```text
MedicineRecord {
    id

    medicineName

    animalCategory
    quantity
    dosage

    treatmentDate
    nextDueDate

    notes

    addedBy

    createdAt
    updatedAt

    syncStatus
}
```

---

# 35. Audit Log

Important actions must be recorded.

```text
AuditLog {
    id

    userId

    action

    entityType
    entityId

    timestamp

    oldValue
    newValue
}
```

Actions:

```text
CREATE_EXPENSE
UPDATE_EXPENSE
DELETE_EXPENSE
RESTORE_EXPENSE

ADD_USER
DISABLE_USER
CHANGE_ROLE

CHANGE_RECEIPT_DRIVE

SYNC_FAILURE
SYNC_RETRY
```

---

# 36. Soft Delete

Business records should not normally be physically deleted.

Instead:

```text
isDeleted = true
deletedAt = timestamp
deletedBy = user
```

Normal users do not see deleted records.

Admins can view:

```text
Deleted Records

Feed Purchase
₹2,500

Deleted by Ravi
29 Aug 2026

[Restore]
```

---

# 37. Admin Dashboard

Example:

```text
Admin Dashboard

Expenses
₹48,250

Animals
126

Pending Sync
3

Failed Sync
2
```

Management:

```text
Users
Roles & Permissions
Reports
Receipt Storage
Sync Health
Audit Logs
Deleted Records
Settings
```

---

# 38. Worker Dashboard

Example:

```text
Good Morning 👋

Farm Overview

🐑 126 Animals
💰 ₹48,250 Expenses
📦 Stock
💊 Medicine

Recent Activity

Feed Purchase
₹2,500
✓ Synced

Medicine
₹850
⟳ Syncing
```

---

# 39. Bottom Navigation

Worker mode:

```text
Expenses
Stock
Live Count
Medicine
```

Keep the worker interface simple.

Admin functions should not clutter the main worker navigation.

---

# 40. Expense Module

Expense list should show:

* Date
* Reason
* Amount
* Category
* Added by
* Sync status
* Receipt status

Example:

```text
29 Aug

Feed Purchase
₹2,500

Ravi

Data ✓
Receipt ⟳
```

---

# 41. Add Expense Screen

Fields:

```text
Date
Amount
Reason
Category
Added By
Receipt
```

Receipt options:

```text
[ Take Photo ]

[ Choose From Gallery ]
```

Save:

```text
[ Save Expense ]
```

---

# 42. Edit Expense

Editing must be local-first.

```text
Open Expense
 ↓
Edit
 ↓
SQLite transaction
 ↓
UI updates
 ↓
UPDATE SyncOperation
 ↓
Background synchronization
```

---

# 43. Stock Module

Track:

* Feed
* Supplies
* Medicine stock
* Other inventory

Example:

```text
Feed

Opening:
100 kg

Purchase:
+50 kg

Consumption:
-20 kg

Adjustment:
-5 kg

Current:
125 kg
```

Prefer transaction history rather than blindly overwriting stock quantities.

---

# 44. Livestock Count Module

The count UI should be extremely fast.

Example:

```text
Live Count

Sheep
82

[ - ]    [ + ]

Goats
41

[ - ]    [ + ]

Total
123
```

Every change should be recorded.

---

# 45. Medicine Module

Support:

* Vaccination
* Treatment
* Medicine usage
* Due dates
* Medical history

Example:

```text
Medicine

Vitamin Injection

Date:
29 Aug

Quantity:
10 ml

Next Due:
29 Sep

[Save]
```

---

# 46. Reports

Reports should include:

### Expense Report

```text
August 2026

Total:
₹48,250

Feed:
₹24,000

Medicine:
₹8,500

Transport:
₹5,200

Other:
₹10,550
```

### Stock Report

* Opening stock
* Purchases
* Consumption
* Adjustments
* Closing stock

### Livestock Report

* Current count
* Count changes
* Historical trend

### Medicine Report

* Treatments
* Vaccinations
* Upcoming due dates

### Monthly Report

Combine:

```text
Expenses
Stock
Livestock
Medicine
Operational summary
```

---

# 47. Receipt Storage Administration

Admin should be able to manage receipt storage directly from the phone.

Navigation:

```text
Admin
 ↓
Settings
 ↓
Receipt Storage
```

Display:

```text
Receipt Storage

Google Account
admin@example.com

Status
✓ Connected

Folder
LivestockApp / Receipts

Storage
Used: X GB
Available: Y GB

Last Upload
12:42 PM

[ Test Connection ]

[ Change Drive ]
```

---

# 48. Why Receipt Storage Must Be Configurable

If the receipt Drive becomes full or needs to be replaced:

```text
Admin Phone
      ↓
Change Receipt Drive
      ↓
Authenticate new account
      ↓
Validate
      ↓
Activate
```

No application update should be required.

---

# 49. Receipt Storage Configuration

```text
ReceiptStorageConfiguration {
    id

    provider

    accountReference

    folderId

    status

    version

    createdAt
    activatedAt
    deactivatedAt

    createdBy
}
```

---

# 50. Changing Receipt Drive

Flow:

```text
Admin
 ↓
Receipt Storage
 ↓
Change Drive
 ↓
Google Authentication
 ↓
Select Account
 ↓
Select/Create Folder
 ↓
Verify Access
 ↓
Test Upload
 ↓
Success
 ↓
Confirm
 ↓
Activate New Drive
```

If validation fails:

```text
Old Drive remains active.
```

---

# 51. Receipt Storage Synchronization

Changing the Drive is itself synchronized.

Example:

```text
Admin Phone

Drive A
Version 14

Change to Drive B

Version 15
```

Other phones:

```text
Receive configuration version 15
        ↓
Download configuration
        ↓
Update local configuration
        ↓
New receipts use Drive B
```

---

# 52. Do Not Automatically Move Old Receipts

If Drive changes:

```text
Drive A
Receipt 001
Receipt 002
Receipt 003

Drive B
Receipt 004
Receipt 005
```

This is correct.

Old receipts remain in their original Drive.

---

# 53. Storage Configuration ID

Every receipt must contain:

```text
storageConfigId
```

Therefore:

```text
Receipt 001 → Drive A
Receipt 002 → Drive A
Receipt 003 → Drive B
```

The application always knows where the receipt is stored.

---

# 54. Drive Configuration History

Never delete old configurations.

Example:

```text
Receipt Storage History

Drive B
ACTIVE
Activated: Aug 2026

Drive A
ARCHIVED
Activated: Jan 2025
```

This allows historical receipts to remain accessible.

---

# 55. Drive Storage Warning

The admin UI should display approximate storage status.

Example:

```text
Storage Usage

████████░░ 80%

Warning:
Storage is approaching capacity.

[Change Receipt Drive]
```

The application should not automatically switch accounts without administrator approval.

---

# 56. Local Receipt Cleanup

After successful synchronization, the application should **not immediately delete the local receipt**.

A future cleanup policy can be implemented.

For example:

```text
Keep locally:
30 days
```

But only if:

* Drive upload succeeded
* Drive file ID is known
* Remote metadata is synchronized
* No local operation depends on the file

A cleanup operation must never destroy the only available copy.

---

# 57. Authentication

Recommended flow:

```text
App Launch
 ↓
Check local session
 ↓
If needed:
Google Sign-In
 ↓
Validate user
 ↓
Load role
 ↓
Load permissions
 ↓
Load configuration
 ↓
Dashboard
```

---

# 58. Permissions

Potential permissions:

```text
Camera
Photo/Gallery access
Notifications
Calendar
Biometrics
```

Only request permissions when actually needed.

For example:

Do not request camera permission during onboarding if the user may never capture a receipt.

Prefer contextual permission requests.

---

# 59. Biometrics

Biometrics should be used as a convenience/security layer for subsequent application access.

Conceptually:

```text
Google authentication
        ↓
Initial authenticated session
        ↓
Enable biometric unlock
        ↓
Future app opening
        ↓
Fingerprint / Face unlock
```

Biometrics should not replace the application's server-side authorization.

---

# 60. Security Requirements

Never embed:

```text
Google service-account private key
API secret
Private credentials
```

inside the APK.

Use:

```text
OAuth
Secure token storage
HTTPS
Remote authorization
```

The application should not rely only on UI-based permission checks.

---

# 61. Authorization

Authentication answers:

> Who is this user?

Authorization answers:

> What is this user allowed to do?

Permissions must therefore be enforced at the backend/API level.

For example:

```text
Worker

Can:
CREATE_EXPENSE

Cannot:
DELETE_USER
CHANGE_DRIVE
```

Hiding an Admin button is not sufficient security.

---

# 62. Remote Provider Abstraction

Do not make the entire application directly dependent on Google Sheets.

Use:

```text
Repository
    ↓
RemoteDataProvider
```

For example:

```text
abstract class RemoteDataProvider
```

Implementation:

```text
GoogleRemoteDataProvider
```

This makes it possible to replace the backend later with:

```text
Supabase
Firebase
PostgreSQL API
Custom API
```

without rewriting the application.

---

# 63. Google Sheets Considerations

If Google Sheets is selected as the remote metadata store, do not use:

```text
Sheet Row Number
```

as the application's primary key.

Instead:

```text
Expense UUID
Amount
Date
Reason
Receipt UUID
Drive File ID
Created By
Updated At
Deleted
Version
```

The sheet becomes a remote data representation rather than the application's local identity system.

---

# 64. Remote API Layer

The preferred architecture is:

```text
Flutter
   ↓
HTTPS API
   ↓
RemoteDataProvider
   ↓
Google Sheets / Remote Store
```

Do not place privileged Google credentials in the Android application.

The API layer should handle:

* Authorization
* Validation
* Idempotency
* Remote writes
* Remote reads
* Audit enforcement
* Configuration synchronization

---

# 65. Sync Engine

The Sync Engine is one of the most important components.

Responsibilities:

1. Read pending operations.
2. Prioritize operations.
3. Execute operations.
4. Retry failures.
5. Handle idempotency.
6. Handle dependencies.
7. Update local status.
8. Store errors.
9. Prevent duplicate uploads.
10. Resume after application restart.

---

# 66. Sync Ordering

Recommended:

```text
1. Configuration updates
2. Business record creation
3. Business record updates
4. Receipt uploads
5. Receipt metadata linking
6. Deletes/restores
7. Background refresh
```

However, actual dependencies should be implemented according to the remote API contract.

---

# 67. Sync Dependencies

For an expense with a receipt:

```text
Expense CREATE
      │
      ▼
Receipt UPLOAD
      │
      ▼
Receipt METADATA UPDATE
```

If the backend supports referencing the receipt UUID before the Drive upload completes, the metadata operations can be decoupled further.

---

# 68. Automatic Retry

Retry when:

* Internet returns
* Application starts
* Background work executes
* User taps Retry

Use exponential backoff.

Example:

```text
Retry 1 → 10 sec
Retry 2 → 30 sec
Retry 3 → 2 min
Retry 4 → 10 min
Retry 5 → later
```

Exact values can be tuned during implementation.

---

# 69. Manual Retry

Every failed operation should provide:

```text
[Retry]
```

Example:

```text
Feed Purchase
₹2,500

⚠ Receipt upload failed

The receipt is safely stored on this phone.

[ Retry ]
```

Admin can have:

```text
[Retry All Failed]
```

---

# 70. Sync Health Screen

Admin:

```text
Sync Health

Synced
145

Syncing
3

Failed
2

Last Successful Sync
12:42 PM

[Sync Now]

[Retry Failed]
```

---

# 71. Sync Details

Tapping a record:

```text
Sync Details

Expense
Feed Purchase

Data
✓ Synced

Receipt
⚠ Upload Failed

Error
Internet unavailable

Retry Count
3

Last Attempt
12:39 PM

[Retry Receipt]
```

---

# 72. Connectivity

The app should monitor connectivity but must not assume:

```text
Wi-Fi = Internet available
```

Actual synchronization success should determine whether the remote service is reachable.

---

# 73. Android Background Synchronization

Use Android-compatible background scheduling.

Potential triggers:

```text
After local write
Connectivity restoration
App launch
Periodic background work
Manual Sync Now
```

The application must respect Android background execution restrictions.

---

# 74. App Startup

On startup:

```text
Initialize database
      ↓
Load local configuration
      ↓
Render cached data
      ↓
Check authentication
      ↓
Check connectivity
      ↓
Start sync engine
```

Do not block the UI unnecessarily while waiting for synchronization.

---

# 75. UI Loading Strategy

Use:

```text
Local cached data
       ↓
Immediate UI
       ↓
Background remote refresh
```

Avoid:

```text
Loading...
Loading...
Loading...
```

when useful local data is already available.

---

# 76. Empty States

Instead of blank screens:

```text
No expenses yet.

Start recording your first expense.

[+ Add Expense]
```

---

# 77. Error Messages

Do not show:

```text
SocketException
HTTP 500
NullPointerException
```

Show:

```text
Couldn't sync this expense.

Your expense is safely saved on this phone.

[Retry]
```

---

# 78. Offline Message

Offline mode should not look like a failure.

Example:

```text
You're offline.

Your changes are saved safely on this phone.
They will sync automatically when you're online.
```

---

# 79. Modern UI Requirements

Use:

* Material 3
* Rounded cards
* Good spacing
* Modern typography
* Icons
* Chips
* Bottom sheets
* Floating action buttons
* Smooth transitions
* Pull-to-refresh
* Skeleton loading
* Snackbars
* Empty states
* Error states
* Dark mode

---

# 80. Dashboard Design

Dashboard should provide visual summaries.

Example:

```text
Good Morning 👋

Farm Overview

┌─────────────┐
│ Animals     │
│ 126         │
└─────────────┘

┌─────────────┐
│ Expenses    │
│ ₹48,250     │
└─────────────┘

┌─────────────┐
│ Stock       │
│ Healthy ✓   │
└─────────────┘

┌─────────────┐
│ Medicines   │
│ 3 Due Soon  │
└─────────────┘
```

---

# 81. Activity Feed

The application should maintain an internal activity feed.

Example:

```text
Today

Ravi added an expense
Feed Purchase — ₹2,500

Kumar updated livestock count
Goats: 41 → 42

Anil added medicine
Vitamin X

Admin changed receipt storage
```

This provides the desired WhatsApp-like visibility inside the application without making the core architecture dependent on WhatsApp.

---

# 82. External Notifications

If the organization later requires external notifications, such as notifications to a WhatsApp group, this must be treated as a separate integration.

Core application:

```text
Business Event
      ↓
Notification Abstraction
```

Possible providers can be added later.

The core system must continue working even when external notification delivery fails.

---

# 83. Database Indexes

Recommended indexes:

```text
Expense.date
Expense.isDeleted
Expense.syncStatus
Expense.updatedAt

Receipt.expenseId
Receipt.uploadStatus
Receipt.storageConfigId

SyncOperation.status
SyncOperation.createdAt
SyncOperation.entityId
```

---

# 84. Database Migrations

Because the application is intended to be used for many years, every schema change must use migrations.

Example:

```text
Database v1
   ↓
Migration
Database v2
   ↓
Migration
Database v3
```

Never require users to uninstall the app to update the schema.

---

# 85. Backup Architecture

There are multiple layers:

```text
Device
 └── SQLite
     ├── Business data
     ├── Sync queue
     └── Pending receipt references

Remote Store
 └── Business metadata

Google Drive
 └── Receipt files

Audit
 └── Historical changes
```

Important:

> Data that has not synchronized yet exists only on the originating device.

Therefore the application must clearly identify unsynchronized records.

---

# 86. Data Recovery

If a device is lost:

```text
Already synchronized data
        ↓
Can be recovered from remote storage
```

But:

```text
Local-only unsynchronized data
        ↓
May be lost with the device
```

Therefore:

* Sync should happen automatically whenever possible.
* Unsynced counts should be visible.
* Admin should be able to monitor synchronization health.

---

# 87. Multi-Device Conflict Handling

Example:

```text
Phone A edits Expense X
Phone B edits Expense X
```

Use:

```text
version
updatedAt
updatedBy
```

Initial conflict strategy:

```text
Last-write-wins
```

while preserving audit history.

A future version can implement more advanced conflict resolution if required.

---

# 88. User Management

Admin capabilities:

```text
Add user
Approve user
Disable user
Reactivate user
Assign role
Change permissions
View activity
```

Disabled users should not be able to perform new operations.

---

# 89. Role Model

Example:

```text
Worker
 ├── View Expenses
 ├── Add Expense
 ├── Edit Expense
 ├── View Stock
 ├── Update Stock
 ├── View Livestock
 └── View Medicine
```

Admin:

```text
All Worker permissions

+

Manage Users
Manage Roles
Delete/Restore
Reports
Receipt Storage
Sync Health
Audit Logs
Settings
```

---

# 90. Permission Model

Permissions should be extensible.

Examples:

```text
EXPENSE_VIEW
EXPENSE_CREATE
EXPENSE_EDIT
EXPENSE_DELETE

STOCK_VIEW
STOCK_UPDATE

LIVESTOCK_VIEW
LIVESTOCK_UPDATE

MEDICINE_VIEW
MEDICINE_CREATE

USER_MANAGE
ROLE_MANAGE

REPORT_VIEW
REPORT_GENERATE

RECEIPT_STORAGE_MANAGE

AUDIT_VIEW
SYNC_MANAGE
```

---

# 91. Report Generation

For this scale, reports do not require an expensive reporting infrastructure.

Reports can be generated from:

```text
Local SQLite
```

or:

```text
Remote synchronized data
```

Official monthly reports should preferably be generated from the authoritative synchronized dataset.

---

# 92. Monthly Report

Example:

```text
Monthly Livestock Report

August 2026

Animals
126

Expenses
₹48,250

Stock Purchases
₹25,000

Medicine
₹8,500

Major Changes
...

Pending Items
0
```

The report can eventually be exported as:

```text
PDF
Excel
CSV
```

depending on requirements.

---

# 93. Project Structure

Recommended:

```text
lib/

├── core/
│   ├── authentication/
│   ├── database/
│   ├── sync/
│   ├── networking/
│   ├── storage/
│   ├── notifications/
│   ├── permissions/
│   ├── security/
│   └── utilities/
│
├── features/
│   ├── auth/
│   ├── dashboard/
│   ├── expenses/
│   ├── stock/
│   ├── livestock/
│   ├── medicine/
│   ├── reports/
│   ├── users/
│   ├── roles/
│   ├── receipt_storage/
│   ├── sync_status/
│   └── settings/
│
└── shared/
    ├── widgets/
    ├── theme/
    ├── models/
    └── constants/
```

---

# 94. Repository Architecture

UI must never directly communicate with Google APIs.

Correct:

```text
UI
 ↓
ViewModel / Controller
 ↓
Repository
 ↓
Local Database
 ↓
Sync Engine
 ↓
Remote Provider
```

Example:

```text
ExpenseRepository

getExpenses()
createExpense()
updateExpense()
deleteExpense()
retrySync()
```

---

# 95. Local Repository Behavior

Example:

```text
createExpense()
```

should:

```text
1. Validate input
2. Generate UUID
3. Start transaction
4. Save expense
5. Save receipt metadata
6. Save sync operations
7. Commit
8. Notify UI
9. Trigger background synchronization
```

The network must not be required for step 8.

---

# 96. Receipt Repository

Example:

```text
ReceiptRepository

captureReceipt()
saveReceiptLocally()
uploadReceipt()
retryUpload()
getReceipt()
deleteLocalCopy()
```

---

# 97. Sync Engine Interface

Conceptually:

```text
SyncEngine

start()
pause()
resume()
syncNow()
retryFailed()
getPendingCount()
getFailedCount()
```

---

# 98. Sync State Persistence

Never rely on:

```text
boolean synced = true
```

Use detailed status information.

For example:

```text
Expense:
syncStatus = SYNCED

Receipt:
uploadStatus = UPLOADED
metadataSyncStatus = PENDING
```

This gives the application precise knowledge of what has happened.

---

# 99. Receipt Drive Rotation Example

Initial:

```text
Drive A

Receipt 001
Receipt 002
Receipt 003
```

Admin changes storage:

```text
Drive B
```

New receipts:

```text
Receipt 004 → Drive B
Receipt 005 → Drive B
```

Historical mapping remains:

```text
Receipt 001 → Drive A
Receipt 002 → Drive A
Receipt 004 → Drive B
```

---

# 100. Drive Change Synchronization

Suppose Admin Phone changes the Drive while another phone is offline.

Admin:

```text
Drive configuration
Version 15
```

Other phone:

```text
Old configuration
Version 14
```

When it reconnects:

```text
Remote configuration version 15
        ↓
Detect newer version
        ↓
Download
        ↓
Update local configuration
        ↓
Use Drive B for future receipts
```

---

# 101. What If a Worker Captures a Receipt While Drive Configuration Is Changing?

The receipt must retain the configuration that was active when it was created.

Example:

```text
Receipt created:

storageConfigId = 14
```

Even if configuration 15 becomes active later, the pending receipt still uploads according to its original configuration unless the application explicitly migrates the operation.

This prevents receipts from being accidentally uploaded to the wrong Drive.

---

# 102. What If Old Drive Becomes Unavailable?

The application should show:

```text
Receipt unavailable

This receipt belongs to an older storage location.

Please contact an administrator.
```

The application should not silently change the receipt's Drive reference.

---

# 103. Important Data Integrity Rules

Never:

* Change a receipt's local UUID
* Use a Drive File ID as the receipt primary key
* Delete a receipt record merely because upload failed
* Delete a sync operation after a transient failure
* Treat Wi-Fi availability as synchronization success
* Use spreadsheet row number as business ID
* Automatically migrate historical receipts between Drives

---

# 104. Performance Requirements

Target behavior:

### Add Expense

```text
Tap Save
↓
Immediate local commit
↓
UI update
```

Target perceived response:

```text
Near-instant
```

Network latency must not affect perceived save speed.

---

# 105. Image Performance

Receipts can consume significant storage.

Before upload:

* Compress large images
* Resize excessively high-resolution images
* Preserve readability
* Store MIME type
* Store file size
* Generate thumbnails if useful

---

# 106. Network Efficiency

The application should avoid unnecessary remote reads.

Prefer:

```text
Local database
+
incremental synchronization
```

instead of:

```text
Download entire dataset every time
```

Use:

```text
updatedAt
version
cursor
lastSyncToken
```

where supported.

---

# 107. Incremental Sync

Example:

```text
Last Sync:
2026-08-29 12:00
```

Next synchronization:

```text
Give me records changed after:
2026-08-29 12:00
```

This is much more efficient than downloading all historical data.

---

# 108. Initial Device Synchronization

When a new device logs in:

```text
Authenticate
 ↓
Load user
 ↓
Download required configuration
 ↓
Download business data
 ↓
Download required receipt metadata
 ↓
Build local indexes
 ↓
Start normal synchronization
```

Do not necessarily download every receipt image immediately.

Receipt images can be downloaded on demand if required.

---

# 109. Receipt Viewing

If a receipt is already locally cached:

```text
Open → Show local file
```

If not:

```text
Open
 ↓
Check Drive mapping
 ↓
Download from Drive
 ↓
Cache locally
 ↓
Display
```

This reduces device storage usage.

---

# 110. Receipt Download Status

Receipt UI can show:

```text
Receipt available ✓
```

or:

```text
Receipt downloading...
```

or:

```text
Receipt unavailable
[Retry]
```

---

# 111. Admin Sync Monitoring Across Devices

Admin should eventually be able to see:

```text
Devices

Ravi's Phone
Last sync: 12:42 PM
Status: Healthy

Kumar's Phone
Last sync: 11:35 AM
Status: 2 failures

Anil's Phone
Last sync: Yesterday
Status: Offline
```

This is valuable for identifying devices that have been offline for a long period.

---

# 112. Device Registration

Each installation should have a generated local device ID.

Example:

```text
deviceId:
DEVICE-8F73A...
```

This must not be used as the user's identity.

It is only for:

* Sync diagnostics
* Audit
* Device tracking
* Conflict information

---

# 113. Audit Example

```text
29 Aug 2026 12:32 PM

Ravi
Created Expense

Feed Purchase
₹2,500

Device:
Ravi Android

Sync:
Completed
```

---

# 114. Deleted Record Audit

When deleted:

```text
29 Aug 2026 01:15 PM

Ravi deleted:

Feed Purchase
₹2,500
```

Admin can restore.

---

# 115. Restore

Restore should create a synchronization operation:

```text
RESTORE
```

rather than simply changing local UI state.

---

# 116. Testing Strategy

Testing must heavily emphasize offline behavior.

## Authentication

* Google login
* Logout
* Disabled account
* Role assignment

## Expense

* Create
* Edit
* Delete
* Restore

## Offline

* Create while offline
* Edit while offline
* Delete while offline
* Capture receipt offline
* Restart app offline
* Sync after reconnection

## Sync

* Successful sync
* Timeout
* Server error
* Retry
* Duplicate request
* Partial failure
* App crash
* Connectivity interruption

## Receipt

* Camera
* Gallery
* Large image
* Offline receipt
* Drive upload
* Upload failure
* Duplicate prevention
* Drive change

## Multi-device

* Device A creates
* Device B receives
* Device A edits
* Device B edits
* Conflict
* Delete
* Restore

---

# 117. Critical Failure Scenarios

## Scenario 1 — Offline Expense

```text
Expense saved locally
Sync pending
```

Expected:

```text
No data loss
```

---

## Scenario 2 — Offline Receipt

```text
Receipt saved locally

driveFileId = NULL
```

Expected:

```text
Upload later
```

---

## Scenario 3 — Receipt Upload Failure

```text
Expense = SYNCED

Receipt = FAILED
```

Expected:

```text
Retry receipt only
```

---

## Scenario 4 — Drive Upload Succeeds, Metadata Fails

```text
Drive file exists
driveFileId saved
Metadata failed
```

Expected:

```text
Do not upload again
Retry metadata
```

---

## Scenario 5 — App Crashes

```text
App crashes
 ↓
Restart
 ↓
Read SQLite
 ↓
Read Sync Queue
 ↓
Resume
```

---

## Scenario 6 — Phone Restart While Offline

```text
Expense
Receipt
Sync queue
```

must all remain.

---

## Scenario 7 — Drive Account Changes

```text
Old receipts → Old Drive
New receipts → New Drive
```

---

## Scenario 8 — New Drive Validation Fails

```text
Old Drive remains active
```

---

# 118. Development Phases

## Phase 1 — Foundation

* Flutter project
* Material 3
* Architecture
* SQLite/Drift
* Authentication
* Navigation
* User
* Role

## Phase 2 — Expenses

* Expense list
* Add
* Edit
* Soft delete
* Receipt camera
* Gallery
* Local receipt storage

## Phase 3 — Sync Engine

* Persistent queue
* UUIDs
* Idempotency
* Retry
* Sync status
* Background synchronization

## Phase 4 — Remote Metadata

* Remote provider abstraction
* Remote API
* Multi-device synchronization
* Incremental sync
* Conflict handling

## Phase 5 — Google Drive

* OAuth
* Upload
* Drive File ID
* Retry
* Duplicate prevention

## Phase 6 — Receipt Administration

* Current Drive
* Change Drive
* Test Drive
* Configuration versioning
* Configuration synchronization
* Drive history

## Phase 7 — Stock

* Stock items
* Transactions
* Balances

## Phase 8 — Livestock

* Categories
* Counts
* History

## Phase 9 — Medicine

* Treatments
* Vaccinations
* Reminders

## Phase 10 — Administration

* Users
* Roles
* Permissions
* Audit
* Deleted records
* Sync health

## Phase 11 — Reports

* Monthly reports
* Expense reports
* Stock reports
* Medicine reports
* Livestock reports
* Export

## Phase 12 — UI Polish

* Animations
* Dashboard
* Dark mode
* Empty states
* Error states
* Performance

## Phase 13 — Production

* Multi-device testing
* Offline testing
* Drive failure testing
* Sync recovery
* Database migration
* Security testing
* Recovery testing

---

# 119. Non-Negotiable Requirements

The implementation must include:

* [ ] Android application
* [ ] Flutter
* [ ] Material 3
* [ ] Modern attractive UI
* [ ] Responsive UI
* [ ] Local-first architecture
* [ ] SQLite/Drift
* [ ] Offline operation
* [ ] Persistent sync queue
* [ ] UUID-based IDs
* [ ] Idempotent synchronization
* [ ] Retry mechanism
* [ ] Background synchronization
* [ ] Multi-device synchronization
* [ ] Google authentication
* [ ] Role-based access
* [ ] Admin mode
* [ ] Worker mode
* [ ] Expense management
* [ ] Stock management
* [ ] Livestock counting
* [ ] Medicine management
* [ ] Camera receipt capture
* [ ] Gallery receipt selection
* [ ] Google Drive receipt storage
* [ ] Nullable Drive File ID
* [ ] Independent receipt synchronization
* [ ] Receipt retry
* [ ] Admin-controlled receipt Drive
* [ ] Receipt Drive configuration synchronization
* [ ] Historical Drive configurations
* [ ] Old receipt preservation
* [ ] Per-record synchronization status
* [ ] Soft delete
* [ ] Deleted-record visibility
* [ ] Audit logging
* [ ] Reports
* [ ] Secure credential handling
* [ ] Database migrations
* [ ] Replaceable remote backend
* [ ] Minimal infrastructure cost

---

# 120. Architecture Decision Summary

| Decision            | Choice                         |
| ------------------- | ------------------------------ |
| Mobile              | Flutter                        |
| Database            | SQLite + Drift                 |
| Architecture        | Offline-first                  |
| IDs                 | Local UUID                     |
| Remote metadata     | API/provider abstraction       |
| Receipt storage     | Google Drive                   |
| Receipt primary ID  | Local UUID                     |
| Drive reference     | Nullable `driveFileId`         |
| Sync                | Persistent queue               |
| Retry               | Automatic + manual             |
| Delete              | Soft delete                    |
| Conflict            | Version + timestamp            |
| Audit               | Audit log                      |
| Roles               | RBAC                           |
| Drive changes       | Versioned configuration        |
| Historical receipts | Preserve original Drive        |
| UI                  | Material 3                     |
| Reports             | Local/remote synchronized data |
| Background work     | Android-compatible scheduler   |

---

# 121. Final Architecture

```text
                              USER
                                │
                                ▼
                    ┌─────────────────────┐
                    │   Flutter Android   │
                    │         App         │
                    └──────────┬──────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │ UI / ViewModels     │
                    └──────────┬──────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │ Repositories        │
                    │ Application Services │
                    └──────────┬──────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │ SQLite / Drift      │
                    │ Local Data Store    │
                    └──────────┬──────────┘
                               │
                    ┌──────────┴──────────┐
                    │                     │
                    ▼                     ▼
              Business Data          Sync Queue
                                          │
                                          ▼
                                  ┌───────────────┐
                                  │  Sync Engine  │
                                  └───────┬───────┘
                                          │
                         ┌────────────────┴────────────────┐
                         │                                 │
                         ▼                                 ▼
                 ┌───────────────┐                 ┌───────────────┐
                 │ Remote Data   │                 │ Google Drive  │
                 │ API / Store   │                 │   Receipts    │
                 └───────────────┘                 └───────────────┘
```

---

# 122. The Most Important Offline Receipt Rule

The implementation team must understand this rule clearly:

```text
LOCAL UUID
    =
Permanent identity of the receipt
```

while:

```text
DRIVE FILE ID
    =
Remote reference to the uploaded file
```

Therefore:

```text
Offline:

receiptId       = REC-123
driveFileId     = NULL
localPath       = /receipts/REC-123.jpg
uploadStatus    = PENDING
```

Later:

```text
Online:

receiptId       = REC-123
driveFileId     = 1ABCXYZ...
localPath       = /receipts/REC-123.jpg
uploadStatus    = SYNCED
```

The `receiptId` never changes.

---

# 123. Complete Example

Suppose Ravi is offline.

He enters:

```text
Expense:
Feed Purchase

Amount:
₹2,500

Receipt:
Photo
```

The application creates:

```text
Expense UUID:
EXP-550E...

Receipt UUID:
REC-6BA7...
```

SQLite:

```text
Expense
--------------------------------
id = EXP-550E...
amount = 2500
reason = Feed Purchase
syncStatus = PENDING
```

Receipt:

```text
Receipt
--------------------------------
id = REC-6BA7...
expenseId = EXP-550E...
localPath = /receipts/REC-6BA7.jpg
driveFileId = NULL
uploadStatus = PENDING
```

Sync queue:

```text
CREATE EXPENSE
PENDING

UPLOAD RECEIPT
PENDING
```

The UI immediately shows:

```text
Feed Purchase

₹2,500

Data      ⟳ Pending
Receipt   ⟳ Pending
```

Ravi closes the application.

Later:

```text
Internet available
```

Sync engine:

```text
CREATE EXPENSE
       ↓
SUCCESS
       ↓
UPLOAD RECEIPT
       ↓
SUCCESS
       ↓
Google Drive:
1A2B3C...
       ↓
Save driveFileId
       ↓
SYNC RECEIPT METADATA
       ↓
SUCCESS
```

Final state:

```text
Expense:
SYNCED

Receipt:
SYNCED

driveFileId:
1A2B3C...
```

Another phone later synchronizes:

```text
Phone B
   ↓
Downloads expense
   ↓
Downloads receipt metadata
   ↓
User opens receipt
   ↓
Drive file downloaded/viewed
```

---

# 124. Definition of Done

The architecture is considered successfully implemented only when this complete scenario works:

```text
Phone A
    ↓
No Internet
    ↓
User adds expense
    ↓
Captures receipt
    ↓
Expense appears immediately
    ↓
Receipt appears immediately
    ↓
Data = Pending
Receipt = Pending
    ↓
Application is closed
    ↓
Phone restarts
    ↓
Still offline
    ↓
Expense still exists
    ↓
Receipt still exists
    ↓
Sync queue still exists
    ↓
Internet returns
    ↓
Expense synchronizes
    ↓
Receipt uploads
    ↓
Google Drive returns driveFileId
    ↓
driveFileId saved locally
    ↓
Remote metadata updated
    ↓
Status = Synced
    ↓
Phone B synchronizes
    ↓
Phone B sees expense
    ↓
Phone B can access receipt
    ↓
Admin changes receipt Drive
    ↓
Configuration synchronizes
    ↓
New receipts use new Drive
    ↓
Old receipts remain accessible
    ↓
All important actions remain auditable
```

---

# 125. Final Architectural Philosophy

The application should be built around one fundamental principle:

> **The user should never have to care whether the internet is available.**

The application should allow:

```text
Create
Edit
Delete
View
Capture Receipt
Update Stock
Update Livestock
Add Medicine
```

without waiting for the cloud.

The cloud synchronization should happen quietly in the background.

The application should tell the user:

```text
✓ Saved
⟳ Syncing
✓ Synced
⚠ Needs Attention
```

rather than exposing network complexity.

The final architecture is therefore:

```text
                    LOCAL-FIRST
                         │
              ┌──────────┴──────────┐
              │                     │
          SQLite                Sync Queue
              │                     │
              │                     ▼
              │                Sync Engine
              │                 /       \
              │                /         \
              ▼               ▼           ▼
           Fast UI       Remote Data    Google Drive
                                        Receipts
```

This architecture provides the desired **WhatsApp-like responsiveness**, while still giving the organization remote backup, multi-device synchronization, receipt storage, retry handling, auditability, and the ability to change the receipt-storage Drive later without redesigning the application.
