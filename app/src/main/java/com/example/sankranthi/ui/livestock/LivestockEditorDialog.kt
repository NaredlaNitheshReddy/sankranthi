package com.example.sankranthi.ui.livestock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.text.KeyboardOptions
import com.example.sankranthi.data.model.LivestockEntry
import com.example.sankranthi.data.model.TradeKind
import com.example.sankranthi.ui.common.DateField
import com.example.sankranthi.ui.common.PickerField
import com.example.sankranthi.util.Dates
import com.example.sankranthi.util.Money

/** Animals the partnership deals in. Free text is still allowed. */
private val COMMON_ANIMALS = listOf("Goat", "Sheep", "Cow", "Buffalo", "Poultry", "Other")

/**
 * Create or edit one livestock trade. Save stays disabled until the entry is
 * valid, so a bad amount cannot reach the books.
 */
@Composable
fun LivestockEditorDialog(
    existing: LivestockEntry?,
    onDismiss: () -> Unit,
    onSave: (LivestockEntry) -> Unit,
) {
    var kind by remember { mutableStateOf(existing?.kind ?: TradeKind.BUY) }
    var animal by remember { mutableStateOf(existing?.animal ?: COMMON_ANIMALS.first()) }
    var headCount by remember { mutableStateOf(existing?.headCount?.toString() ?: "") }
    var amount by remember {
        mutableStateOf(existing?.let { Money.toEditableRupees(it.amountMinor) } ?: "")
    }
    var counterparty by remember { mutableStateOf(existing?.counterparty ?: "") }
    var occurredOn by remember { mutableStateOf(existing?.occurredOn ?: Dates.today()) }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }

    val parsedHead = headCount.toIntOrNull()
    val parsedAmount = Money.parseToMinor(amount)
    val valid = animal.isNotBlank() &&
        parsedHead != null && parsedHead > 0 &&
        parsedAmount != null && parsedAmount > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New livestock entry" else "Edit livestock entry") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PickerField(
                    label = "Type",
                    options = TradeKind.entries,
                    selected = kind,
                    optionLabel = { it.label },
                    onSelect = { kind = it },
                )
                PickerField(
                    label = "Animal",
                    options = COMMON_ANIMALS,
                    selected = if (animal in COMMON_ANIMALS) animal else COMMON_ANIMALS.last(),
                    optionLabel = { it },
                    onSelect = { animal = it },
                )
                OutlinedTextField(
                    value = headCount,
                    onValueChange = { headCount = it.filter(Char::isDigit) },
                    label = { Text("Head count") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = headCount.isNotEmpty() && (parsedHead == null || parsedHead <= 0),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Total amount (₹)") },
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
                    value = counterparty,
                    onValueChange = { counterparty = it },
                    label = { Text("Bought from / sold to") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
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
                        LivestockEntry(
                            id = existing?.id ?: "",
                            kind = kind,
                            animal = animal.trim(),
                            headCount = parsedHead ?: 0,
                            amountMinor = parsedAmount ?: 0L,
                            counterparty = counterparty.trim().ifBlank { null },
                            occurredOn = occurredOn,
                            notes = notes.trim().ifBlank { null },
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
