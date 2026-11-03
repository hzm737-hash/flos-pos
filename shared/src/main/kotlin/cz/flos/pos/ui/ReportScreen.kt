package cz.flos.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import cz.flos.pos.data.CashKind
import cz.flos.pos.data.TipsOwner
import cz.flos.pos.data.CashState
import cz.flos.core.DailyReport
import cz.flos.core.Money
import cz.flos.core.PaymentMethod
import java.time.LocalDate

@Composable
fun ReportScreen(vm: PosViewModel) {
    var date by remember { mutableStateOf(LocalDate.now()) }
    var report by remember { mutableStateOf<DailyReport?>(null) }
    val sales by vm.recentSales.collectAsState()
    val settings by vm.settings.collectAsState()
    LaunchedEffect(date, sales) { report = vm.dailyReport(date) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { date = date.minusDays(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, tr("Předchozí den")) }
            Text("${date.dayOfMonth}. ${date.monthValue}. ${date.year}", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { date = date.plusDays(1) }, enabled = date < LocalDate.now()) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, tr("Další den"))
            }
        }
        val cash by vm.cashState.collectAsState()
        CashCard(vm, cash)
        TransfersCard(vm)
        report?.let { r -> ReportBody(r, settings.vatPayer) { vm.printReport(r) } }
    }
}

/** Cash in the drawer for the current shift, with deposits, withdrawals and the shift close. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CashCard(vm: PosViewModel, cash: CashState) {
    var dialog by remember { mutableStateOf<CashKind?>(null) }
    Card(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Payments, null, tint = MaterialTheme.colorScheme.primary)
                Text(tr("  Hotovost v pokladně"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(Money.format(cash.expected), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (cash.float != 0L) ReportRow(tr("Počáteční stav"), Money.format(cash.float))
            ReportRow(tr("Hotovostní tržby"), Money.format(cash.sales))
            if (cash.refunds != 0L) ReportRow(tr("Storna v hotovosti"), Money.format(-cash.refunds))
            if (cash.cashIn != 0L) ReportRow(tr("Vklady"), Money.format(cash.cashIn))
            if (cash.cashOut != 0L) ReportRow(tr("Výběry"), Money.format(-cash.cashOut))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { dialog = CashKind.IN }) { Icon(Icons.Rounded.Add, null); Text(tr(" Vklad")) }
                FilledTonalButton(onClick = { dialog = CashKind.OUT }) { Icon(Icons.Rounded.Remove, null); Text(tr(" Výběr")) }
                Button(onClick = { dialog = CashKind.CLOSE }) { Icon(Icons.Rounded.Lock, null); Text(tr(" Uzavřít směnu")) }
            }
        }
    }
    dialog?.let { kind -> CashDialog(kind, cash, onDismiss = { dialog = null }) { amount, second, note ->
        if (kind == CashKind.CLOSE) vm.closeShift(amount, second) else vm.cashMovement(kind, amount, note)
        dialog = null
    } }
}

@Composable
private fun CashDialog(kind: CashKind, cash: CashState, onDismiss: () -> Unit, onConfirm: (Long, Long, String) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val parsed = Money.parse(amount)
    val close = kind == CashKind.CLOSE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(kind.label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (close) ReportRow(tr("Očekáváno v pokladně"), Money.format(cash.expected), bold = true)
                OutlinedTextField(amount, { amount = it }, singleLine = true,
                    label = { Text(if (close) tr("Spočítaná hotovost (Kč)") else tr("Částka (Kč)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (close) {
                    parsed?.let { ReportRow(tr("Rozdíl"), Money.format(it - cash.expected)) }
                    OutlinedTextField(second, { second = it }, singleLine = true,
                        label = { Text(tr("Nechat v pokladně na další směnu (Kč)")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                } else {
                    OutlinedTextField(note, { note = it }, singleLine = true, label = { Text(tr("Poznámka")) })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null && (close || parsed > 0), onClick = {
                onConfirm(parsed ?: 0, Money.parse(second) ?: 0, note)
            }) { Text(if (close) tr("Uzavřít směnu") else tr("Uložit")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zrušit")) } },
    )
}

@Composable
private fun ReportBody(r: DailyReport, vatPayer: Boolean, onPrint: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Card(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(top = 12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportRow(tr("Tržba celkem"), Money.format(r.total), bold = true)
                ReportRow(tr("Počet účtenek"), r.receiptCount.toString())
                if (r.cancelledCount > 0) ReportRow(tr("Stornováno"), r.cancelledCount.toString())
                HorizontalDivider()
                PaymentMethod.entries.forEach { ReportRow(tr(it.label), Money.format(r.byPayment[it] ?: 0)) }
                if (r.tips != 0L) {
                    HorizontalDivider()
                    ReportRow(tr("Spropitné (mimo DPH)"), Money.format(r.tips), bold = true)
                    ReportRow(tr("Přijato celkem"), Money.format(r.total + r.tips))
                }
                if (vatPayer && r.byVat.isNotEmpty()) {
                    HorizontalDivider()
                    r.byVat.forEach {
                        ReportRow(tr("Základ %s", it.rate.label), Money.format(it.base))
                        ReportRow(tr("DPH %s", it.rate.label), Money.format(it.vat))
                    }
                }
            }
        }
        Button(onClick = onPrint, modifier = Modifier.padding(top = 16.dp)) {
            Icon(Icons.Filled.Print, null); Text(tr("  Vytisknout uzávěrku"))
        }
    }
}

@Composable
private fun ReportRow(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontWeight = if (bold) FontWeight.Bold else null)
        Spacer(Modifier.weight(1f))
        Text(value, fontWeight = if (bold) FontWeight.Bold else null)
    }
}

/** Transfers still waiting for their money and the monthly tax-free tips limit (7 % of revenue). */
@Composable
private fun TransfersCard(vm: PosViewModel) {
    val waiting by vm.waitingTransfers.collectAsState()
    val settings by vm.settings.collectAsState()
    val sales by vm.recentSales.collectAsState()
    var month by remember { mutableStateOf(0L to 0L) }
    LaunchedEffect(sales) { month = vm.monthTips() }
    val (tips, revenue) = month
    if (waiting.isEmpty() && tips == 0L) return
    Card(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (waiting.isNotEmpty()) {
                ReportRow(tr("Čeká na převod (%d účtenek)", waiting.size), Money.format(waiting.sumOf { it.transferMissing }), bold = true)
            }
            if (tips != 0L) {
                ReportRow(tr("Spropitné tento měsíc"), Money.format(tips), bold = true)
                if (settings.tipsOwner == TipsOwner.STAFF) {
                    val limit = revenue * 7 / 100
                    ReportRow(tr("Limit osvobození (7 %% tržby %s)", Money.format(revenue)), Money.format(limit))
                    if (tips > limit) {
                        Text(tr("Nad limit: %s – tato část se u zaměstnanců daní jako mzda.", Money.format(tips - limit)),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    } else {
                        ReportRow(tr("Zbývá do limitu"), Money.format(limit - tips))
                    }
                }
            }
        }
    }
}
