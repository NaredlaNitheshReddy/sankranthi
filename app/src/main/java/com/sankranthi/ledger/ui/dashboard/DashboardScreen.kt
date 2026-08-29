package com.sankranthi.ledger.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sankranthi.ledger.data.model.Expense
import com.sankranthi.ledger.data.model.LedgerSummary
import com.sankranthi.ledger.data.model.LivestockEntry
import com.sankranthi.ledger.data.model.Permission
import com.sankranthi.ledger.data.model.Profile
import com.sankranthi.ledger.ui.common.EmptyState
import com.sankranthi.ledger.ui.common.ErrorBanner
import com.sankranthi.ledger.ui.common.LoadingRow
import com.sankranthi.ledger.ui.common.SectionHeader
import com.sankranthi.ledger.ui.common.SummaryTile
import com.sankranthi.ledger.ui.ledger.LedgerUiState
import com.sankranthi.ledger.util.Dates
import com.sankranthi.ledger.util.Money

/** Read-only overview. Every approved member sees this, whatever their permissions. */
@Composable
fun DashboardScreen(
    profile: Profile,
    state: LedgerUiState,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = state.summary
    val recent = remember(state.livestock, state.expenses) { recentActivity(state) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(
                    text = "Namaskaram, ${profile.displayName}",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = "${profile.role.label} · ${profile.grantedPermissions.size} " +
                        "of ${Permission.entries.size} edit rights",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.error != null) {
            item { ErrorBanner(message = state.error, onDismiss = onDismissError) }
        }

        item { NetPositionCard(summary) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryTile(
                    label = "Livestock sales",
                    amountMinor = summary.salesMinor,
                    modifier = Modifier.weight(1f),
                )
                SummaryTile(
                    label = "Livestock purchases",
                    amountMinor = summary.purchasesMinor,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryTile(
                    label = "Maintenance expenses",
                    amountMinor = summary.expensesMinor,
                    modifier = Modifier.weight(1f),
                )
                SummaryTile(
                    label = "Trading margin",
                    amountMinor = summary.grossMarginMinor,
                    signed = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item { HeadCountCard(summary) }

        item { SectionHeader("Recent activity") }

        if (state.loading && recent.isEmpty()) {
            item { LoadingRow() }
        } else if (recent.isEmpty()) {
            item {
                EmptyState(
                    title = "Nothing recorded yet",
                    detail = "Livestock trades and maintenance expenses will show up here.",
                )
            }
        } else {
            items(recent, key = { it.id }) { row ->
                ActivityRow(row)
            }
        }
    }
}

@Composable
private fun NetPositionCard(summary: LedgerSummary, modifier: Modifier = Modifier) {
    SummaryTile(
        label = "Net position (sales − purchases − expenses)",
        amountMinor = summary.netMinor,
        signed = true,
        emphasise = true,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun HeadCountCard(summary: LedgerSummary, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Head count", style = MaterialTheme.typography.labelMedium)
            Text(
                text = "${summary.headBought} bought · ${summary.headSold} sold · " +
                    "${summary.headBought - summary.headSold} on hand",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun ActivityRow(row: ActivityItem, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(row.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "${Dates.forDisplay(row.occurredOn)} · ${row.subtitle}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = Money.formatSigned(row.signedMinor),
                style = MaterialTheme.typography.bodyLarge,
                color = if (row.signedMinor < 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        HorizontalDivider()
    }
}

private data class ActivityItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val occurredOn: String,
    val signedMinor: Long,
)

/** Livestock trades and expenses interleaved, newest first, capped for the overview. */
private fun recentActivity(state: LedgerUiState): List<ActivityItem> {
    val trades = state.livestock.map { it.toActivityItem() }
    val spend = state.expenses.map { it.toActivityItem() }
    return (trades + spend)
        .sortedWith(compareBy(Dates.descendingComparator()) { it.occurredOn })
        .take(12)
}

private fun LivestockEntry.toActivityItem() = ActivityItem(
    id = id,
    title = "${kind.label}: $headCount × $animal",
    subtitle = counterparty?.takeIf { it.isNotBlank() }
        ?: createdByName?.takeIf { it.isNotBlank() }
        ?: "Livestock",
    occurredOn = occurredOn,
    signedMinor = signedMinor,
)

private fun Expense.toActivityItem() = ActivityItem(
    id = id,
    title = category.label,
    subtitle = description?.takeIf { it.isNotBlank() }
        ?: createdByName?.takeIf { it.isNotBlank() }
        ?: "Maintenance",
    occurredOn = occurredOn,
    signedMinor = -amountMinor,
)
