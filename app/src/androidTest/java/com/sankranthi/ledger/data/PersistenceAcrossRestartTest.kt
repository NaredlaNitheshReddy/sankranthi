package com.sankranthi.ledger.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sankranthi.ledger.data.local.AppDatabase
import com.sankranthi.ledger.data.local.entity.SyncStatus
import com.sankranthi.ledger.data.model.LivestockEntry
import com.sankranthi.ledger.data.model.TradeKind
import com.sankranthi.ledger.data.repository.Actor
import com.sankranthi.ledger.data.repository.LedgerRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §30 Phase 2 acceptance criteria, and §20's "local data must never silently
 * disappear".
 *
 * Uses a real on-disk database rather than an in-memory one, and closes and
 * reopens it — an in-memory database would pass this test while proving nothing
 * about durability.
 */
@RunWith(AndroidJUnit4::class)
class PersistenceAcrossRestartTest {

    private val dbName = "restart-test.db"
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actor = Actor("ravi@example.com", "Ravi Kumar")

    private fun openDatabase(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()

    @Before
    fun clean() {
        context.deleteDatabase(dbName)
    }

    @After
    fun tidy() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun aRecordCreatedOfflineSurvivesTheProcessBeingRestarted() = runTest {
        val id: String
        openDatabase().let { db ->
            id = LedgerRepository(db).saveLivestock(
                LivestockEntry(
                    id = "",
                    kind = TradeKind.SELL,
                    animal = "Goat",
                    headCount = 5,
                    amountMinor = 52_500_00L,
                    occurredOn = "2026-08-25",
                ),
                actor,
            )
            db.close() // stands in for the app being killed or the phone rebooting
        }

        openDatabase().let { db ->
            val repository = LedgerRepository(db)

            val rows = repository.observeLivestock().first()
            assertEquals(1, rows.size)
            assertEquals(id, rows.first().id)
            assertEquals(52_500_00L, rows.first().amountMinor)

            // The outbox must survive too, or the record would be stranded on
            // this device forever.
            assertEquals(1, repository.observePendingCount().first())
            assertEquals(SyncStatus.PENDING, db.livestockDao().byId(id)!!.sync.syncStatus)

            db.close()
        }
    }

    @Test
    fun theSyncCursorSurvivesRestartAndNeverGoesBackwards() = runTest {
        openDatabase().let { db ->
            db.syncStateDao().advanceSeq(newSeq = 120, at = 1L)
            db.close()
        }

        openDatabase().let { db ->
            assertEquals(120L, db.syncStateDao().get()!!.lastSeq)

            // A replayed or out-of-order response must not rewind the cursor,
            // which would re-download and re-apply changes.
            db.syncStateDao().advanceSeq(newSeq = 90, at = 2L)
            assertEquals(120L, db.syncStateDao().get()!!.lastSeq)

            db.syncStateDao().advanceSeq(newSeq = 130, at = 3L)
            assertEquals(130L, db.syncStateDao().get()!!.lastSeq)

            db.close()
        }
    }

    @Test
    fun manyOfflineRecordsAreAllRetained() = runTest {
        openDatabase().let { db ->
            val repository = LedgerRepository(db)
            repeat(25) { index ->
                repository.saveLivestock(
                    LivestockEntry(
                        id = "",
                        kind = if (index % 2 == 0) TradeKind.BUY else TradeKind.SELL,
                        animal = "Goat",
                        headCount = index + 1,
                        amountMinor = (index + 1) * 1_000_00L,
                        occurredOn = "2026-08-01",
                    ),
                    actor,
                )
            }
            db.close()
        }

        openDatabase().let { db ->
            val repository = LedgerRepository(db)
            assertEquals(25, repository.observeLivestock().first().size)
            assertEquals(25, repository.observePendingCount().first())
            assertTrue(db.livestockDao().unsynced().size == 25)
            db.close()
        }
    }
}
