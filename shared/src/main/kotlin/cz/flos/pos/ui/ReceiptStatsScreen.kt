package cz.flos.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.flos.core.Money
import cz.flos.core.ReceiptStatistics
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Receipt statistics: the same periods as Statistics, but about the receipts themselves —
 * how many were issued and when, how big they are, how many items they hold, and the largest ones.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReceiptStatsScreen(vm: PosViewModel) {
    var period by rememberSaveable { mutableStateOf(StatsPeriod.WEEK) }
    var stats by remember { mutableStateOf<ReceiptStatistics?>(null) }
    val range = remember(period) { period.range() }
    LaunchedEffect(range) {
        stats = null
        stats = runCatching { vm.receiptStatistics(range.first, range.second) }.getOrNull()
    }
    val wide = isWide()
    val count = { n: Long -> n.toString() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(StatsPeriod.entries) { p ->
                    FilterChip(selected = period == p, onClick = { period = p }, label = { Text(tr(p.label)) }, shape = RoundedCornerShape(50))
                }
            }
        }
        val s = stats
        if (s == null) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            return@LazyColumn
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Kpi(tr("Účtenek"), s.receipts.toString())
                Kpi(tr("Průměrná účtenka"), Money.format(s.average))
                Kpi(tr("Střední účtenka (medián)"), Money.format(s.median))
                Kpi(tr("Největší účtenka"), Money.format(s.largest.firstOrNull()?.amount ?: 0L))
                Kpi(tr("Nejmenší účtenka"), Money.format(s.smallest))
                Kpi(tr("Kusů na účtenku"), "%d,%d".format(s.itemsPerReceiptX10 / 10, s.itemsPerReceiptX10 % 10))
                s.busiestHour?.let { Kpi(tr("Nejrušnější hodina"), "%d:00–%d:00".format(it, it + 1)) }
                if (s.refunds > 0) Kpi(tr("Vratek"), s.refunds.toString())
                if (s.stornos > 0) Kpi(tr("Storen"), s.stornos.toString())
            }
        }
        if (s.countByDay.size > 1) item {
            val fmt = DateTimeFormatter.ofPattern("d.M.")
            ChartCard(tr("Účtenky po dnech")) {
                Bars(s.countByDay.map { it.second }, s.countByDay.map { fmt.format(it.first) }, count)
            }
        }
        item {
            ChartCard(tr("Účtenky podle hodin")) {
                val hours = s.countByHour.withIndex().filter { it.value != 0L }.map { it.index }
                val first = (hours.minOrNull() ?: 8).coerceAtMost(8)
                val last = (hours.maxOrNull() ?: 20).coerceAtLeast(20)
                Bars(s.countByHour.subList(first, last + 1), (first..last).map { "$it" }, count)
            }
        }
        if (s.countByDay.size >= 7) item {
            val names = listOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne")
            ChartCard(tr("Účtenky podle dnů v týdnu")) {
                Bars(DayOfWeek.entries.map { s.countByWeekday[it] ?: 0L }, names.map { tr(it) }, count)
            }
        }
        item {
            ChartCard(tr("Velikost účtenek")) {
                Bars(s.sizes.map { it.count }, s.sizes.map { b -> b.toCrowns?.let { "<$it" } ?: ">${b.fromCrowns}" }, count)
                Text(tr("Částky v Kč"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LargestCard(s, Modifier.weight(1f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RankCard(tr("Účtenky podle způsobu platby"), s.byPayment.map { it.copy(label = tr(it.label)) })
                    RankCard(tr("Účtenky podle obsluhy"), s.byCashier)
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LargestCard(s)
                RankCard(tr("Účtenky podle způsobu platby"), s.byPayment.map { it.copy(label = tr(it.label)) })
                RankCard(tr("Účtenky podle obsluhy"), s.byCashier)
            }
        }
    }
}

@Composable
private fun LargestCard(s: ReceiptStatistics, modifier: Modifier = Modifier) {
    val fmt = remember { DateTimeFormatter.ofPattern("d. M. HH:mm") }
    Card(shape = RoundedCornerShape(20.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Největší účtenky"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (s.largest.isEmpty()) Text(tr("Žádná data"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.largest.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.number, fontWeight = FontWeight.SemiBold)
                        Text(fmt.format(Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault())) + " · " + tr("%d ks", r.items) + " · " + tr(r.payment.label),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(Money.format(r.amount), fontWeight = FontWeight.Bold)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
