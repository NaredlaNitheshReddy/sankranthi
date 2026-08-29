package com.sankranthi.ledger.data.repo.demo

import com.sankranthi.ledger.data.model.AccessStatus
import com.sankranthi.ledger.data.model.Permission
import com.sankranthi.ledger.data.model.Profile
import com.sankranthi.ledger.data.model.Role
import com.sankranthi.ledger.util.Dates
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    }

    companion object {
        /** Signing in as this address lands you in the admin view. */
        const val DEMO_ADMIN_EMAIL = "admin@demo.local"

        /** An approved member holding only the livestock permission. */
        const val DEMO_MEMBER_EMAIL = "ravi@demo.local"
    }
}
