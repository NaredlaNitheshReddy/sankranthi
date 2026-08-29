package com.sankranthi.ledger.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sankranthi.ledger.data.model.AccessStatus
import com.sankranthi.ledger.data.model.Profile
import com.sankranthi.ledger.ui.theme.SankranthiTheme

/**
 * Where a signed-in but unapproved account waits. Pending and rejected both land
 * here — the difference is only what the copy says, since neither may read the
 * books.
 */
@Composable
fun PendingApprovalScreen(
    profile: Profile,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rejected = profile.status == AccessStatus.REJECTED

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = if (rejected) Icons.Filled.Block else Icons.Filled.HourglassTop,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (rejected) "Access declined" else "Waiting for approval",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (rejected) {
                "An admin declined access for ${profile.email}. Talk to them if " +
                    "you think this is a mistake."
            } else {
                "Your request from ${profile.email} is in the admin's pending " +
                    "queue. You will be able to see the books once it is approved."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))

        if (!rejected) {
            Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text("Check again")
            }
        }
        TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PendingApprovalScreenPreview() {
    SankranthiTheme {
        PendingApprovalScreen(
            profile = Profile(
                id = "1",
                email = "lakshmi@example.com",
                status = AccessStatus.PENDING,
            ),
            onRefresh = {},
            onSignOut = {},
        )
    }
}
