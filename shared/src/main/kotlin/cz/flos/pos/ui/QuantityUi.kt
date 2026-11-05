package cz.flos.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cz.flos.core.Money
import cz.flos.core.Qty

/** Quick quantities offered in the quantity dialog; ½ is a half portion. */
private val PRESETS = listOf(0.5, 1.0, 1.5, 2.0, 3.0, 4.0)

/**
 * Sets the quantity of a cart line: a half portion, one and a half, or any amount typed in
 * ("0,5", "1/2", "0,25", "1,5" …). The price is the unit price times the quantity.
 */
@Composable
internal fun QuantityDialog(name: String, unitPrice: Long, current: Double, onDismiss: () -> Unit, onSet: (Double) -> Unit) {
    var text by remember { mutableStateOf(Qty.format(current)) }
    val parsed = Qty.parse(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Množství: %s", name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PRESETS.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { q ->
                            FilterChip(
                                selected = parsed == q, onClick = { onSet(q) }, shape = RoundedCornerShape(50),
                                label = { Text(if (q == 0.5) tr("½ porce") else Qty.format(q)) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text(tr("Jiné množství")) },
                    supportingText = { Text(tr("Např. 0,5 nebo 1,5 nebo 1/2")) },
                    isError = text.isNotBlank() && parsed == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { parsed?.let(onSet) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                parsed?.let {
                    Text(tr("Cena: %s", Money.format(Math.round(unitPrice * it))), fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = { Button(onClick = { parsed?.let(onSet) }, enabled = parsed != null) { Text(tr("Nastavit")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zrušit")) } },
    )
}

/**
 * − / + for choosing part of a line (split bill, refunds). Steps are whole pieces, and "½"
 * adds half a portion, never past [max]; a line of 2,5 can be taken as 0,5, 1,5 or 2,5.
 */
@Composable
internal fun QtyStepper(value: Double, max: Double, onChange: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onChange(Qty.norm(minOf(value + 0.5, max))) }, enabled = value + 0.5 <= max + 1e-9) {
            Text("+½", style = MaterialTheme.typography.labelLarge)
        }
        FilledTonalIconButton(onClick = { onChange(Qty.stepDown(value)) }, enabled = value > 0, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Remove, tr("Méně"))
        }
        Text(Qty.format(value), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(40.dp))
        FilledTonalIconButton(onClick = { onChange(Qty.stepUp(value, max)) }, enabled = value < max - 1e-9, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Add, tr("Více"))
        }
    }
}
