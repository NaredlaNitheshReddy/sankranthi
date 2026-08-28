package com.example.sankranthi.data.repo.demo

import com.example.sankranthi.data.model.AccessStatus
import com.example.sankranthi.data.model.Expense
import com.example.sankranthi.data.model.ExpenseCategory
import com.example.sankranthi.data.model.LivestockEntry
import com.example.sankranthi.data.model.Permission
import com.example.sankranthi.data.model.Profile
import com.example.sankranthi.data.model.Role
import com.example.sankranthi.data.model.TradeKind
import com.example.sankranthi.util.Dates
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.UUID

/**
 * In-memory stand-in for Supabase, used when `local.properties` carries no
 * Supabase credentials. It enforces the same approval rules as the real backend
 * so the whole flow — request access, admin approves, admin grants permissions —
 * is exercisable without any cloud setup. Nothing survives process death.
 */
class DemoBackend {

    private val mutex = Mutex()

    private val profiles = linkedMapOf<String, Profile>()
    private val livestock = mutableListOf<LivestockEntry>()
    private val expenses = mutableListOf<Expense>()

    init {
        seed()
    }

    /** Runs [block] under the backend lock. All mutation goes through here. */
    suspend fun <T> withState(block: DemoBackend.() -> T): T = mutex.withLock { block() }

    fun profileOrNull(id: String): Profile? = profiles[id]

    fun allProfiles(): List<Profile> = profiles.values.toList()

    fun putProfile(profile: Profile) {
        profiles[profile.id] = profile
    }

    /**
     * Signing in with an unseen address creates a pending request, mirroring the
     * `handle_new_user` trigger in the real schema.
     */
    fun signIn(email: String): Profile {
        val existing = profiles.values.firstOrNull { it.email.equals(email, ignoreCase = true) }
        if (existing != null) return existing
        val created = Profile(
            id = UUID.randomUUID().toString(),
            email = email,
            fullName = email.substringBefore("@"),
            role = Role.MEMBER,
            status = AccessStatus.PENDING,
            requestedAt = Dates.today(),
        )
        profiles[created.id] = created
        return created
    }

    fun livestockEntries(): List<LivestockEntry> = livestock.toList()

    fun saveLivestock(entry: LivestockEntry): LivestockEntry {
        val index = livestock.indexOfFirst { it.id == entry.id }
        if (index >= 0) {
            livestock[index] = entry
            return entry
        }
        val created = entry.copy(id = UUID.randomUUID().toString())
        livestock += created
        return created
    }

    fun deleteLivestock(id: String) {
        livestock.removeAll { it.id == id }
    }

    fun expenseRows(): List<Expense> = expenses.toList()

    fun saveExpense(expense: Expense): Expense {
        val index = expenses.indexOfFirst { it.id == expense.id }
        if (index >= 0) {
            expenses[index] = expense
            return expense
        }
        val created = expense.copy(id = UUID.randomUUID().toString())
        expenses += created
        return created
    }

    fun deleteExpense(id: String) {
        expenses.removeAll { it.id == id }
    }

    private fun seed() {
        val admin = Profile(
            id = UUID.randomUUID().toString(),
            email = DEMO_ADMIN_EMAIL,
            fullName = "Demo Admin",
            role = Role.ADMIN,
            status = AccessStatus.APPROVED,
            requestedAt = Dates.today(),
        )
        val member = Profile(
            id = UUID.randomUUID().toString(),
            email = DEMO_MEMBER_EMAIL,
            fullName = "Ravi Kumar",
            role = Role.MEMBER,
            status = AccessStatus.APPROVED,
            permissions = listOf(Permission.EDIT_LIVESTOCK.wire),
            requestedAt = Dates.today(),
        )
        val waiting = Profile(
            id = UUID.randomUUID().toString(),
            email = "lakshmi@demo.local",
            fullName = "Lakshmi Devi",
            role = Role.MEMBER,
            status = AccessStatus.PENDING,
            requestedAt = Dates.today(),
        )
        listOf(admin, member, waiting).forEach { profiles[it.id] = it }

        livestock += LivestockEntry(
            id = UUID.randomUUID().toString(),
            kind = TradeKind.BUY,
            animal = "Goat",
            headCount = 12,
            amountMinor = 96_000_00L,
            counterparty = "Kurnool mandi",
            occurredOn = LocalDate.now().minusDays(21).toString(),
            notes = "Mixed batch: 8 does, 4 kids.",
            createdBy = admin.id,
            createdByName = admin.displayName,
        )
        livestock += LivestockEntry(
            id = UUID.randomUUID().toString(),
            kind = TradeKind.SELL,
            animal = "Goat",
            headCount = 5,
            amountMinor = 52_500_00L,
            counterparty = "Sri Balaji Traders",
            occurredOn = LocalDate.now().minusDays(4).toString(),
            createdBy = member.id,
            createdByName = member.displayName,
        )

        expenses += Expense(
            id = UUID.randomUUID().toString(),
            category = ExpenseCategory.FEED,
            amountMinor = 42_500_00L,
            description = "Maize and groundnut cake, one month",
            occurredOn = LocalDate.now().minusDays(10).toString(),
            createdBy = admin.id,
            createdByName = admin.displayName,
        )
        expenses += Expense(
            id = UUID.randomUUID().toString(),
            category = ExpenseCategory.VETERINARY,
            amountMinor = 8_200_00L,
            description = "Deworming and FMD vaccination",
            occurredOn = LocalDate.now().minusDays(6).toString(),
            createdBy = admin.id,
            createdByName = admin.displayName,
        )
    }

    companion object {
        /** Signing in as this address lands you in the admin view. */
        const val DEMO_ADMIN_EMAIL = "admin@demo.local"

        /** An approved member holding only the livestock permission. */
        const val DEMO_MEMBER_EMAIL = "ravi@demo.local"
    }
}
