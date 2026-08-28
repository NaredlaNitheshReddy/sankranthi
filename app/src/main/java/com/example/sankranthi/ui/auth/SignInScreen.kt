package com.example.sankranthi.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.sankranthi.data.repo.demo.DemoBackend
import com.example.sankranthi.ui.common.ErrorBanner
import com.example.sankranthi.ui.theme.SankranthiTheme

/**
 * Entry point for anyone not signed in. Access is by Google account only; the
 * admin decides afterwards whether that account may use the books.
 */
@Composable
fun SignInScreen(
    state: SignInUiState,
    demoBackend: Boolean,
    onGoogleSignIn: (android.content.Context) -> Unit,
    onDemoSignIn: (String) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Sankranthi", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Livestock and maintenance accounts for the partnership.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))

        if (state.error != null) {
            ErrorBanner(message = state.error, onDismiss = onDismissError)
            Spacer(Modifier.height(16.dp))
        }

        if (state.busy) {
            CircularProgressIndicator(Modifier.size(32.dp))
            Spacer(Modifier.height(16.dp))
        }

        Button(
            onClick = { onGoogleSignIn(context) },
            enabled = !state.busy && !demoBackend,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue with Google")
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "New accounts start as a request. An admin approves you and " +
                "chooses what you may edit.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (demoBackend) {
            Spacer(Modifier.height(32.dp))
            DemoSignInCard(busy = state.busy, onDemoSignIn = onDemoSignIn)
        }
    }
}

/**
 * Shown only when the build has no Supabase credentials. Lets the flow be walked
 * end to end without a backend, standing in for the Google account chooser.
 */
@Composable
private fun DemoSignInCard(
    busy: Boolean,
    onDemoSignIn: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Demo mode", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "No Supabase credentials in local.properties, so the app is " +
                    "running on an in-memory backend. Sign in as either role to " +
                    "try the flow.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = { onDemoSignIn(DemoBackend.DEMO_ADMIN_EMAIL) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sign in as admin") }
            OutlinedButton(
                onClick = { onDemoSignIn(DemoBackend.DEMO_MEMBER_EMAIL) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sign in as approved member") }
            OutlinedButton(
                onClick = { onDemoSignIn("newcomer@demo.local") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sign in as a brand-new account") }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SignInScreenPreview() {
    SankranthiTheme {
        SignInScreen(
            state = SignInUiState(),
            demoBackend = true,
            onGoogleSignIn = {},
            onDemoSignIn = {},
            onDismissError = {},
        )
    }
}
