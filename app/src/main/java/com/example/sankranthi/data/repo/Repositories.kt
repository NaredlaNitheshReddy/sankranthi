package com.example.sankranthi.data.repo

import com.example.sankranthi.data.model.Expense
import com.example.sankranthi.data.model.LivestockEntry
import com.example.sankranthi.data.model.Permission
import com.example.sankranthi.data.model.Profile
import com.example.sankranthi.data.model.AccessStatus
import com.example.sankranthi.data.model.Role
import kotlinx.coroutines.flow.StateFlow

/** Who is using the app right now. */
sealed interface SessionState {
    /** Restoring a stored session on launch. */
    data object Loading : SessionState

    data object SignedOut : SessionState

    /**
     * Signed in with Google. [profile] carries the approval status, so a member
     * still waiting on an admin is *signed in but not admitted*.
     */
    data class SignedIn(val profile: Profile) : SessionState
}

interface AuthRepository {
    val session: StateFlow<SessionState>

    /** Exchanges a Google ID token for a Supabase session. */
    suspend fun signInWithGoogle(idToken: String, rawNonce: String)

    /** Demo backend only: signs in without touching Google. */
    suspend fun signInAsDemo(email: String)

    suspend fun signOut()

    /** Re-reads the current user's profile, picking up admin decisions. */
    suspend fun reloadProfile()
}

/** Admin-side view of everyone who has ever signed in. */
interface MembersRepository {
    suspend fun pendingRequests(): List<Profile>

    /** Everyone who is not pending — approved members and rejected accounts. */
    suspend fun decidedMembers(): List<Profile>

    suspend fun setStatus(userId: String, status: AccessStatus)

    suspend fun setPermissions(userId: String, permissions: Set<Permission>)

    suspend fun setRole(userId: String, role: Role)
}

/** The books: livestock trades and running expenses. */
interface LedgerRepository {
    suspend fun livestockEntries(): List<LivestockEntry>

    suspend fun saveLivestockEntry(entry: LivestockEntry): LivestockEntry

    suspend fun deleteLivestockEntry(id: String)

    suspend fun expenses(): List<Expense>

    suspend fun saveExpense(expense: Expense): Expense

    suspend fun deleteExpense(id: String)
}
