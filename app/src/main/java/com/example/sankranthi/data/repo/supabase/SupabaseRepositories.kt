package com.example.sankranthi.data.repo.supabase

import android.util.Log
import com.example.sankranthi.data.model.AccessStatus
import com.example.sankranthi.data.model.Expense
import com.example.sankranthi.data.model.LivestockEntry
import com.example.sankranthi.data.model.Permission
import com.example.sankranthi.data.model.Profile
import com.example.sankranthi.data.model.Role
import com.example.sankranthi.data.repo.AuthRepository
import com.example.sankranthi.data.repo.LedgerRepository
import com.example.sankranthi.data.repo.MembersRepository
import com.example.sankranthi.data.repo.SessionState
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


private const val TAG = "SupabaseRepo"

private const val PROFILES = "profiles"
private const val LIVESTOCK = "livestock_entries"
private const val EXPENSES = "expenses"

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

class SupabaseLedgerRepository(private val client: SupabaseClient) : LedgerRepository {

    override suspend fun livestockEntries(): List<LivestockEntry> =
        client.from(LIVESTOCK)
            .select(Columns.ALL) { order("occurred_on", Order.DESCENDING) }
            .decodeList()

    override suspend fun saveLivestockEntry(entry: LivestockEntry): LivestockEntry {
        val payload = LivestockPayload(
            kind = entry.kind.wire,
            animal = entry.animal,
            headCount = entry.headCount,
            amountMinor = entry.amountMinor,
            counterparty = entry.counterparty,
            occurredOn = entry.occurredOn,
            notes = entry.notes,
        )
        return if (entry.id.isBlank()) {
            client.from(LIVESTOCK).insert(payload) { select(Columns.ALL) }.decodeSingle()
        } else {
            client.from(LIVESTOCK)
                .update(payload) {
                    filter { eq("id", entry.id) }
                    select(Columns.ALL)
                }
                .decodeSingle()
        }
    }

    override suspend fun deleteLivestockEntry(id: String) {
        client.from(LIVESTOCK).delete { filter { eq("id", id) } }
    }

    override suspend fun expenses(): List<Expense> =
        client.from(EXPENSES)
            .select(Columns.ALL) { order("occurred_on", Order.DESCENDING) }
            .decodeList()

    override suspend fun saveExpense(expense: Expense): Expense {
        val payload = ExpensePayload(
            category = expense.category.wire,
            amountMinor = expense.amountMinor,
            description = expense.description,
            occurredOn = expense.occurredOn,
        )
        return if (expense.id.isBlank()) {
            client.from(EXPENSES).insert(payload) { select(Columns.ALL) }.decodeSingle()
        } else {
            client.from(EXPENSES)
                .update(payload) {
                    filter { eq("id", expense.id) }
                    select(Columns.ALL)
                }
                .decodeSingle()
        }
    }

    override suspend fun deleteExpense(id: String) {
        client.from(EXPENSES).delete { filter { eq("id", id) } }
    }
}

/**
 * Write shapes deliberately omit `id`, `created_by` and `created_by_name`: the
 * database owns those (defaults and triggers), and letting the client set them
 * would let a member attribute an entry to someone else.
 */
@Serializable
private data class LivestockPayload(
    val kind: String,
    val animal: String,
    @SerialName("head_count") val headCount: Int,
    @SerialName("amount_minor") val amountMinor: Long,
    val counterparty: String?,
    @SerialName("occurred_on") val occurredOn: String,
    val notes: String?,
)

@Serializable
private data class ExpensePayload(
    val category: String,
    @SerialName("amount_minor") val amountMinor: Long,
    val description: String?,
    @SerialName("occurred_on") val occurredOn: String,
)
