package com.sankranthi.ledger.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.sankranthi.ledger.data.local.dao.ExpenseDao
import com.sankranthi.ledger.data.local.dao.LivestockDao
import com.sankranthi.ledger.data.local.dao.PendingOperationDao
import com.sankranthi.ledger.data.local.dao.ReceiptDao
import com.sankranthi.ledger.data.local.dao.SyncStateDao
import com.sankranthi.ledger.data.local.entity.ExpenseEntity
import com.sankranthi.ledger.data.local.entity.LivestockEntity
import com.sankranthi.ledger.data.local.entity.PendingOperationEntity
import com.sankranthi.ledger.data.local.entity.ReceiptEntity
import com.sankranthi.ledger.data.local.entity.SyncStateEntity

/**
 * The device-local database. One per phone — it is a cache plus an outbox, never
 * a shared file. The `.db` must never be placed in Drive and synced between
 * devices (§31.4); the spreadsheet is the shared source of truth.
 *
 * `exportSchema = true` writes the schema to `app/schemas/`, which is what lets
 * migration tests assert that an upgrade preserves data. Those files are meant to
 * be committed.
 */
@Database(
    entities = [
        LivestockEntity::class,
        ExpenseEntity::class,
        ReceiptEntity::class,
        PendingOperationEntity::class,
        SyncStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun livestockDao(): LivestockDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun receiptDao(): ReceiptDao
    abstract fun pendingOperationDao(): PendingOperationDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        private const val NAME = "sankranthi.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                // No fallbackToDestructiveMigration: losing a partner's unsynced
                // records on an app update would violate §20. Every schema change
                // must ship a real migration.
                .build()
    }
}
