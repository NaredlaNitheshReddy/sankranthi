package com.example.sankranthi.ui.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sankranthi.data.ServiceLocator
import com.example.sankranthi.data.model.Expense
import com.example.sankranthi.data.model.LedgerSummary
import com.example.sankranthi.data.model.LivestockEntry
import com.example.sankranthi.data.repo.LedgerRepository
import com.example.sankranthi.util.Dates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LedgerUiState(
    val loading: Boolean = true,
    val livestock: List<LivestockEntry> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val error: String? = null,
) {
    val summary: LedgerSummary get() = LedgerSummary.of(livestock, expenses)
}

/** The books, shared by the dashboard, livestock and expenses screens. */
class LedgerViewModel(
    private val ledger: LedgerRepository = ServiceLocator.ledgerRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LedgerUiState())
    val state: StateFlow<LedgerUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val livestock = ledger.livestockEntries()
                    .sortedWith(compareBy(Dates.descendingComparator()) { it.occurredOn })
                val expenses = ledger.expenses()
                    .sortedWith(compareBy(Dates.descendingComparator()) { it.occurredOn })
                _state.value = LedgerUiState(
                    loading = false,
                    livestock = livestock,
                    expenses = expenses,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "Could not load the ledger.",
                )
            }
        }
    }

    fun saveLivestock(entry: LivestockEntry, onDone: () -> Unit = {}) {
        mutate({ ledger.saveLivestockEntry(entry) }, onDone)
    }

    fun deleteLivestock(id: String) {
        mutate({ ledger.deleteLivestockEntry(id) })
    }

    fun saveExpense(expense: Expense, onDone: () -> Unit = {}) {
        mutate({ ledger.saveExpense(expense) }, onDone)
    }

    fun deleteExpense(id: String) {
        mutate({ ledger.deleteExpense(id) })
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    /**
     * Runs a write then re-reads, so the list reflects whatever the database
     * actually stored (ids, `created_by`, RLS rejections).
     */
    private fun mutate(block: suspend () -> Unit, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                block()
                onDone()
                load()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "The change could not be saved.",
                )
            }
        }
    }
}
