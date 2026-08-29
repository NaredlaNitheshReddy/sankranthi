package com.sankranthi.ledger.data.repo.supabase

import android.util.Log
import com.sankranthi.ledger.data.model.AccessStatus
import com.sankranthi.ledger.data.model.Permission
import com.sankranthi.ledger.data.model.Profile
import com.sankranthi.ledger.data.model.Role
import com.sankranthi.ledger.data.repo.AuthRepository
import com.sankranthi.ledger.data.repo.MembersRepository
import com.sankranthi.ledger.data.repo.SessionState
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable


private const val TAG = "SupabaseRepo"

private const val PROFILES = "profiles"

/**
 * Observes the Supabase session and pairs it with the caller's `profiles` row,
 * which is where the approval status and permission grants live.
 */
class SupabaseAuthRepository(
    private val client: SupabaseClient,
    scope: CoroutineScope,
) : AuthRepository {

    private val _session = MutableStateFlow<SessionState>(SessionState.Loading)
    override val session: StateFlow<SessionState> = _session.asStateFlow()

    init {
        scope.launch {
            client.auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> _session.value = loadSignedInState()
                    is SessionStatus.NotAuthenticated -> _session.value = SessionState.SignedOut
                    else -> _session.value = SessionState.Loading
                }
            }
        }
    }

    override suspend fun signInWithGoogle(idToken: String, rawNonce: String) {
        client.auth.signInWith(IDToken) {
            provider = Google
            this.idToken = idToken
            this.nonce = rawNonce
        }
    }

    override suspend fun signInAsDemo(email: String) {
        throw UnsupportedOperationException("Demo sign-in is only available without Supabase.")
    }

    override suspend fun signOut() {
        client.auth.signOut()
    }

    override suspend fun reloadProfile() {
        if (client.auth.currentSessionOrNull() == null) return
        _session.value = loadSignedInState()
    }

    /**
     * The row is created by a database trigger on signup. If it has not landed
     * yet the user is reported as pending rather than signed out, so a first-time
     * sign-in shows the waiting screen instead of bouncing back to login.
     */
    private suspend fun loadSignedInState(): SessionState {
        val user = client.auth.currentUserOrNull() ?: return SessionState.SignedOut
        val fallback = Profile(
            id = user.id,
            email = user.email.orEmpty(),
            status = AccessStatus.PENDING,
        )
        return try {
            val profile = client.from(PROFILES)
                .select(Columns.ALL) { filter { eq("id", user.id) } }
                .decodeSingleOrNull<Profile>()
            SessionState.SignedIn(profile ?: fallback)
        } catch (e: Exception) {
            Log.w(TAG, "Could not load profile for ${user.id}", e)
            SessionState.SignedIn(fallback)
        }
    }
}

class SupabaseMembersRepository(private val client: SupabaseClient) : MembersRepository {

    override suspend fun pendingRequests(): List<Profile> =
        client.from(PROFILES)
            .select(Columns.ALL) {
                filter { eq("status", AccessStatus.PENDING.wire) }
                order("requested_at", Order.ASCENDING)
            }
            .decodeList()

    override suspend fun decidedMembers(): List<Profile> =
        client.from(PROFILES)
            .select(Columns.ALL) {
                filter { neq("status", AccessStatus.PENDING.wire) }
                order("email", Order.ASCENDING)
            }
            .decodeList()

    override suspend fun setStatus(userId: String, status: AccessStatus) {
        client.from(PROFILES).update(mapOf("status" to status.wire)) {
            filter { eq("id", userId) }
        }
    }

    override suspend fun setPermissions(userId: String, permissions: Set<Permission>) {
        client.from(PROFILES)
            .update(mapOf("permissions" to permissions.map(Permission::wire).sorted())) {
                filter { eq("id", userId) }
            }
    }

    override suspend fun setRole(userId: String, role: Role) {
        client.from(PROFILES).update(mapOf("role" to role.wire)) {
            filter { eq("id", userId) }
        }
    }
}
