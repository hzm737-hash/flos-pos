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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(vm: PosViewModel, onEditOrder: () -> Unit) {
    var period by rememberSaveable { mutableStateOf(HistoryPeriod.TODAY) }
    var filterKey by rememberSaveable { mutableStateOf(HistoryFilter.All.key) }
    val filter = HistoryFilter.of(filterKey)
    val range = remember(period) { period.range() }
    val sales by remember(range) { vm.history(range.first, range.second) }.collectAsState(initial = emptyList())
    var open by remember { mutableStateOf<SaleWithItems?>(null) }
    var confirmCancel by remember { mutableStateOf<SaleWithItems?>(null) }
    var bankDialog by remember { mutableStateOf(false) }
    var confirmTransfer by remember { mutableStateOf<SaleWithItems?>(null) }
    var editTip by remember { mutableStateOf<SaleWithItems?>(null) }
    var refunding by remember { mutableStateOf<SaleWithItems?>(null) }
    val unmatched by vm.unmatchedPayments.collectAsState()
    val settings by vm.settings.collectAsState()
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
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), horizontalArrangement = Arrangement.End) {
                BadgedBox(badge = { if (unmatched.isNotEmpty()) Badge { Text(unmatched.size.toString()) } }) {
                    FilledTonalButton(onClick = { bankDialog = true }) {
                        Icon(Icons.Rounded.AccountBalance, null, Modifier.size(18.dp)); Text(tr("  Platby z banky"))
                    }
                }
            }
        }
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
                FlowRow {
                    if (s.awaitsTransfer) TextButton(onClick = { confirmTransfer = sw; open = null }) { Text(tr("Platba přišla")) }
                    if (s.isPaid && !s.cancelled && !s.isRefund) TextButton(onClick = { refunding = sw; open = null }) { Text(tr("Vrátit položky")) }
                    if (s.isPaid && !s.cancelled && !s.isRefund) TextButton(onClick = { editTip = sw; open = null }) { Text(tr("Spropitné")) }
                    if (s.isPaid && !s.cancelled) TextButton(onClick = { confirmCancel = sw; open = null }) { Text(tr("Storno")) }
                    TextButton(onClick = { open = null }) { Text(tr("Zavřít")) }
                }
            },
        )
    }
    if (bankDialog) BankPaymentsDialog(vm, onDismiss = { bankDialog = false })
    confirmTransfer?.let { sw ->
        val due = sw.sale.payAmount?.takeIf { sw.sale.isForeign } ?: sw.sale.received
        val cur = if (sw.sale.isForeign) "EUR" else "Kč"
        AmountDialog(
            title = tr("Platba za účtenku %s", sw.sale.number),
            label = if (sw.sale.isForeign) tr("Přišlo na účet (EUR)") else tr("Přišlo na účet (Kč)"),
            initial = due,
            hint = { amount -> if (amount > due) tr("Spropitné: %s", Money.format(amount - due, cur))
                else if (amount < due) tr("Chybí: %s", Money.format(due - amount, cur)) else "" },
            onDismiss = { confirmTransfer = null },
        ) { vm.confirmTransfer(sw.sale, it); confirmTransfer = null }
    }
    editTip?.let { sw ->
        AmountDialog(
            title = tr("Spropitné k účtence %s", sw.sale.number),
            label = tr("Spropitné (Kč)"),
            initial = sw.sale.tip,
            hint = { tipsHint(settings.tipsOwner) },
            onDismiss = { editTip = null },
        ) { vm.setTip(sw.sale, it); editTip = null }
    }
    refunding?.let { sw -> RefundDialog(vm, sw, onDismiss = { refunding = null }) }
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

/** Return some items of a paid receipt: a refund document (vratka) with negative quantities. */
@Composable
private fun RefundDialog(vm: PosViewModel, sw: SaleWithItems, onDismiss: () -> Unit) {
    var left by remember(sw) { mutableStateOf<List<Int>?>(null) }
    LaunchedEffect(sw) { left = vm.refundable(sw) }
    var chosen by remember(sw) { mutableStateOf(mapOf<Int, Int>()) }
    val cart = sw.toCart()
    val amount = sw.items.indices.sumOf { i ->
        val q = chosen[i] ?: 0
        if (q == 0) 0L else cart.items[i].copy(quantity = q).total
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
                        val q = chosen[i] ?: 0
                        val max = l.getOrElse(i) { 0 }
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, fontWeight = FontWeight.Medium)
                                Text(tr("lze vrátit %d z %d", max, item.quantity), style = MaterialTheme.typography.bodySmall)
                            }
                            FilledTonalIconButton(onClick = { chosen = chosen + (i to (q - 1).coerceAtLeast(0)) }, enabled = q > 0, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Rounded.Remove, tr("Méně"))
                            }
                            Text("$q", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(32.dp))
                            FilledTonalIconButton(onClick = { chosen = chosen + (i to (q + 1).coerceAtMost(max)) }, enabled = q < max, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Rounded.Add, tr("Více"))
                            }
                        }
                    }
                }
                Text(tr("Vrátit zákazníkovi: %s", Money.format(amount)), fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            Button(onClick = { vm.refund(sw, chosen.filterValues { it > 0 }); onDismiss() }, enabled = amount > 0 || chosen.values.any { it > 0 }) {
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
private fun eetLabel(records: List<EetRecordEntity>): String {
    val r = records.firstOrNull { !it.storno } ?: return ""
    return when (r.status) {
        EetStatus.CONFIRMED -> " · EET ✓"
        EetStatus.PENDING -> tr(" · EET čeká")
        EetStatus.REJECTED -> tr(" · EET odmítnuto")
        EetStatus.VERIFIED -> tr(" · EET ověřeno")
    }
}
