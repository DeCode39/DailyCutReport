package com.littleone.dailycutreport

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal fun cartCommitLabel(date: LocalDate?, today: LocalDate = LocalDate.now()): String =
    when (date) {
        null -> "Choose date"
        today -> "Log to today"
        else -> "Log to ${date.format(DateTimeFormatter.ofPattern("d MMM"))}"
    }

private fun showCartDatePicker(context: android.content.Context, date: LocalDate, onDate: (LocalDate) -> Unit) {
    DatePickerDialog(context, { _, year, month, day -> onDate(LocalDate.of(year, month + 1, day)) },
        date.year, date.monthValue - 1, date.dayOfMonth)
        .apply {
            datePicker.maxDate = System.currentTimeMillis()
            setButton(android.content.DialogInterface.BUTTON_NEUTRAL, "Today") { _, _ -> onDate(LocalDate.now()) }
        }.show()
}

@Composable
internal fun CartSheetHeader(
    draft: BulkDraft, submitting: Boolean, onClose: () -> Unit,
    onDateChange: (LocalDate) -> Unit, onCommit: () -> Unit
) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onClose, enabled = !submitting, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${draft.items.size} item(s)", fontWeight = FontWeight.Bold)
            TextButton(
                enabled = !submitting,
                onClick = { showCartDatePicker(context, draft.date ?: LocalDate.now(), onDateChange) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Change cart date: ${draft.date}" }
            ) { Text(draft.date?.toString() ?: "Choose date", style = MaterialTheme.typography.bodySmall) }
        }
        TextButton(
            onClick = onCommit, enabled = draft.isValid && !submitting,
            modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 128.dp)
                .semantics { contentDescription = "Log cart to ${draft.date}" }
        ) { Text(if (submitting) "Saving…" else cartCommitLabel(draft.date)) }
    }
}

@Composable
internal fun CopyDestinationDialog(sourceDate: LocalDate, onDismiss: () -> Unit, onCopy: (LocalDate) -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Copy foods from $sourceDate") },
        text = { Text("Choose the destination. Quantities go into the review cart; old actual-paid prices are not copied.") },
        confirmButton = { TextButton(onClick = { onCopy(LocalDate.now()) }) { Text("Copy to today") } },
        dismissButton = {
            Column {
                TextButton(onClick = { showCartDatePicker(context, LocalDate.now(), onCopy) }) { Text("Choose date") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
