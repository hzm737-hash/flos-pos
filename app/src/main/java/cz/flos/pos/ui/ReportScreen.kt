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
        report?.let { r -> ReportBody(r, settings.vatPayer) { vm.printReport(r) } }
    }
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
