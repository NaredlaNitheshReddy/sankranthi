package com.sankranthi.ledger.ui.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sankranthi.ledger.data.AppConfig
import com.sankranthi.ledger.data.ServiceLocator
import com.sankranthi.ledger.data.SignInCancelled
import com.sankranthi.ledger.data.repo.AuthRepository
import com.sankranthi.ledger.data.repo.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SignInUiState(
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Owns "who is signed in and are they admitted yet". Everything above this in the
 * UI tree keys off [session].
 */
class SessionViewModel(
    private val auth: AuthRepository = ServiceLocator.authRepository,
) : ViewModel() {

    val session: StateFlow<SessionState> = auth.session

    private val _signIn = MutableStateFlow(SignInUiState())
    val signIn: StateFlow<SignInUiState> = _signIn.asStateFlow()

    val googleSignInAvailable: Boolean get() = AppConfig.hasGoogleSignIn
    val demoBackend: Boolean get() = ServiceLocator.usingDemoBackend

    /** @param activityContext Credential Manager needs an Activity to draw on. */
    fun signInWithGoogle(activityContext: Context) {
        val client = ServiceLocator.googleSignInClient
        if (client == null) {
            _signIn.value = SignInUiState(
                error = "Google sign-in is not configured. Add supabase.url, " +
                    "supabase.anonKey and google.webClientId to local.properties.",
            )
            return
        }
        viewModelScope.launch {
            _signIn.value = SignInUiState(busy = true)
            try {
                val credential = client.requestIdToken(activityContext)
                auth.signInWithGoogle(credential.idToken, credential.rawNonce)
                _signIn.value = SignInUiState()
            } catch (_: SignInCancelled) {
                _signIn.value = SignInUiState()
            } catch (e: Exception) {
                _signIn.value = SignInUiState(error = e.message ?: "Sign-in failed.")
            }
        }
    }

    /** Demo backend only — stands in for Google while there is no Supabase project. */
    fun signInAsDemo(email: String) {
        viewModelScope.launch {
            _signIn.value = SignInUiState(busy = true)
            try {
                auth.signInAsDemo(email)
                _signIn.value = SignInUiState()
            } catch (e: Exception) {
                _signIn.value = SignInUiState(error = e.message ?: "Sign-in failed.")
            }
        }
    }

    /** Picks up an admin decision made while the user was sitting on the wait screen. */
    fun refresh() {
        viewModelScope.launch {
            runCatching { auth.reloadProfile() }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            runCatching { auth.signOut() }
        }
    }

    fun dismissError() {
        _signIn.value = _signIn.value.copy(error = null)
    }
}
