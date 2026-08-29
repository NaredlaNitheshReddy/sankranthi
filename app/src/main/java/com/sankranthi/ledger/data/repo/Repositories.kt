package com.sankranthi.ledger.data.repo

import com.sankranthi.ledger.data.model.Permission
import com.sankranthi.ledger.data.model.Profile
import com.sankranthi.ledger.data.model.AccessStatus
import com.sankranthi.ledger.data.model.Role
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
