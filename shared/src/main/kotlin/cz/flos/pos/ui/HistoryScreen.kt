package cz.flos.pos.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.ui.text.style.TextAlign

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import java.time.Instant
import java.time.format.DateTimeFormatter
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
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilledTonalButton
import cz.flos.pos.data.TransferStatus
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
    data object AwaitingTransfer : HistoryFilter("transfer")
    data object Tips : HistoryFilter("tips")

    val label: String
        get() = when (this) {
            All -> tr("Zaplaceno celkem")
            is Paid -> tr(method.label)
            Unpaid -> tr("Nezaplacené")
            Voided -> tr("Stornované")
            Cancelled -> tr("Zrušené objednávky")
            AwaitingTransfer -> tr("Čeká na převod")
            Tips -> tr("Spropitné")
        }

    val icon: ImageVector
        get() = when (this) {
            All -> Icons.Rounded.DoneAll
            is Paid -> method.icon
            Unpaid -> Icons.Rounded.DeliveryDining
            Voided -> Icons.Rounded.Undo
            Cancelled -> Icons.Rounded.Block
            AwaitingTransfer -> Icons.Rounded.HourglassTop
            Tips -> Icons.Rounded.VolunteerActivism
        }

    fun matches(sw: SaleWithItems): Boolean {
        val s = sw.sale
        return when (this) {
            All -> s.isPaid && !s.cancelled
            is Paid -> s.isPaid && !s.cancelled && s.paymentMethod == method
            Unpaid -> s.isOpen
            Voided -> s.isPaid && s.cancelled
            Cancelled -> s.saleStatus == SaleStatus.VOID
            AwaitingTransfer -> s.awaitsTransfer
            Tips -> s.isPaid && !s.cancelled && s.tip > 0
        }
    }

    /** Amount the sale adds to this group: what was received for paid sales, the order total otherwise. */
    fun amountOf(sw: SaleWithItems): Long = when (this) {
        Tips -> sw.sale.tip
        AwaitingTransfer -> sw.sale.transferMissing
        else -> if (sw.sale.isPaid) sw.sale.received else sw.sale.total
    }

    companion object {
        // Lazy on purpose: built eagerly, this list is filled while the first object used (e.g.
        // All, via All.key) is still being created, so it would hold null and crash the screen.
        val entries: List<HistoryFilter> by lazy {
            listOf(All) + PaymentMethod.entries.map(::Paid) + listOf(AwaitingTransfer, Tips, Unpaid, Voided, Cancelled)
        }
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: All
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: PosViewModel, onEditOrder: () -> Unit) {
    var period by rememberSaveable { mutableStateOf(HistoryPeriod.TODAY) }
    // A day or range picked in the calendar (epoch days, inclusive); overrides the period chips.
    var customFrom by rememberSaveable { mutableStateOf<Long?>(null) }
    var customTo by rememberSaveable { mutableStateOf<Long?>(null) }
    var picking by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var everywhere by rememberSaveable { mutableStateOf(true) }
    var filterKey by rememberSaveable { mutableStateOf(HistoryFilter.All.key) }
    val filter = HistoryFilter.of(filterKey)
    val zone = ZoneId.systemDefault()
    val range = remember(period, customFrom, customTo) {
        val f = customFrom; val t = customTo
        if (f != null && t != null) LocalDate.ofEpochDay(f).atStartOfDay(zone).toInstant().toEpochMilli() to
            LocalDate.ofEpochDay(t + 1).atStartOfDay(zone).toInstant().toEpochMilli()
        else period.range()
    }
    val searching = query.isNotBlank()
    val sales by remember(range, query, everywhere) {
        if (query.isBlank()) vm.history(range.first, range.second)
        else if (everywhere) vm.searchHistory(query, 0L, Long.MAX_VALUE)
        else vm.searchHistory(query, range.first, range.second)
    }.collectAsState(initial = emptyList())
    var openId by remember { mutableStateOf<Long?>(null) }
    var bankDialog by remember { mutableStateOf(false) }
    val unmatched by vm.unmatchedPayments.collectAsState()
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.forLanguageTag("cs-CZ")) }
    val dayFmt = remember { DateTimeFormatter.ofPattern("EEEE d. M. yyyy", Locale.forLanguageTag("cs-CZ")) }

    val eetRecords by vm.eetRecent.collectAsState()
    val eetBySale = eetRecords.groupBy { it.saleId }

    // Open orders are always loaded; in a past period only those opened in it are counted.
    val inPeriod = if (searching) sales
        else sales.filter { !it.sale.isOpen || (it.sale.openedAt ?: it.sale.createdAt) in range.first until range.second }
    val groups = HistoryFilter.entries.map { f ->
        val matching = inPeriod.filter(f::matches)
        Triple(f, matching.size, matching.sumOf(f::amountOf))
    }
    // While searching, every match is shown whatever the group; otherwise the chosen group filters the list.
    val shown = if (searching) inPeriod else inPeriod.filter(filter::matches)
    val byDay = shown.groupBy { Instant.ofEpochMilli(it.sale.createdAt).atZone(zone).toLocalDate() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { EetQueueBanner(vm) }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, tr("Smazat")) } },
                    placeholder = { Text(tr("Číslo účtenky, částka, položka, stůl, zákazník…"), maxLines = 1) },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f),
                )
                BadgedBox(badge = { if (unmatched.isNotEmpty()) Badge { Text(unmatched.size.toString()) } }) {
                    FilledTonalIconButton(onClick = { bankDialog = true }) { Icon(Icons.Rounded.AccountBalance, tr("Platby z banky")) }
                }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (searching) item {
                    FilterChip(selected = everywhere, onClick = { everywhere = !everywhere }, label = { Text(tr("Hledat v celé historii")) },
                        shape = RoundedCornerShape(50))
                }
                val custom = customFrom != null && customTo != null
                if (!searching || !everywhere) {
                    item {
                        val f = customFrom; val t = customTo
                        val df = DateTimeFormatter.ofPattern("d. M. yyyy")
                        FilterChip(
                            selected = custom, onClick = { picking = true }, shape = RoundedCornerShape(50),
                            leadingIcon = { Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(18.dp)) },
                            label = {
                                Text(if (f != null && t != null) {
                                    if (f == t) df.format(LocalDate.ofEpochDay(f)) else df.format(LocalDate.ofEpochDay(f)) + " – " + df.format(LocalDate.ofEpochDay(t))
                                } else tr("Vybrat datum"))
                            },
                        )
                    }
                    if (custom) item {
                        // Step the picked day (or range) backwards and forwards.
                        Row {
                            val len = customTo!! - customFrom!! + 1
                            IconButton(onClick = { customFrom = customFrom!! - len; customTo = customTo!! - len }) {
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, tr("Předchozí"))
                            }
                            IconButton(onClick = { customFrom = customFrom!! + len; customTo = customTo!! + len }) {
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, tr("Další"))
                            }
                        }
                    }
                    items(HistoryPeriod.entries) { p ->
                        FilterChip(selected = !custom && period == p, onClick = { period = p; customFrom = null; customTo = null },
                            label = { Text(tr(p.label)) }, shape = RoundedCornerShape(50))
                    }
                }
            }
        }
        if (!searching) item {
            // One card per group with its count and total; tapping a card filters the list.
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(groups, key = { it.first.key }) { (f, count, total) ->
                    TotalCard(f, count, total, selected = f == filter) { filterKey = f.key }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (searching) tr("Nalezeno") else filter.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(tr("%d × · %s", shown.size, Money.format(shown.sumOf(filter::amountOf))), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (searching && shown.size >= 500) Text(tr("Zobrazeno prvních 500 výsledků, upřesněte hledání."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (shown.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(if (searching) tr("Nic nenalezeno") else tr("Žádné doklady v tomto výběru"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        byDay.forEach { (day, list) ->
            item(key = "day-$day") {
                val paid = list.filter { it.sale.isPaid && !it.sale.cancelled }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(dayFmt.format(day).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    Text(tr("%d dokladů", list.size) + " · " + Money.format(paid.sumOf { it.sale.received }), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(list, key = { it.sale.id }) { sw ->
                SaleRow(sw, fmt, eetBySale[sw.sale.id].orEmpty()) { openId = sw.sale.id }
            }
        }
    }

    if (picking) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = customFrom?.let { it * 86_400_000L },
            initialSelectedEndDateMillis = customTo?.let { it * 86_400_000L },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(enabled = state.selectedStartDateMillis != null, onClick = {
                    val start = state.selectedStartDateMillis!! / 86_400_000L
                    customFrom = start
                    customTo = (state.selectedEndDateMillis?.div(86_400_000L) ?: start).coerceAtLeast(start)
                    picking = false
                }) { Text(tr("Zobrazit")) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(tr("Zrušit")) } },
        ) {
            DateRangePicker(
                state = state, modifier = Modifier.weight(1f),
                title = { Text(tr("Vyberte den nebo období (od – do)"), Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) },
            )
        }
    }

    openId?.let { id ->
        val index = shown.indexOfFirst { it.sale.id == id }
        val sw = shown.getOrNull(index)
        if (sw != null) ReceiptDetailDialog(
            vm, sw, eetBySale[sw.sale.id].orEmpty(),
            onDismiss = { openId = null },
            onEditOrder = { vm.editOrder(sw); openId = null; onEditOrder() },
            onRepeat = { vm.repeatSale(sw); openId = null; onEditOrder() },
            onOpenOther = { other -> openId = other.sale.id },
            // The list is newest first: "previous" is the older receipt below.
            onPrevious = shown.getOrNull(index + 1)?.let { p -> { openId = p.sale.id } },
            onNext = shown.getOrNull(index - 1)?.let { n -> { openId = n.sale.id } },
        )
    }
    if (bankDialog) BankPaymentsDialog(vm, onDismiss = { bankDialog = false })
}

/** Return some items of a paid receipt: a refund document (vratka) with negative quantities. */
@Composable
internal fun RefundDialog(vm: PosViewModel, sw: SaleWithItems, onDismiss: () -> Unit) {
    var left by remember(sw) { mutableStateOf<List<Double>?>(null) }
    LaunchedEffect(sw) { left = vm.refundable(sw) }
    var chosen by remember(sw) { mutableStateOf(mapOf<Int, Double>()) }
    val cart = sw.toCart()
    val amount = sw.items.indices.sumOf { i ->
        val q = chosen[i] ?: 0.0
        if (q < cz.flos.core.Qty.MIN) 0L else cart.items[i].copy(quantity = q).total
    }.let { Math.round(it * (100 - sw.sale.discountPercent) / 100.0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Vrátit položky z účtenky %s", sw.sale.number)) },
        text = {
            val l = left
            if (l == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Vyberte vrácené kusy. Vznikne doklad vratky se zápornou částkou (zaeviduje se i v EET) a peníze vrátíte stejným způsobem, jakým zákazník platil."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    itemsIndexed(sw.items) { i, item ->
                        val q = chosen[i] ?: 0.0
                        val max = l.getOrElse(i) { 0.0 }
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, fontWeight = FontWeight.Medium)
                                Text(tr("lze vrátit %s z %s", cz.flos.core.Qty.format(max), cz.flos.core.Qty.format(item.quantity)), style = MaterialTheme.typography.bodySmall)
                            }
                            QtyStepper(q, max) { chosen = chosen + (i to it) }
                        }
                    }
                }
                Text(tr("Vrátit zákazníkovi: %s", Money.format(amount)), fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            Button(onClick = { vm.refund(sw, chosen.filterValues { it >= cz.flos.core.Qty.MIN }); onDismiss() }, enabled = amount > 0 || chosen.values.any { it >= cz.flos.core.Qty.MIN }) {
                Text(tr("Vrátit"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zrušit")) } },
    )
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
                    else -> listOfNotNull(
                        if (s.isRefund) tr("Vratka") else null,
                        s.tableName?.let { tr("Stůl %s", it) },
                        s.paymentMethod?.let { tr(it.label) },
                        s.transferStatus?.let { t -> if (t == TransferStatus.UNDERPAID) tr("chybí %s", Money.format(s.transferMissing)) else tr(t.label) },
                        if (s.tip > 0) tr("spropitné %s", Money.format(s.tip)) else null,
                    ).joinToString(" · ")
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
internal fun eetLabel(records: List<EetRecordEntity>): String {
    val r = records.firstOrNull { !it.storno } ?: return ""
    return when (r.status) {
        EetStatus.CONFIRMED -> " · EET ✓"
        EetStatus.PENDING -> tr(" · EET čeká")
        EetStatus.REJECTED -> tr(" · EET odmítnuto")
        EetStatus.VERIFIED -> tr(" · EET ověřeno")
    }
}
