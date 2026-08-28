package com.example.sankranthi.data.repo.demo

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DemoAuthRepository(private val backend: DemoBackend) : AuthRepository {

    private val _session = MutableStateFlow<SessionState>(SessionState.SignedOut)
    override val session: StateFlow<SessionState> = _session.asStateFlow()

    private var currentId: String? = null

    override suspend fun signInWithGoogle(idToken: String, rawNonce: String) {
        throw UnsupportedOperationException(
            "Google sign-in requires Supabase credentials in local.properties.",
        )
    }

    override suspend fun signInAsDemo(email: String) {
        val profile = backend.withState { signIn(email) }
        currentId = profile.id
        _session.value = SessionState.SignedIn(profile)
    }

    override suspend fun signOut() {
        currentId = null
        _session.value = SessionState.SignedOut
    }

    override suspend fun reloadProfile() {
        val id = currentId ?: return
        val profile = backend.withState { profileOrNull(id) }
        _session.value = if (profile != null) SessionState.SignedIn(profile) else SessionState.SignedOut
    }
}

class DemoMembersRepository(private val backend: DemoBackend) : MembersRepository {

    override suspend fun pendingRequests(): List<Profile> =
        backend.withState { allProfiles() }
            .filter { it.status == AccessStatus.PENDING }
            .sortedBy { it.email }

    override suspend fun decidedMembers(): List<Profile> =
        backend.withState { allProfiles() }
            .filter { it.status != AccessStatus.PENDING }
            .sortedWith(compareByDescending<Profile> { it.isAdmin }.thenBy { it.email })

    override suspend fun setStatus(userId: String, status: AccessStatus) {
        backend.withState {
            profileOrNull(userId)?.let { putProfile(it.copy(status = status)) }
        }
    }

    override suspend fun setPermissions(userId: String, permissions: Set<Permission>) {
        backend.withState {
            profileOrNull(userId)?.let {
                putProfile(it.copy(permissions = permissions.map(Permission::wire).sorted()))
            }
        }
    }

    override suspend fun setRole(userId: String, role: Role) {
        backend.withState {
            profileOrNull(userId)?.let { putProfile(it.copy(role = role)) }
        }
    }
}

class DemoLedgerRepository(private val backend: DemoBackend) : LedgerRepository {

    override suspend fun livestockEntries(): List<LivestockEntry> =
        backend.withState { livestockEntries() }

    override suspend fun saveLivestockEntry(entry: LivestockEntry): LivestockEntry =
        backend.withState { saveLivestock(entry) }

    override suspend fun deleteLivestockEntry(id: String) {
        backend.withState { deleteLivestock(id) }
    }

    override suspend fun expenses(): List<Expense> = backend.withState { expenseRows() }

    override suspend fun saveExpense(expense: Expense): Expense =
        backend.withState { saveExpense(expense) }

    override suspend fun deleteExpense(id: String) {
        backend.withState { deleteExpense(id) }
    }
}
