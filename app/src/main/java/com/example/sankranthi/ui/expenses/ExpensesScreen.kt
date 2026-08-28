package com.example.sankranthi.ui.expenses

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
import com.example.sankranthi.data.model.Expense
import com.example.sankranthi.data.model.Permission
import com.example.sankranthi.data.model.Profile
import com.example.sankranthi.ui.common.EmptyState
import com.example.sankranthi.ui.common.ErrorBanner
import com.example.sankranthi.ui.common.LoadingRow
import com.example.sankranthi.ui.common.SummaryTile
import com.example.sankranthi.ui.ledger.LedgerUiState
import com.example.sankranthi.util.Dates
import com.example.sankranthi.util.Money

/**
 * Running costs of the operation — feed, vet, labour, shed repairs. Same
 * permission model as livestock, gated on [Permission.EDIT_EXPENSES].
 */
@Composable
fun ExpensesScreen(
    profile: Profile,
    state: LedgerUiState,
    onSave: (Expense, () -> Unit) -> Unit,
    onDelete: (String) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canEdit = profile.can(Permission.EDIT_EXPENSES)
    val canDelete = profile.can(Permission.DELETE_ENTRIES)

    var editing by remember { mutableStateOf<Expense?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column {
                    Text("Maintenance", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = if (canEdit) {
                            "Feed, veterinary, labour and everything else it costs to run."
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

            item {
                SummaryTile(
                    label = "Total maintenance spend",
                    amountMinor = state.summary.expensesMinor,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state.loading && state.expenses.isEmpty()) {
                item { LoadingRow() }
            } else if (state.expenses.isEmpty()) {
                item {
                    EmptyState(
                        title = "No expenses recorded",
                        detail = if (canEdit) {
                            "Tap Add expense to record the first one."
                        } else {
                            "Nothing has been recorded yet."
                        },
                    )
                }
            } else {
                items(state.expenses, key = { it.id }) { expense ->
                    ExpenseCard(
                        expense = expense,
                        canEdit = canEdit,
                        canDelete = canDelete,
                        onClick = {
                            if (canEdit) {
                                editing = expense
                                showEditor = true
                            }
                        },
                        onDelete = { onDelete(expense.id) },
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
                text = { Text("Add expense") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }

    if (showEditor) {
        ExpenseEditorDialog(
            existing = editing,
            onDismiss = { showEditor = false },
            onSave = { expense -> onSave(expense) { showEditor = false } },
        )
    }
}

@Composable
private fun ExpenseCard(
    expense: Expense,
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
                    Text(expense.category.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = Dates.forDisplay(expense.occurredOn),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = Money.format(expense.amountMinor),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            expense.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                expense.createdByName?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = "Entered by $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } ?: AssistChip(onClick = {}, enabled = false, label = { Text("Maintenance") })
                if (canDelete) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete expense")
                    }
                }
            }
        }
    }
}
