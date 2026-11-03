package cz.flos.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.flos.core.Cart
import cz.flos.core.Money
import cz.flos.core.PaymentMethod

@Composable
fun PaymentDialog(vm: PosViewModel, cart: Cart, onDismiss: () -> Unit, onPaid: () -> Unit) {
    var method by remember { mutableStateOf(PaymentMethod.CASH) }
    var given by remember { mutableStateOf("") }
    val qr = remember(cart) { vm.preparePaymentQr() }
    val cashDue = Money.roundToCrowns(cart.total)
    val givenHaler = Money.parse(given)
    val change = givenHaler?.let { it - cashDue }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Platba %s", Money.format(if (method == PaymentMethod.CASH) cashDue else cart.total))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = method == PaymentMethod.CASH, onClick = { method = PaymentMethod.CASH },
                        label = { Text(tr("Hotově")) }, leadingIcon = { Icon(Icons.Filled.Payments, null) })
                    FilterChip(selected = method == PaymentMethod.CARD, onClick = { method = PaymentMethod.CARD },
                        label = { Text(tr("Kartou")) }, leadingIcon = { Icon(Icons.Filled.CreditCard, null) })
                    FilterChip(selected = method == PaymentMethod.QR_TRANSFER, onClick = { method = PaymentMethod.QR_TRANSFER },
                        label = { Text(tr("QR")) }, leadingIcon = { Icon(Icons.Filled.QrCode2, null) })
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
                    PaymentMethod.CARD -> Text(tr("Proveďte platbu na platebním terminálu a po schválení potvrďte."))
                    PaymentMethod.QR_TRANSFER -> {
                        if (qr == null) {
                            Text(
                                tr("Pro QR platbu zadejte číslo účtu v Nastavení."),
                                color = MaterialTheme.colorScheme.error,
                            )
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
                PaymentMethod.CARD -> true
                PaymentMethod.QR_TRANSFER -> qr != null
            }
            Button(
                enabled = enabled,
                onClick = {
                    vm.checkout(method, if (method == PaymentMethod.CASH) givenHaler else null, onPaid)
                },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text(if (method == PaymentMethod.QR_TRANSFER) tr("Vytisknout účtenku s QR") else tr("Dokončit")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zpět")) } },
    )
}
