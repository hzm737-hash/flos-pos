package cz.flos.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType

/**
 * A message to a customer: the text can be changed, then WhatsApp, SMS or the e-mail app opens
 * with it filled in. [extra] adds choices above the text (e.g. confirmation / reminder).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SendDialog(
    vm: PosViewModel, title: String, phone: String, email: String, text: String, onDismiss: () -> Unit,
    extra: (@Composable ((String) -> Unit) -> Unit)? = null,
) {
    var body by remember(text) { mutableStateOf(text) }
    var tel by remember(phone) { mutableStateOf(phone) }
    var mail by remember(email) { mutableStateOf(email) }
    val clipboard = LocalClipboardManager.current
    fun send(channel: String, contact: String) {
        if (vm.sendMessage(channel, contact, body)) onDismiss()
        else { clipboard.setText(AnnotatedString(body)); vm.notify(tr("Aplikaci nelze otevřít, text je zkopírovaný do schránky.")) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(dp8)) {
                extra?.invoke { body = it }
                OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text(tr("Zpráva")) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = tel, onValueChange = { tel = it }, singleLine = true, label = { Text(tr("Telefon")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = mail, onValueChange = { mail = it }, singleLine = true, label = { Text(tr("E-mail")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(dp8)) {
                    FilledTonalButton(onClick = { send("whatsapp", tel) }, enabled = tel.isNotBlank()) { Text("WhatsApp") }
                    FilledTonalButton(onClick = { send("sms", tel) }, enabled = tel.isNotBlank()) { Text("SMS") }
                    FilledTonalButton(onClick = { send("email", mail) }, enabled = mail.isNotBlank()) { Text(tr("E-mail")) }
                }
                Text(tr("Otevře se aplikace s připraveným textem, zprávu odešlete v ní."), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(body)); vm.notify(tr("Text zkopírován")) }) { Text(tr("Kopírovat text")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zavřít")) } },
    )
}

private val dp8 = androidx.compose.ui.unit.Dp(8f)
