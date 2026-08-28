package com.example.sankranthi.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sankranthi.data.ServiceLocator
import com.example.sankranthi.data.model.AccessStatus
import com.example.sankranthi.data.model.Permission
import com.example.sankranthi.data.model.Profile
import com.example.sankranthi.data.model.Role
import com.example.sankranthi.data.repo.MembersRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AdminUiState(
    val loading: Boolean = true,
    val pending: List<Profile> = emptyList(),
    val members: List<Profile> = emptyList(),
    val error: String? = null,
    val notice: String? = null,
)

/** Backs the admin panel: the approval queue and per-member permission grants. */
class AdminViewModel(
    private val members: MembersRepository = ServiceLocator.membersRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AdminUiState())
    val state: StateFlow<AdminUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                _state.value = _state.value.copy(
                    loading = false,
                    pending = members.pendingRequests(),
                    members = members.decidedMembers(),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "Could not load members.",
                )
            }
        }
    }

    /**
     * Admits a member and sets their starting rights in one go, so nobody is
     * left approved but unable to do anything.
     */
    fun approve(profile: Profile, permissions: Set<Permission>) {
        mutate("${profile.displayName} approved") {
            members.setStatus(profile.id, AccessStatus.APPROVED)
            members.setPermissions(profile.id, permissions)
        }
    }

    fun reject(profile: Profile) {
        mutate("${profile.displayName} rejected") {
            members.setStatus(profile.id, AccessStatus.REJECTED)
        }
    }

    /** Sends an approved member back to the queue without deleting their history. */
    fun revoke(profile: Profile) {
        mutate("${profile.displayName}'s access revoked") {
            members.setStatus(profile.id, AccessStatus.PENDING)
            members.setPermissions(profile.id, emptySet())
        }
    }

    fun togglePermission(profile: Profile, permission: Permission, granted: Boolean) {
        val next = profile.grantedPermissions.toMutableSet().apply {
            if (granted) add(permission) else remove(permission)
        }
        mutate(null) { members.setPermissions(profile.id, next) }
    }

    fun setRole(profile: Profile, role: Role) {
        mutate("${profile.displayName} is now ${role.label.lowercase()}") {
            members.setRole(profile.id, role)
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    private fun mutate(notice: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                _state.value = _state.value.copy(notice = notice)
                load()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "The change could not be saved.",
                )
            }
        }
    }
}
