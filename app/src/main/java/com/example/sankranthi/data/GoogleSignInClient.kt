package com.example.sankranthi.data

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.MessageDigest
import java.security.SecureRandom

/** A Google ID token plus the raw nonce Supabase must verify it against. */
data class GoogleIdCredential(val idToken: String, val rawNonce: String)

/** Raised when the account chooser was dismissed — not an error worth surfacing. */
class SignInCancelled : Exception("Sign-in cancelled")

/**
 * Wraps Credential Manager to obtain a Google ID token. The token is then traded
 * for a Supabase session, so the OAuth client id must be the *web* application
 * client id registered with Supabase, not the Android one.
 */
class GoogleSignInClient(appContext: Context) {

    private val credentialManager = CredentialManager.create(appContext)

    /**
     * Shows the Google account chooser and returns the resulting ID token.
     * Prefers accounts already used with this app, falling back to the full
     * chooser when there are none.
     *
     * @param activityContext must be an Activity context — Credential Manager
     *   presents UI and cannot do so from an application context.
     */
    suspend fun requestIdToken(activityContext: Context): GoogleIdCredential {
        require(AppConfig.googleWebClientId.isNotBlank()) {
            "google.webClientId is missing from local.properties."
        }
        return try {
            fetch(activityContext, filterByAuthorizedAccounts = true)
        } catch (_: NoCredentialException) {
            fetch(activityContext, filterByAuthorizedAccounts = false)
        } catch (e: GetCredentialCancellationException) {
            throw SignInCancelled()
        }
    }

    private suspend fun fetch(
        activityContext: Context,
        filterByAuthorizedAccounts: Boolean,
    ): GoogleIdCredential {
        // Google sees only the hash; Supabase gets the raw value and checks that
        // it hashes to the nonce embedded in the signed token.
        val rawNonce = newRawNonce()

        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
            .setServerClientId(AppConfig.googleWebClientId)
            .setNonce(sha256(rawNonce))
            .setAutoSelectEnabled(false)
            .build()

        val response = credentialManager.getCredential(
            context = activityContext,
            request = GetCredentialRequest.Builder().addCredentialOption(option).build(),
        )

        val credential = GoogleIdTokenCredential.createFrom(response.credential.data)
        return GoogleIdCredential(idToken = credential.idToken, rawNonce = rawNonce)
    }

    private fun newRawNonce(): String =
        ByteArray(32).also { SecureRandom().nextBytes(it) }.toHex()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
