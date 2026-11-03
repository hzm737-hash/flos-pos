package cz.flos.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.flos.core.Money
import cz.flos.pos.LocalPlatform
import cz.flos.pos.data.BankPaymentEntity
import cz.flos.pos.data.BankSource
import cz.flos.pos.data.SaleEntity
import cz.flos.pos.data.TipsOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun tipsHint(owner: TipsOwner): String = when (owner) {
    TipsOwner.STAFF -> tr("Spropitné patří zaměstnancům: neeviduje se v EET a není v základu DPH.")
    TipsOwner.BUSINESS -> tr("Spropitné patří provozovně: eviduje se v EET, není v základu DPH.")
}

/** Asks for an amount in crowns. */
@Composable
fun AmountDialog(
    title: String,
    label: String,
    initial: Long,
    hint: (Long) -> String = { "" },
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var text by remember { mutableStateOf(if (initial == 0L) "" else Money.format(initial, "")) }
    val amount = Money.parse(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(label) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                val h = amount?.let(hint).orEmpty()
                if (h.isNotEmpty()) Text(h, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(enabled = amount != null && amount >= 0, onClick = { onConfirm(amount ?: 0) }) { Text(tr("Uložit")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zrušit")) } },
    )
}

/**
 * Payments read from the bank: receipts still waiting for their money, and payments Flos could
 * not pair with a receipt, which a person assigns or hides here.
 */
@Composable
fun BankPaymentsDialog(vm: PosViewModel, onDismiss: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val unmatched by vm.unmatchedPayments.collectAsState()
    val waiting by vm.waitingTransfers.collectAsState()
    val state by vm.bankState.collectAsState()
    var assigning by remember { mutableStateOf<BankPaymentEntity?>(null) }
    val fmt = remember { SimpleDateFormat("d.M. HH:mm", Locale.forLanguageTag("cs-CZ")) }
    val pickStatement = LocalPlatform.current.rememberFilePicker { bytes -> vm.importStatement(bytes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Platby z banky")) },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(
                        if (settings.bankSource == BankSource.OFF) tr("Automatická kontrola je vypnutá. Zapněte ji v Nastavení → Platby z banky.")
                        else state ?: tr("Kontrola probíhá každou minutu, dokud je pokladna otevřená."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr("Výpis z jakékoli banky (GPC/ABO nebo XML CAMT.053)"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = pickStatement) { Text(tr("Nahrát výpis")) }
                    }
                }
                item { Text(tr("Nepřiřazené platby (%d)", unmatched.size), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
                if (unmatched.isEmpty()) item { Text(tr("Žádné"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(unmatched, key = { it.id }) { p ->
                    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(Money.format(p.amount), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text(fmt.format(Date(p.bookedAt ?: p.readAt)), style = MaterialTheme.typography.bodySmall)
                            }
                            Text(listOfNotNull(p.variableSymbol?.let { "VS $it" }, p.counterName, p.counterAccount, p.message).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { assigning = p }, enabled = waiting.isNotEmpty()) { Text(tr("Přiřadit k účtence")) }
                                OutlinedButton(onClick = { vm.ignorePayment(p) }) { Text(tr("Skrýt")) }
                            }
                        }
                    }
                }
                item { HorizontalDivider() }
                item { Text(tr("Účtenky čekající na platbu (%d)", waiting.size), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
                if (waiting.isEmpty()) item { Text(tr("Žádné"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(waiting, key = { "w" + it.id }) { s -> WaitingRow(s, fmt, settings.transferDueDays) }
            }
        },
        confirmButton = { Button(onClick = { vm.checkBankNow() }, enabled = settings.bankSource != BankSource.OFF) { Text(tr("Zkontrolovat teď")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zavřít")) } },
    )

    assigning?.let { p ->
        AlertDialog(
            onDismissRequest = { assigning = null },
            title = { Text(tr("Přiřadit platbu %s", Money.format(p.amount))) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(waiting, key = { it.id }) { s ->
                        Card(onClick = { vm.assignPayment(p, s); assigning = null }, shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) { WaitingRow(s, fmt, settings.transferDueDays) }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { assigning = null }) { Text(tr("Zpět")) } },
        )
    }
}

@Composable
private fun WaitingRow(s: SaleEntity, fmt: SimpleDateFormat, dueDays: Int) {
    val overdue = s.createdAt < System.currentTimeMillis() - dueDays * 86_400_000L
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(s.number, fontWeight = FontWeight.SemiBold)
            Text(listOfNotNull(fmt.format(Date(s.createdAt)), s.variableSymbol?.let { "VS $it" },
                if (s.isForeign) Money.format(s.payAmount!!, "EUR") else null,
                if (s.bankPaid > 0) tr("přišlo %s", Money.format(s.bankPaid, if (s.isForeign) "EUR" else "Kč")) else null,
                if (overdue) tr("po splatnosti") else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(Money.format(s.transferMissing), fontWeight = FontWeight.Bold)
    }
}
