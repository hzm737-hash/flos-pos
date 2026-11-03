package cz.flos.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Switch
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
import cz.flos.core.CzechBankAccount
import cz.flos.core.Money
import cz.flos.core.PaymentMethod

/**
 * Takes the payment for [total]. [onConfirm] gets the payment type and, for cash, the amount
 * handed over (or null).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaymentDialog(
    vm: PosViewModel,
    total: Long,
    title: String = tr("Platba"),
    onDismiss: () -> Unit,
    onConfirm: (PaymentMethod, Long?) -> Unit,
) {
    val s by vm.settings.collectAsState()
    var method by remember { mutableStateOf(PaymentMethod.CASH) }
    var given by remember { mutableStateOf("") }
    val qr = remember(total) { vm.preparePaymentQr(total) }
    LaunchedEffect(Unit) { vm.setForeignTransfer(false) }
    val cashDue = Money.roundToCrowns(total)
    val givenHaler = Money.parse(given)
    val change = givenHaler?.let { it - cashDue }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title + " " + Money.format(if (method == PaymentMethod.CASH) cashDue else total)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PaymentMethod.entries.forEach { m ->
                        FilterChip(selected = method == m, onClick = { method = m },
                            label = { Text(tr(m.label)) }, leadingIcon = { Icon(m.icon, null) })
                    }
                }
                when (method) {
                    PaymentMethod.CASH -> {
                        OutlinedTextField(
                            value = given,
                            onValueChange = { given = it },
                            label = { Text(tr("Přijato od zákazníka (Kč)")) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val quick = listOf(cashDue, 10000L, 20000L, 50000L, 100000L, 200000L).filter { it >= cashDue }.distinct().take(4)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            quick.forEach { amount ->
                                FilterChip(selected = givenHaler == amount, onClick = { given = Money.format(amount, "") },
                                    label = { Text(if (amount == cashDue) tr("Přesně") else Money.format(amount, "").substringBefore(',')) })
                            }
                        }
                        if (change != null) {
                            Text(
                                if (change >= 0) tr("Vrátit: %s", Money.format(change)) else tr("Chybí: %s", Money.format(-change)),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (change >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    PaymentMethod.CARD -> Hint(tr("Proveďte platbu na platebním terminálu a po schválení potvrďte."))
                    PaymentMethod.MEAL_VOUCHER -> Hint(tr("Přijměte stravenky v hodnotě nákupu. Tržba se eviduje v EET jako ostatní platby."))
                    PaymentMethod.INVOICE -> Hint(tr("Zákazník zaplatí na fakturu. Fakturu vystavte v účetním programu. Tržba placená fakturou se v EET neeviduje."))
                    PaymentMethod.BANK_TRANSFER -> {
                        val iban = s.iban
                        if (iban == null) Hint(tr("Pro platbu převodem zadejte číslo účtu v Nastavení."), error = true)
                        else {
                            Text(CzechBankAccount.fromIban(iban)?.toString() ?: CzechBankAccount.formatIban(iban), style = MaterialTheme.typography.titleMedium)
                            qr?.variableSymbol?.let { Text("VS: $it") }
                            Hint(tr("Potvrďte, až zákazník převod odešle ze své banky."))
                        }
                    }
                    PaymentMethod.QR_TRANSFER -> {
                        val foreign by vm.foreignTransfer.collectAsState()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("Plátce ze zahraničí (SEPA QR v EUR)"), Modifier.weight(1f))
                            Switch(checked = foreign, onCheckedChange = { vm.setForeignTransfer(it) })
                        }
                        if (foreign) {
                            var epc by remember { mutableStateOf<Pair<cz.flos.core.EpcQr, Double>?>(null) }
                            var loaded by remember { mutableStateOf(false) }
                            LaunchedEffect(total) { epc = vm.prepareForeignQr(total); loaded = true }
                            val e = epc
                            when {
                                e != null -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    QrImage(e.first)
                                    Spacer(Modifier.height(8.dp))
                                    Text(Money.format(e.first.amount, "EUR"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    Text(tr("Kurz 1 EUR = %s Kč · IBAN %s", "%.3f".format(java.util.Locale.ROOT, e.second).replace('.', ','),
                                        CzechBankAccount.formatIban(e.first.iban)), style = MaterialTheme.typography.bodySmall)
                                    Text(tr("Platba z ciziny trvá obvykle 1–2 pracovní dny. Účtenka počká na platbu a spáruje se sama."),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                loaded -> Hint(tr("Kurz EUR se nepodařilo načíst. Zadejte vlastní kurz v Nastavení → Platby z banky."), error = true)
                                else -> Hint(tr("Načítám kurz ČNB…"))
                            }
                        } else if (qr == null) {
                            Hint(tr("Pro QR platbu zadejte číslo účtu v Nastavení."), error = true)
                        } else {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                QrImage(qr)
                                Spacer(Modifier.height(8.dp))
                                Text(tr("Zákazník naskenuje kód v bankovní aplikaci."), style = MaterialTheme.typography.bodySmall)
                                qr.variableSymbol?.let { Text("VS: $it", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val enabled = when (method) {
                PaymentMethod.CASH -> change == null || change >= 0
                PaymentMethod.QR_TRANSFER, PaymentMethod.BANK_TRANSFER -> s.iban != null
                else -> true
            }
            Button(
                enabled = enabled,
                onClick = { onConfirm(method, if (method == PaymentMethod.CASH) givenHaler else null) },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text(if (method == PaymentMethod.QR_TRANSFER) tr("Vytisknout účtenku s QR") else tr("Dokončit")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zpět")) } },
    )
}

@Composable
private fun Hint(text: String, error: Boolean = false) {
    Text(text, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}
