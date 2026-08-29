package com.sankranthi.ledger.ui.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sankranthi.ledger.data.ServiceLocator
import com.sankranthi.ledger.data.model.Expense
import com.sankranthi.ledger.data.model.LedgerSummary
import com.sankranthi.ledger.data.model.LivestockEntry
import com.sankranthi.ledger.data.model.Profile
import com.sankranthi.ledger.data.repository.Actor
import com.sankranthi.ledger.data.repository.LedgerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LedgerUiState(
    val loading: Boolean = true,
    val livestock: List<LivestockEntry> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val pendingCount: Int = 0,
    val error: String? = null,
) {
    val summary: LedgerSummary get() = LedgerSummary.of(livestock, expenses)
}

/**
 * The books, shared by the dashboard, livestock and expenses screens.
 *
 * State comes straight off Room `Flow`s, so there is no `load()` and nothing to
 * refresh: a write updates the UI because the database changed, and so does an
 * incoming sync. That is what makes the app feel instant on a bad connection
 * (§17, §18).
 */
class LedgerViewModel(
    private val ledger: LedgerRepository = ServiceLocator.ledgerRepository,
) : ViewModel() {

    private val errors = MutableStateFlow<String?>(null)

    val state: StateFlow<LedgerUiState> = combine(
        ledger.observeLivestock(),
        ledger.observeExpenses(),
        ledger.observePendingCount(),
        errors,
    ) { livestock, expenses, pending, error ->
        LedgerUiState(
            loading = false,
            livestock = livestock,
            expenses = expenses,
            pendingCount = pending,
            error = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LedgerUiState(),
    )

    /** Who to attribute writes to. Set by the shell once the session is known. */
    private var actor: Actor = Actor(null, null)

    fun bindActor(profile: Profile) {
        actor = Actor(email = profile.email, displayName = profile.displayName)
    }

    fun saveLivestock(entry: LivestockEntry, onDone: () -> Unit = {}) {
        write({ ledger.saveLivestock(entry, actor) }, onDone)
    }

    fun deleteLivestock(id: String) {
        write({ ledger.deleteLivestock(id, actor) })
    }

    fun saveExpense(expense: Expense, onDone: () -> Unit = {}) {
        write({ ledger.saveExpense(expense, actor) }, onDone)
    }

    fun deleteExpense(id: String) {
        write({ ledger.deleteExpense(id, actor) })
    }

    fun dismissError() {
        errors.value = null
    }

    /**
     * Writes go to the local database only, so the only realistic failure is a
     * genuine storage problem. There is deliberately no network call to fail
     * here — that is the sync layer's job, and its failures surface through the
     * pending count rather than as an error on the user's save.
     */
    private fun write(block: suspend () -> Unit, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                block()
                errors.value = null
                onDone()
            } catch (e: Exception) {
                errors.value = e.message ?: "The change could not be saved on this device."
            }
        }
    }
}
