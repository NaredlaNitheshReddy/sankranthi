package com.sankranthi.ledger.ui.livestock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sankranthi.ledger.data.model.LivestockEntry
import com.sankranthi.ledger.data.model.Permission
import com.sankranthi.ledger.data.model.Profile
import com.sankranthi.ledger.data.model.TradeKind
import com.sankranthi.ledger.ui.common.EmptyState
import com.sankranthi.ledger.ui.common.ErrorBanner
import com.sankranthi.ledger.ui.common.LoadingRow
import com.sankranthi.ledger.ui.ledger.LedgerUiState
import com.sankranthi.ledger.util.Dates
import com.sankranthi.ledger.util.Money

/**
 * The livestock book. Reading is open to every approved member; the add, edit and
 * delete affordances appear only for the permissions the admin granted.
 */
@Composable
fun LivestockScreen(
    profile: Profile,
    state: LedgerUiState,
    onSave: (LivestockEntry, () -> Unit) -> Unit,
    onDelete: (String) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canEdit = profile.can(Permission.EDIT_LIVESTOCK)
    val canDelete = profile.can(Permission.DELETE_ENTRIES)

    var editing by remember { mutableStateOf<LivestockEntry?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column {
                    Text("Livestock", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = if (canEdit) {
                            "Record every purchase and sale."
                        } else {
                            "View only — ask an admin for edit rights."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.error != null) {
                item { ErrorBanner(message = state.error, onDismiss = onDismissError) }
            }

            if (state.loading && state.livestock.isEmpty()) {
                item { LoadingRow() }
            } else if (state.livestock.isEmpty()) {
                item {
                    EmptyState(
                        title = "No livestock entries",
                        detail = if (canEdit) {
                            "Tap Add entry to record your first purchase or sale."
                        } else {
                            "Nothing has been recorded yet."
                        },
                    )
                }
            } else {
                items(state.livestock, key = { it.id }) { entry ->
                    LivestockCard(
                        entry = entry,
                        canEdit = canEdit,
                        canDelete = canDelete,
                        onClick = {
                            if (canEdit) {
                                editing = entry
                                showEditor = true
                            }
                        },
                        onDelete = { onDelete(entry.id) },
                    )
                }
            }
        }

        if (canEdit) {
            ExtendedFloatingActionButton(
                onClick = {
                    editing = null
                    showEditor = true
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add entry") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }

    if (showEditor) {
        LivestockEditorDialog(
            existing = editing,
            onDismiss = { showEditor = false },
            onSave = { entry -> onSave(entry) { showEditor = false } },
        )
    }
}

@Composable
private fun LivestockCard(
    entry: LivestockEntry,
    canEdit: Boolean,
    canDelete: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (canEdit) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${entry.headCount} × ${entry.animal}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = Dates.forDisplay(entry.occurredOn),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = Money.format(entry.amountMinor),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (entry.kind == TradeKind.SELL) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(onClick = {}, enabled = false, label = { Text(entry.kind.label) })
                entry.counterparty?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (canDelete) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete entry")
                    }
                }
            }

            entry.notes?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            entry.createdByName?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = "Entered by $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
