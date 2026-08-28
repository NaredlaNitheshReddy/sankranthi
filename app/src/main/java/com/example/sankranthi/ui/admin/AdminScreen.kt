package com.example.sankranthi.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.sankranthi.data.model.AccessStatus
import com.example.sankranthi.data.model.Permission
import com.example.sankranthi.data.model.Profile
import com.example.sankranthi.data.model.Role
import com.example.sankranthi.ui.common.EmptyState
import com.example.sankranthi.ui.common.ErrorBanner
import com.example.sankranthi.ui.common.LoadingRow
import com.example.sankranthi.ui.theme.SankranthiTheme
import com.example.sankranthi.util.Dates

private enum class AdminTab(val label: String) {
    PENDING("Pending requests"),
    MEMBERS("Members"),
}

/**
 * Admin-only panel. Two tabs: the approval queue, and the roster where existing
 * members' edit rights are granted and taken away.
 */
@Composable
fun AdminScreen(
    currentUser: Profile,
    state: AdminUiState,
    onApprove: (Profile, Set<Permission>) -> Unit,
    onReject: (Profile) -> Unit,
    onRevoke: (Profile) -> Unit,
    onTogglePermission: (Profile, Permission, Boolean) -> Unit,
    onSetRole: (Profile, Role) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(AdminTab.PENDING) }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            Text("Admin panel", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Approve who gets in, and choose what each member may edit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.error != null) {
            ErrorBanner(
                message = state.error,
                onDismiss = onDismissError,
                modifier = Modifier.padding(16.dp),
            )
        }

        TabRow(selectedTabIndex = tab.ordinal) {
            AdminTab.entries.forEach { entry ->
                Tab(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    text = {
                        if (entry == AdminTab.PENDING && state.pending.isNotEmpty()) {
                            BadgedBox(badge = { Badge { Text(state.pending.size.toString()) } }) {
                                Text(entry.label)
                            }
                        } else {
                            Text(entry.label)
                        }
                    },
                )
            }
        }

        when (tab) {
            AdminTab.PENDING -> PendingTab(
                loading = state.loading,
                pending = state.pending,
                onApprove = onApprove,
                onReject = onReject,
            )

            AdminTab.MEMBERS -> MembersTab(
                loading = state.loading,
                currentUser = currentUser,
                members = state.members,
                onRevoke = onRevoke,
                onTogglePermission = onTogglePermission,
                onSetRole = onSetRole,
            )
        }
    }
}

@Composable
private fun PendingTab(
    loading: Boolean,
    pending: List<Profile>,
    onApprove: (Profile, Set<Permission>) -> Unit,
    onReject: (Profile) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (loading && pending.isEmpty()) {
        LoadingRow(modifier)
        return
    }
    if (pending.isEmpty()) {
        EmptyState(
            title = "No pending requests",
            detail = "When someone signs in with Google for the first time, their " +
                "request lands here for you to approve.",
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(pending, key = { it.id }) { profile ->
            PendingRequestCard(
                profile = profile,
                onApprove = { permissions -> onApprove(profile, permissions) },
                onReject = { onReject(profile) },
            )
        }
    }
}

/**
 * One access request. The admin ticks the rights to grant *before* approving, so
 * the member arrives with the access they need and nothing more.
 */
@Composable
private fun PendingRequestCard(
    profile: Profile,
    onApprove: (Set<Permission>) -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = remember(profile.id) { mutableStateMapOf<Permission, Boolean>() }

    Card(modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = profile.email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            profile.requestedAt?.let {
                Text(
                    text = "Requested ${Dates.forDisplay(it)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = "Grant on approval",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            Permission.entries.forEach { permission ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = selected[permission] == true,
                        onCheckedChange = { selected[permission] = it },
                    )
                    Text(permission.label, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        onApprove(selected.filterValues { it }.keys.toSet())
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Approve") }
                OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) {
                    Text("Reject")
                }
            }
        }
    }
}

@Composable
private fun MembersTab(
    loading: Boolean,
    currentUser: Profile,
    members: List<Profile>,
    onRevoke: (Profile) -> Unit,
    onTogglePermission: (Profile, Permission, Boolean) -> Unit,
    onSetRole: (Profile, Role) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (loading && members.isEmpty()) {
        LoadingRow(modifier)
        return
    }
    if (members.isEmpty()) {
        EmptyState(
            title = "No members yet",
            detail = "Approved and rejected accounts show up here.",
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(members, key = { it.id }) { profile ->
            MemberCard(
                profile = profile,
                isSelf = profile.id == currentUser.id,
                onRevoke = { onRevoke(profile) },
                onTogglePermission = { permission, granted ->
                    onTogglePermission(profile, permission, granted)
                },
                onSetRole = { role -> onSetRole(profile, role) },
            )
        }
    }
}

@Composable
private fun MemberCard(
    profile: Profile,
    isSelf: Boolean,
    onRevoke: () -> Unit,
    onTogglePermission: (Permission, Boolean) -> Unit,
    onSetRole: (Role) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = profile.displayName + if (isSelf) " (you)" else "",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = profile.email,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("${profile.role.label} · ${profile.status.label}") },
                )
            }

            if (profile.status == AccessStatus.REJECTED) {
                Text(
                    text = "Access was declined. Approving again puts them back in " +
                        "the pending queue first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            if (profile.isAdmin) {
                Text(
                    text = "Admins hold every edit right by default.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Permission.entries.forEach { permission ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = permission.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = permission in profile.grantedPermissions,
                            enabled = profile.isApproved,
                            onCheckedChange = { onTogglePermission(permission, it) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Guard against an admin demoting or locking out their own account
                // and leaving the organisation with nobody who can approve anyone.
                if (!isSelf) {
                    TextButton(
                        onClick = { onSetRole(if (profile.isAdmin) Role.MEMBER else Role.ADMIN) },
                    ) {
                        Text(if (profile.isAdmin) "Make member" else "Make admin")
                    }
                    if (profile.isApproved) {
                        TextButton(onClick = onRevoke) { Text("Revoke access") }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AdminScreenPreview() {
    val admin = Profile(
        id = "1",
        email = "admin@example.com",
        fullName = "Admin",
        role = Role.ADMIN,
        status = AccessStatus.APPROVED,
    )
    SankranthiTheme {
        AdminScreen(
            currentUser = admin,
            state = AdminUiState(
                loading = false,
                pending = listOf(
                    Profile(id = "2", email = "lakshmi@example.com", requestedAt = "2026-08-20"),
                ),
                members = listOf(admin),
            ),
            onApprove = { _, _ -> },
            onReject = {},
            onRevoke = {},
            onTogglePermission = { _, _, _ -> },
            onSetRole = { _, _ -> },
            onDismissError = {},
        )
    }
}
