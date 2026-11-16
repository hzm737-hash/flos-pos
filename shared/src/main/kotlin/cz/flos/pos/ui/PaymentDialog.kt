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
import androidx.compose.runtime.DisposableEffect
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
    /** When set, the dialog also offers a mixed payment (vouchers, card and the rest in cash). */
    onMixed: ((List<cz.flos.core.PaymentPart>, Long?) -> Unit)? = null,
    /** Note of the open order being paid: a web order's QR payment keeps the variable symbol from its ticket. */
    orderNote: String? = null,
    onConfirm: (PaymentMethod, Long?) -> Unit,
) {
    val s by vm.settings.collectAsState()
    // Only the buttons the owner left switched on (Settings → Platby); never an empty row.
    val shown = PaymentMethod.entries.filter { it.name !in s.hiddenPaymentSet }.ifEmpty { listOf(PaymentMethod.CASH) }
    var method by remember { mutableStateOf(if (PaymentMethod.CASH in shown) PaymentMethod.CASH else shown.first()) }
    var mixed by remember { mutableStateOf(false) }
    var cardPart by remember { mutableStateOf("") }
    var voucherPart by remember { mutableStateOf("") }
    val cardHaler = Money.parse(cardPart) ?: 0L
    val voucherHaler = Money.parse(voucherPart) ?: 0L
    // The rest of a mixed payment is paid in cash (as before) or, when chosen, by QR payment; then the cash is typed in.
    var restByQr by remember { mutableStateOf(false) }
    var cashFixedText by remember { mutableStateOf("") }
    val cashFixed = Money.parse(cashFixedText) ?: 0L
    val qrPart = if (restByQr) total - cardHaler - voucherHaler - cashFixed else 0L
    val cashPart = if (restByQr) cashFixed else total - cardHaler - voucherHaler
    val mixedParts = listOf(
        cz.flos.core.PaymentPart(PaymentMethod.MEAL_VOUCHER, voucherHaler),
        cz.flos.core.PaymentPart(PaymentMethod.CARD, cardHaler),
        cz.flos.core.PaymentPart(PaymentMethod.QR_TRANSFER, qrPart),
        cz.flos.core.PaymentPart(PaymentMethod.CASH, cashPart),
    ).filter { it.amount > 0 }
    val mixedQr = remember(qrPart, orderNote) { if (restByQr && qrPart > 0) vm.preparePaymentQr(qrPart, orderNote) else null }
    var given by remember { mutableStateOf("") }
    val qr = remember(total, orderNote) { vm.preparePaymentQr(total, orderNote) }
    LaunchedEffect(Unit) { vm.setForeignTransfer(false) }
    val cashDue = Money.roundToCrowns(total)
    val givenHaler = Money.parse(given)
    val change = givenHaler?.let { it - cashDue }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title + " " + Money.format(if (method == PaymentMethod.CASH && !mixed) cashDue else total)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    shown.forEach { m ->
                        FilterChip(selected = method == m && !mixed, onClick = { method = m; mixed = false },
                            label = { Text(tr(m.label)) }, leadingIcon = { Icon(m.icon, null) })
                    }
                    if (onMixed != null && "MIXED" !in s.hiddenPaymentSet) FilterChip(selected = mixed, onClick = { mixed = true }, label = { Text(tr("Kombinovaná")) })
                }
                if (mixed) {
                    // QR payment needs the account number (Settings); without it the rest is cash as before.
                    if (s.iban != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Zbytek doplatí:"))
                        FilterChip(selected = !restByQr, onClick = { restByQr = false }, label = { Text(tr("Hotově")) })
                        FilterChip(selected = restByQr, onClick = { restByQr = true }, label = { Text(tr("QR platbou")) },
                            leadingIcon = { Icon(PaymentMethod.QR_TRANSFER.icon, null) })
                    }
                    if (restByQr) Hint(tr("Zadejte, kolik zákazník platí stravenkami, kartou a hotově. Zbytek zaplatí QR platbou."))
                    else Hint(tr("Zadejte, kolik zákazník platí stravenkami a kartou. Zbytek zaplatí hotově."))
                    OutlinedTextField(voucherPart, { voucherPart = it }, label = { Text(tr("Stravenky (Kč)")) }, singleLine = true,
                        isError = voucherPart.isNotBlank() && Money.parse(voucherPart) == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(cardPart, { cardPart = it }, label = { Text(tr("Kartou (Kč)")) }, singleLine = true,
                        isError = cardPart.isNotBlank() && Money.parse(cardPart) == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                    if (restByQr) {
                        OutlinedTextField(cashFixedText, { cashFixedText = it }, label = { Text(tr("Hotově (Kč)")) }, singleLine = true,
                            isError = cashFixedText.isNotBlank() && Money.parse(cashFixedText) == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                        Text(if (qrPart >= 0) tr("QR platbou: %s", Money.format(qrPart)) else tr("Zadáno víc, než je k úhradě: %s", Money.format(-qrPart)),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            color = if (qrPart >= 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                        mixedQr?.let { q ->
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                DisposableEffect(q) { vm.showOnDisplay(q); onDispose { vm.showOnDisplay(null) } }
                                QrImage(q)
                                Spacer(Modifier.height(8.dp))
                                Text(Money.format(qrPart), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text(tr("Zákazník naskenuje kód v bankovní aplikaci."), style = MaterialTheme.typography.bodySmall)
                                q.variableSymbol?.let { Text("VS: $it", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    } else Text(if (cashPart >= 0) tr("Hotově: %s", Money.format(cashPart)) else tr("Zadáno víc, než je k úhradě: %s", Money.format(-cashPart)),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = if (cashPart >= 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    if (cashPart > 0) {
                        OutlinedTextField(given, { given = it }, label = { Text(tr("Přijato hotově od zákazníka (Kč)")) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                        givenHaler?.let { g ->
                            Text(if (g >= cashPart) tr("Vrátit: %s", Money.format(g - cashPart)) else tr("Chybí: %s", Money.format(cashPart - g)),
                                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                                color = if (g >= cashPart) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        }
                    }
                } else when (method) {
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
                        // Cash in euros (Pokročilé nastavení): the amount due in EUR, and what was received
                        // in EUR becomes the crowns handed over, so the change is given in Kč.
                        if (s.on(cz.flos.pos.data.AdvFeature.EUR_CASH)) {
                            var rate by remember { mutableStateOf(s.eurCashRateOverride) }
                            LaunchedEffect(Unit) { if (rate == null) rate = vm.eurRate() }
                            var eur by remember { mutableStateOf("") }
                            val r = rate
                            if (r == null) Hint(tr("Načítám kurz EUR…"))
                            else {
                                // Rounded up to 10 cents so the business never gets less than the bill.
                                val dueCents = Math.ceil(cashDue / r / 10.0).toLong() * 10
                                Text(tr("V eurech: %s (kurz 1 EUR = %s Kč)", Money.format(dueCents, "EUR"),
                                    "%.2f".format(java.util.Locale.ROOT, r).replace('.', ',')), fontWeight = FontWeight.Bold)
                                OutlinedTextField(
                                    value = eur,
                                    onValueChange = { v -> eur = v; Money.parse(v)?.let { c -> given = Money.format(Math.round(c * r), "") } },
                                    label = { Text(tr("Přijato v EUR")) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                                )
                            }
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
                                    DisposableEffect(e) { vm.showOnDisplay(e.first); onDispose { vm.showOnDisplay(null) } }
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
                                DisposableEffect(qr) { vm.showOnDisplay(qr); onDispose { vm.showOnDisplay(null) } }
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
            if (mixed) {
                val ok = cashPart >= 0 && qrPart >= 0 && (!restByQr || s.iban != null) && mixedParts.size >= 2 &&
                    (givenHaler == null || cashPart <= 0 || givenHaler >= cashPart)
                Button(enabled = ok, onClick = { onMixed?.invoke(mixedParts, givenHaler.takeIf { cashPart > 0 }) }, modifier = Modifier.padding(start = 8.dp)) {
                    Text(tr("Dokončit"))
                }
            } else {
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
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zpět")) } },
    )
}

@Composable
private fun Hint(text: String, error: Boolean = false) {
    Text(text, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}
