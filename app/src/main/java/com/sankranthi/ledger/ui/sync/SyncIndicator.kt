package com.sankranthi.ledger.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sankranthi.ledger.ui.theme.SankranthiTheme

/** What the sync layer is currently doing, as far as the user needs to know. */
sealed interface SyncUiState {
    data object Synced : SyncUiState
    data object Syncing : SyncUiState
    data class Pending(val count: Int) : SyncUiState
    data class Failed(val count: Int) : SyncUiState
    data class Offline(val count: Int) : SyncUiState
}

/**
 * The status chip from §19. Deliberately understated: pending work is normal in
 * an offline-first app, not an error, so it must not read as an alarm or
 * interrupt what the user is doing. Nothing here is tappable.
 */
@Composable
fun SyncIndicator(state: SyncUiState, modifier: Modifier = Modifier) {
    val (icon, label, tint) = when (state) {
        SyncUiState.Synced -> Triple(
            Icons.Filled.CloudDone,
            "Synced",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SyncUiState.Syncing -> Triple(null, "Syncing…", MaterialTheme.colorScheme.primary)

        is SyncUiState.Pending -> Triple(
            Icons.Filled.CloudQueue,
            "${state.count} pending",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is SyncUiState.Failed -> Triple(
            Icons.Filled.ErrorOutline,
            if (state.count > 0) "Retrying ${state.count}" else "Retrying",
            MaterialTheme.colorScheme.error,
        )

        is SyncUiState.Offline -> Triple(
            Icons.Filled.CloudOff,
            if (state.count > 0) "Offline · ${state.count}" else "Offline",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon == null) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = tint,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = tint,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
}

/**
 * Derives the chip state from what Phase 2 actually knows.
 *
 * Until the sync layer lands in Phase 5 there is no uploader and no connectivity
 * monitor, so anything queued is reported as *pending* rather than as synced or
 * failed. Claiming "Synced" while records sit in the outbox would be a lie the
 * user could act on.
 */
fun syncStateFrom(pendingCount: Int, syncing: Boolean = false, offline: Boolean = false): SyncUiState =
    when {
        syncing -> SyncUiState.Syncing
        offline -> SyncUiState.Offline(pendingCount)
        pendingCount > 0 -> SyncUiState.Pending(pendingCount)
        else -> SyncUiState.Synced
    }

@Preview
@Composable
private fun SyncIndicatorPreview() {
    SankranthiTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SyncIndicator(SyncUiState.Synced)
            SyncIndicator(SyncUiState.Pending(3))
            SyncIndicator(SyncUiState.Offline(1))
            SyncIndicator(SyncUiState.Failed(2))
        }
    }
}
