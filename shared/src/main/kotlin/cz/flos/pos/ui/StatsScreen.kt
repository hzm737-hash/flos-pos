package cz.flos.pos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.flos.core.Money
import cz.flos.core.Ranked
import cz.flos.core.Statistics
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Periods offered on the statistics screen. */
enum class StatsPeriod(val label: String) {
    TODAY("Dnes"), YESTERDAY("Včera"), WEEK("7 dní"), MONTH("Tento měsíc"), LAST_MONTH("Minulý měsíc"), DAYS30("30 dní"), YEAR("Tento rok");

    fun range(today: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate> = when (this) {
        TODAY -> today to today
        YESTERDAY -> today.minusDays(1) to today.minusDays(1)
        WEEK -> today.minusDays(6) to today
        MONTH -> today.withDayOfMonth(1) to today
        LAST_MONTH -> today.minusMonths(1).withDayOfMonth(1).let { it to it.plusMonths(1).minusDays(1) }
        DAYS30 -> today.minusDays(29) to today
        YEAR -> today.withDayOfYear(1) to today
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(vm: PosViewModel) {
    var period by rememberSaveable { mutableStateOf(StatsPeriod.WEEK) }
    var stats by remember { mutableStateOf<Statistics?>(null) }
    val range = remember(period) { period.range() }
    LaunchedEffect(range) {
        stats = null
        stats = runCatching { vm.statistics(range.first, range.second) }.getOrNull()
    }
    val wide = isWide()

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
                val change = s.change
                Kpi(tr("Tržba"), Money.format(s.revenue), change?.let { (if (it >= 0) "▲ " else "▼ ") + String.format("%.0f %%", kotlin.math.abs(it)) + " " + tr("proti předchozímu období") },
                    if ((change ?: 0.0) >= 0) FlosMint else FlosCoral)
                Kpi(tr("Účtenek"), s.receipts.toString())
                Kpi(tr("Průměrná účtenka"), Money.format(s.average))
                Kpi(tr("Prodaných kusů"), s.itemsSold.toString())
                if (s.tips > 0) Kpi(tr("Spropitné"), Money.format(s.tips))
                if (s.refunds > 0) Kpi(tr("Vratky"), Money.format(s.refunds))
                if (s.stornoCount > 0) Kpi(tr("Storna"), s.stornoCount.toString())
            }
        }
        if (s.byDay.size > 1) item {
            val fmt = DateTimeFormatter.ofPattern("d.M.")
            ChartCard(tr("Tržba po dnech")) {
                Bars(s.byDay.map { it.second }, s.byDay.map { fmt.format(it.first) })
            }
        }
        item {
            ChartCard(tr("Tržba podle hodin")) {
                val hours = s.byHour.withIndex().filter { it.value != 0L }.map { it.index }
                val first = (hours.minOrNull() ?: 8).coerceAtMost(8)
                val last = (hours.maxOrNull() ?: 20).coerceAtLeast(20)
                Bars(s.byHour.subList(first, last + 1), (first..last).map { "$it" })
            }
        }
        if (s.byDay.size >= 7) item {
            val names = listOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne")
            ChartCard(tr("Tržba podle dnů v týdnu")) {
                Bars(DayOfWeek.entries.map { s.byWeekday[it] ?: 0L }, names.map { tr(it) })
            }
        }
        item {
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RankCard(tr("Nejprodávanější produkty"), s.topProducts.take(10), Modifier.weight(1f), units = true)
                RankCard(tr("Podle kategorií"), s.byCategory, Modifier.weight(1f), units = true)
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RankCard(tr("Nejprodávanější produkty"), s.topProducts.take(10), units = true)
                RankCard(tr("Podle kategorií"), s.byCategory, units = true)
            }
        }
        item {
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RankCard(tr("Podle způsobu platby"), s.byPayment.map { it.copy(label = tr(it.label)) }, Modifier.weight(1f))
                RankCard(tr("Podle obsluhy"), s.byCashier, Modifier.weight(1f))
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RankCard(tr("Podle způsobu platby"), s.byPayment.map { it.copy(label = tr(it.label)) })
                RankCard(tr("Podle obsluhy"), s.byCashier)
            }
        }
    }
}

@Composable
internal fun Kpi(label: String, value: String, sub: String? = null, subColor: Color = Color.Unspecified) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).width(150.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = subColor, maxLines = 2)
        }
    }
}

@Composable
internal fun ChartCard(title: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

/** A simple bar chart: one bar per value, labels under the bars (only some shown when there are many). */
@Composable
internal fun Bars(values: List<Long>, labels: List<String>, format: (Long) -> String = { Money.format(it) }) {
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val every = when {
        values.size > 40 -> 7
        values.size > 16 -> 3
        else -> 1
    }
    Text(tr("Nejvíc: %s", format(values.maxOrNull() ?: 0L)), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        values.forEach { v ->
            val fraction = (v.coerceAtLeast(0L).toFloat() / max).coerceIn(0f, 1f)
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                if (fraction > 0f) Box(
                    Modifier.fillMaxWidth().fillMaxHeight(fraction.coerceAtLeast(0.02f))
                        .background(FlosViolet, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)),
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        labels.forEachIndexed { i, l ->
            Text(if (i % every == 0) l else "", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Clip,
                modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun RankCard(title: String, rows: List<Ranked>, modifier: Modifier = Modifier, units: Boolean = false) {
    Card(shape = RoundedCornerShape(20.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (rows.isEmpty()) Text(tr("Žádná data"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            val max = (rows.maxOfOrNull { it.amount } ?: 0L).coerceAtLeast(1L)
            rows.forEach { r ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text((if (units) "${r.quantity} ks · " else "${r.quantity}× · ") + Money.format(r.amount),
                            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(2.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))) {
                        Box(Modifier.fillMaxWidth((r.amount.coerceAtLeast(0L).toFloat() / max).coerceIn(0f, 1f)).height(6.dp)
                            .background(FlosMint, RoundedCornerShape(3.dp)))
                    }
                }
            }
        }
    }
}
