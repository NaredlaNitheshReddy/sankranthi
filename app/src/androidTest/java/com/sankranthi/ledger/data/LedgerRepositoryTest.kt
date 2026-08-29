package com.sankranthi.ledger.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sankranthi.ledger.data.local.AppDatabase
import com.sankranthi.ledger.data.local.entity.PendingOperationEntity
import com.sankranthi.ledger.data.local.entity.SyncStatus
import com.sankranthi.ledger.data.model.Expense
import com.sankranthi.ledger.data.model.ExpenseCategory
import com.sankranthi.ledger.data.model.LivestockEntry
import com.sankranthi.ledger.data.model.TradeKind
import com.sankranthi.ledger.data.repository.Actor
import com.sankranthi.ledger.data.repository.LedgerRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 2 acceptance: a record created offline is durable and is queued for
 * upload, and nothing about the write path touches the network.
 */
@RunWith(AndroidJUnit4::class)
class LedgerRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: LedgerRepository

    private val actor = Actor(email = "ravi@example.com", displayName = "Ravi Kumar")

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        repository = LedgerRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun trade(id: String = "", amountMinor: Long = 96_000_00L) = LivestockEntry(
        id = id,
        kind = TradeKind.BUY,
        animal = "Goat",
        headCount = 12,
        amountMinor = amountMinor,
        counterparty = "Kurnool mandi",
        occurredOn = "2026-08-01",
    )

    private fun expense(id: String = "", amountMinor: Long = 42_500_00L) = Expense(
        id = id,
        category = ExpenseCategory.FEED,
        amountMinor = amountMinor,
        description = "Maize",
        occurredOn = "2026-08-02",
    )

    @Test
    fun savingALivestockEntry_makesItReadableImmediately() = runTest {
        repository.saveLivestock(trade(), actor)

        val rows = repository.observeLivestock().first()
        assertEquals(1, rows.size)
        assertEquals("Goat", rows.first().animal)
        assertEquals(96_000_00L, rows.first().amountMinor)
    }

    @Test
    fun savingMintsAUuid_soTwoOfflineDevicesCannotCollide() = runTest {
        val first = repository.saveLivestock(trade(), actor)
        val second = repository.saveLivestock(trade(amountMinor = 1L), actor)

        assertTrue(first.isNotBlank())
        assertTrue(second.isNotBlank())
        assertTrue("ids must differ", first != second)
    }

    @Test
    fun aNewRecordIsPendingAndQueuedForUpload() = runTest {
        val id = repository.saveLivestock(trade(), actor)

        val stored = database.livestockDao().byId(id)
        assertNotNull(stored)
        assertEquals(SyncStatus.PENDING, stored!!.sync.syncStatus)
        assertEquals(0, stored.sync.version)
        assertNull("serverSeq is server-owned", stored.sync.serverSeq)

        assertEquals(1, repository.observePendingCount().first())
    }

    @Test
    fun theRecordIsAttributedToTheSignedInUser() = runTest {
        val id = repository.saveLivestock(trade(), actor)

        val stored = database.livestockDao().byId(id)!!
        assertEquals("ravi@example.com", stored.sync.createdBy)
        assertEquals("Ravi Kumar", stored.sync.createdByName)
    }

    @Test
    fun editingTheSameRecordThreeTimesQueuesOnlyOneOperation() = runTest {
        val id = repository.saveLivestock(trade(), actor)
        repository.saveLivestock(trade(id = id, amountMinor = 2L), actor)
        repository.saveLivestock(trade(id = id, amountMinor = 3L), actor)

        // Coalesced: more than one operation would give each the same stale
        // baseVersion, so the second upload would conflict with our own first.
        val queued = database.pendingOperationDao().forEntity(
            PendingOperationEntity.TYPE_LIVESTOCK,
            id,
        )
        assertEquals(1, queued.size)
        assertEquals(1, repository.observePendingCount().first())

        // The final edit is what is stored, and therefore what will be uploaded.
        assertEquals(3L, database.livestockDao().byId(id)!!.amountMinor)
    }

    @Test
    fun editingDoesNotResetServerOwnedVersion() = runTest {
        val id = repository.saveLivestock(trade(), actor)

        // Simulate the gateway having accepted version 4.
        val accepted = database.livestockDao().byId(id)!!
        database.livestockDao().upsert(
            accepted.copy(
                sync = accepted.sync.copy(
                    version = 4,
                    serverSeq = 77,
                    syncStatus = SyncStatus.SYNCED,
                ),
            ),
        )

        repository.saveLivestock(trade(id = id, amountMinor = 5L), actor)

        val edited = database.livestockDao().byId(id)!!
        assertEquals("version must survive a local edit", 4, edited.sync.version)
        assertEquals(77L, edited.sync.serverSeq)
        assertEquals(SyncStatus.PENDING, edited.sync.syncStatus)
    }

    @Test
    fun deletingHidesTheRowButKeepsTheTombstoneQueued() = runTest {
        val id = repository.saveLivestock(trade(), actor)

        repository.deleteLivestock(id, actor)

        assertTrue(repository.observeLivestock().first().isEmpty())

        val tombstone = database.livestockDao().byId(id)
        assertNotNull("soft delete must keep the row so it can propagate", tombstone)
        assertTrue(tombstone!!.sync.deleted)
        assertEquals(SyncStatus.PENDING, tombstone.sync.syncStatus)
    }

    @Test
    fun deletingAnUnknownIdIsANoOp() = runTest {
        repository.deleteLivestock("does-not-exist", actor)

        assertEquals(0, repository.observePendingCount().first())
    }

    @Test
    fun expensesFollowTheSamePath() = runTest {
        val id = repository.saveExpense(expense(), actor)

        assertEquals(1, repository.observeExpenses().first().size)
        val stored = database.expenseDao().byId(id)!!
        assertEquals(SyncStatus.PENDING, stored.sync.syncStatus)
        assertEquals(ExpenseCategory.FEED, stored.category)

        repository.deleteExpense(id, actor)
        assertTrue(repository.observeExpenses().first().isEmpty())
    }

    @Test
    fun livestockAndExpenseQueuesAreIndependent() = runTest {
        val livestockId = repository.saveLivestock(trade(), actor)
        val expenseId = repository.saveExpense(expense(), actor)

        assertEquals(2, repository.observePendingCount().first())
        assertEquals(
            1,
            database.pendingOperationDao()
                .forEntity(PendingOperationEntity.TYPE_LIVESTOCK, livestockId).size,
        )
        assertEquals(
            1,
            database.pendingOperationDao()
                .forEntity(PendingOperationEntity.TYPE_EXPENSE, expenseId).size,
        )
    }

    @Test
    fun theDomainModelReportsPendingSyncSoTheUiCanShowIt() = runTest {
        val id = repository.saveLivestock(trade(), actor)
        assertTrue(repository.observeLivestock().first().first().pendingSync)

        val row = database.livestockDao().byId(id)!!
        database.livestockDao().upsert(
            row.copy(sync = row.sync.copy(syncStatus = SyncStatus.SYNCED)),
        )

        assertFalse(repository.observeLivestock().first().first().pendingSync)
    }

    @Test
    fun tombstonesArePurgedOnlyOnceSyncedAndOnlyUpToTheCursor() = runTest {
        val id = repository.saveLivestock(trade(), actor)
        repository.deleteLivestock(id, actor)

        // Still PENDING: purging now would lose the deletion before other
        // devices ever heard about it.
        assertEquals(0, database.livestockDao().purgeTombstones(upToSeq = 100))
        assertNotNull(database.livestockDao().byId(id))

        val row = database.livestockDao().byId(id)!!
        database.livestockDao().upsert(
            row.copy(sync = row.sync.copy(syncStatus = SyncStatus.SYNCED, serverSeq = 50)),
        )

        // Cursor has not reached it yet.
        assertEquals(0, database.livestockDao().purgeTombstones(upToSeq = 49))
        assertEquals(1, database.livestockDao().purgeTombstones(upToSeq = 50))
        assertNull(database.livestockDao().byId(id))
    }
}
