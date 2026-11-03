package cz.flos.pos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.DeliveryDining
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.flos.core.Money
import cz.flos.core.PaymentMethod
import cz.flos.pos.data.EetRecordEntity
import cz.flos.pos.data.EetStatus
import cz.flos.pos.data.SaleStatus
import cz.flos.pos.data.SaleWithItems
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** Time ranges offered above the receipt list. */
enum class HistoryPeriod(val label: String) {
    TODAY("Dnes"), YESTERDAY("Včera"), WEEK("7 dní"), MONTH("Tento měsíc"), YEAR("Tento rok"), ALL("Vše");

    /** Start (inclusive) and end (exclusive) in epoch milliseconds. */
    fun range(today: LocalDate = LocalDate.now()): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        fun ms(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrow = ms(today.plusDays(1))
        return when (this) {
            TODAY -> ms(today) to tomorrow
            YESTERDAY -> ms(today.minusDays(1)) to ms(today)
            WEEK -> ms(today.minusDays(6)) to tomorrow
            MONTH -> ms(today.withDayOfMonth(1)) to tomorrow
            YEAR -> ms(today.withDayOfYear(1)) to tomorrow
            ALL -> 0L to Long.MAX_VALUE
        }
    }
}

/**
 * Groups for the history filter. Every paid sale belongs to exactly one payment group; voided
 * receipts, unpaid orders and cancelled orders have groups of their own, so each total adds up.
 */
sealed class HistoryFilter(val key: String) {
    data object All : HistoryFilter("all")
    data class Paid(val method: PaymentMethod) : HistoryFilter(method.name)
    data object Unpaid : HistoryFilter("open")
    data object Voided : HistoryFilter("storno")
    data object Cancelled : HistoryFilter("void")

    val label: String
        get() = when (this) {
            All -> tr("Zaplaceno celkem")
            is Paid -> tr(method.label)
            Unpaid -> tr("Nezaplacené")
            Voided -> tr("Stornované")
            Cancelled -> tr("Zrušené objednávky")
        }

    val icon: ImageVector
        get() = when (this) {
            All -> Icons.Rounded.DoneAll
            is Paid -> method.icon
            Unpaid -> Icons.Rounded.DeliveryDining
            Voided -> Icons.Rounded.Undo
            Cancelled -> Icons.Rounded.Block
        }

    fun matches(sw: SaleWithItems): Boolean {
        val s = sw.sale
        return when (this) {
            All -> s.isPaid && !s.cancelled
            is Paid -> s.isPaid && !s.cancelled && s.paymentMethod == method
            Unpaid -> s.isOpen
            Voided -> s.isPaid && s.cancelled
            Cancelled -> s.saleStatus == SaleStatus.VOID
        }
    }

    /** Amount the sale adds to this group: what was received for paid sales, the order total otherwise. */
    fun amountOf(sw: SaleWithItems): Long = if (sw.sale.isPaid) sw.sale.received else sw.sale.total

    companion object {
        val entries: List<HistoryFilter> = listOf(All) + PaymentMethod.entries.map(::Paid) + listOf(Unpaid, Voided, Cancelled)
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: All
    }
}

@Composable
fun HistoryScreen(vm: PosViewModel, onEditOrder: () -> Unit) {
    var period by rememberSaveable { mutableStateOf(HistoryPeriod.TODAY) }
    var filterKey by rememberSaveable { mutableStateOf(HistoryFilter.All.key) }
    val filter = HistoryFilter.of(filterKey)
    val range = remember(period) { period.range() }
    val sales by remember(range) { vm.history(range.first, range.second) }.collectAsState(initial = emptyList())
    var open by remember { mutableStateOf<SaleWithItems?>(null) }
    var confirmCancel by remember { mutableStateOf<SaleWithItems?>(null) }
    val fmt = remember { SimpleDateFormat("d.M.yyyy HH:mm", Locale.forLanguageTag("cs-CZ")) }

    val eetRecords by vm.eetRecent.collectAsState()
    val eetBySale = eetRecords.groupBy { it.saleId }

    // Open orders are always loaded; in a past period only those opened in it are counted.
    val inPeriod = sales.filter { !it.sale.isOpen || (it.sale.openedAt ?: it.sale.createdAt) in range.first until range.second }
    val groups = HistoryFilter.entries.map { f ->
        val matching = inPeriod.filter(f::matches)
        Triple(f, matching.size, matching.sumOf(f::amountOf))
    }
    val shown = inPeriod.filter(filter::matches)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { EetQueueBanner(vm) }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(HistoryPeriod.entries) { p ->
                    FilterChip(selected = period == p, onClick = { period = p }, label = { Text(tr(p.label)) }, shape = RoundedCornerShape(50))
                }
            }
        }
        item {
            // One card per group with its count and total; tapping a card filters the list.
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(groups, key = { it.first.key }) { (f, count, total) ->
                    TotalCard(f, count, total, selected = f == filter) { filterKey = f.key }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(filter.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(tr("%d × · %s", shown.size, Money.format(shown.sumOf(filter::amountOf))), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
        if (shown.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(tr("Žádné doklady v tomto výběru"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(shown, key = { it.sale.id }) { sw ->
            SaleRow(sw, fmt, eetBySale[sw.sale.id].orEmpty()) { open = sw }
        }
    }

    open?.let { sw ->
        val s = sw.sale
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(if (s.isPaid) tr("Účtenka %s", s.number) else tr("Objednávka č. %d", s.orderNumber ?: 0)) },
            text = {
                var preview by remember(sw) { mutableStateOf("") }
                LaunchedEffect(sw) { preview = vm.receiptPreview(sw) }
                Text(
                    preview,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                when {
                    s.isPaid -> Button(onClick = { vm.reprint(sw) }) { Text(tr("Tisknout kopii")) }
                    s.isOpen -> Button(onClick = { vm.editOrder(sw); open = null; onEditOrder() }) { Text(tr("Otevřít v košíku")) }
                    else -> {}
                }
            },
            dismissButton = {
                Row {
                    if (s.isPaid && !s.cancelled) TextButton(onClick = { confirmCancel = sw; open = null }) { Text(tr("Storno")) }
                    TextButton(onClick = { open = null }) { Text(tr("Zavřít")) }
                }
            },
        )
    }
    confirmCancel?.let { sw ->
        AlertDialog(
            onDismissRequest = { confirmCancel = null },
            title = { Text(tr("Stornovat účtenku %s?", sw.sale.number)) },
            text = { Text(tr("Účtenka zůstane v historii označená jako storno a nezapočítá se do tržby.")) },
            confirmButton = { Button(onClick = { vm.cancelSale(sw); confirmCancel = null }) { Text(tr("Stornovat")) } },
            dismissButton = { TextButton(onClick = { confirmCancel = null }) { Text(tr("Zpět")) } },
        )
    }
}

@Composable
private fun TotalCard(f: HistoryFilter, count: Int, total: Long, selected: Boolean, onClick: () -> Unit) {
    val colors = if (selected) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    }
    Card(onClick = onClick, shape = RoundedCornerShape(20.dp), colors = colors, modifier = Modifier.width(158.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(f.icon, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(f.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
            Text(Money.format(total), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(tr("%d dokladů", count), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SaleRow(sw: SaleWithItems, fmt: SimpleDateFormat, eet: List<EetRecordEntity>, onClick: () -> Unit) {
    val s = sw.sale
    val icon = when {
        s.isOpen -> Icons.Rounded.DeliveryDining
        s.saleStatus == SaleStatus.VOID -> Icons.Rounded.Block
        s.cancelled -> Icons.Rounded.Undo
        else -> s.paymentMethod?.icon ?: Icons.Rounded.DoneAll
    }
    val muted = s.cancelled || s.saleStatus == SaleStatus.VOID
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = if (s.isOpen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp),
            ) { Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp)) } }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (s.isPaid) s.number else tr("Objednávka č. %d", s.orderNumber ?: 0),
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (muted) TextDecoration.LineThrough else null,
                )
                val status = when {
                    s.isOpen -> tr("Nezaplaceno")
                    s.saleStatus == SaleStatus.VOID -> tr("Zrušeno")
                    s.cancelled -> tr("STORNO") + " " + (s.stornoNumber ?: "")
                    else -> s.paymentMethod?.let { tr(it.label) } ?: ""
                }
                Text(
                    listOf(fmt.format(Date(s.createdAt)), status, s.note.take(40)).filter { it.isNotBlank() }.joinToString(" · ") + eetLabel(eet),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Text(Money.format(if (s.isPaid) s.received else s.total), fontWeight = FontWeight.Bold)
        }
    }
}

/** Short EET state for the receipt list. */
private fun eetLabel(records: List<EetRecordEntity>): String {
    val r = records.firstOrNull { !it.storno } ?: return ""
    return when (r.status) {
        EetStatus.CONFIRMED -> " · EET ✓"
        EetStatus.PENDING -> tr(" · EET čeká")
        EetStatus.REJECTED -> tr(" · EET odmítnuto")
        EetStatus.VERIFIED -> tr(" · EET ověřeno")
    }
}
