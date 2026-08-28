package com.example.sankranthi.ui.expenses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.sankranthi.data.model.Expense
import com.example.sankranthi.data.model.ExpenseCategory
import com.example.sankranthi.ui.common.DateField
import com.example.sankranthi.ui.common.PickerField
import com.example.sankranthi.util.Dates
import com.example.sankranthi.util.Money

/** Create or edit one maintenance expense. */
@Composable
fun ExpenseEditorDialog(
    existing: Expense?,
    onDismiss: () -> Unit,
    onSave: (Expense) -> Unit,
) {
    var category by remember { mutableStateOf(existing?.category ?: ExpenseCategory.FEED) }
    var amount by remember {
        mutableStateOf(existing?.let { Money.toEditableRupees(it.amountMinor) } ?: "")
    }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var occurredOn by remember { mutableStateOf(existing?.occurredOn ?: Dates.today()) }

    val parsedAmount = Money.parseToMinor(amount)
    val valid = parsedAmount != null && parsedAmount > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New expense" else "Edit expense") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PickerField(
                    label = "Category",
                    options = ExpenseCategory.entries,
                    selected = category,
                    optionLabel = { it.label },
                    onSelect = { category = it },
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount (₹)") },
                    supportingText = { Text("Whole rupees, or up to two decimals") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = amount.isNotEmpty() && parsedAmount == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                DateField(
                    label = "Date",
                    isoDate = occurredOn,
                    onDateChange = { occurredOn = it },
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("What was it for?") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        Expense(
                            id = existing?.id ?: "",
                            category = category,
                            amountMinor = parsedAmount ?: 0L,
                            description = description.trim().ifBlank { null },
                            occurredOn = occurredOn,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
