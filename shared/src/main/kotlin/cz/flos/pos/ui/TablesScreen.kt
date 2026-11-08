package cz.flos.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cz.flos.core.Money
import java.time.Duration

/**
 * Table map: every table from Settings as a tile. A free table starts a new order for it; a
 * table with an open bill shows its total and how long it has been open, and opens the bill.
 */
@Composable
fun TablesScreen(vm: PosViewModel, onOpen: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val orders by vm.openOrders.collectAsState()
    val tables = settings.tableList
    val byTable = orders.filter { it.sale.tableName != null }.groupBy { it.sale.tableName!! }
    val reservations by vm.reservations.collectAsState()
    val bookings = reservations.map { it.booking() }
    val booked = reservations.associateBy { it.id }

    if (tables.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(tr("Zadejte stoly v Nastavení → Stoly, např. „1, 2, 3, Terasa 1“."), textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val now = System.currentTimeMillis()
    // Tables by waiter (Pokročilé nastavení): who has which table, and "only mine" for the waiter at the till.
    val byWaiter = if (settings.on(cz.flos.pos.data.AdvFeature.WAITER_TABLES)) settings.waiterTableMap else emptyMap()
    val waiterOf = byWaiter.flatMap { (w, ts) -> ts.map { it to w } }.toMap()
    var onlyMine by rememberSaveable { mutableStateOf(false) }
    val plan = settings.on(cz.flos.pos.data.AdvFeature.TABLE_PLAN)
    val mine = byWaiter[settings.cashier].orEmpty()
    val shown = if (onlyMine && mine.isNotEmpty()) tables.filter { it in mine } else tables
    Column(Modifier.fillMaxSize()) {
    if (byWaiter.isNotEmpty()) Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !onlyMine, onClick = { onlyMine = false }, label = { Text(tr("Všechny stoly")) })
        FilterChip(selected = onlyMine, onClick = { onlyMine = true }, enabled = mine.isNotEmpty(),
            label = { Text(if (mine.isEmpty()) tr("%s nemá stoly", settings.cashier.ifBlank { "?" }) else tr("Moje stoly (%s)", settings.cashier)) })
    }
    if (plan) TablePlan(vm, shown, byTable, onOpen) { name, busy -> TileContent(name, busy, byTable[name].orEmpty(), waiterOf[name], now, bookings, booked, compact = true) }
    else LazyVerticalGrid(
        columns = GridCells.Adaptive(130.dp),
        contentPadding = PaddingValues(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(shown, key = { it }) { name ->
            val bills = byTable[name].orEmpty()
            val busy = bills.isNotEmpty()
            Card(
                onClick = {
                    if (busy) vm.editOrder(bills.first()) else vm.startTable(name)
                    onOpen()
                },
                shape = RoundedCornerShape(24.dp),
                colors = tileColors(busy),
                modifier = Modifier.height(110.dp),
            ) { TileContent(name, busy, bills, waiterOf[name], now, bookings, booked) }
        }
    }
    }
}

@Composable
private fun tileColors(busy: Boolean) = CardDefaults.cardColors(
    containerColor = if (busy) FlosCoral else MaterialTheme.colorScheme.surfaceVariant,
    contentColor = if (busy) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun TileContent(
    name: String, busy: Boolean, bills: List<cz.flos.pos.data.SaleWithItems>, waiter: String?, now: Long,
    bookings: List<cz.flos.core.Booking>, booked: Map<Long, cz.flos.pos.data.ReservationEntity>,
    compact: Boolean = false,
) {
    Column(Modifier.fillMaxSize().padding(if (compact) 8.dp else 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(tr("Stůl %s", name), style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, maxLines = 1)
            waiter?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
        }
        if (busy) {
            val minutes = Duration.ofMillis(now - (bills.minOf { it.sale.openedAt ?: it.sale.createdAt })).toMinutes()
            Column {
                Text(Money.format(bills.sumOf { it.sale.total }), fontWeight = FontWeight.Bold, maxLines = 1)
                Text(tr("otevřeno %d min", minutes) + (if (bills.size > 1) " · " + tr("%d účty", bills.size) else "") +
                    (if (bills.all { it.sale.kitchenReadyAt != null }) " · " + tr("kuchyně hotovo") else ""),
                    style = MaterialTheme.typography.labelSmall, maxLines = if (compact) 1 else 2)
            }
        } else {
            val next = cz.flos.core.Bookings.upcoming(name, bookings, java.time.LocalDateTime.now())?.let { booked[it.id] }
            if (next != null) Text(tr("Rezervace %s", next.start.format(java.time.format.DateTimeFormatter.ofPattern("H:mm"))) + " · " + next.name,
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = FlosViolet, maxLines = 2)
            else Text(tr("Volný"), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Table plan (Pokročilé nastavení → Plán stolů): tables where they stand in the room. In "Upravit
 * rozmístění" they are dragged to their place; otherwise a tap opens the table as on the grid and
 * holding a table with a bill moves the bill to another table.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun TablePlan(
    vm: PosViewModel, tables: List<String>, byTable: Map<String, List<cz.flos.pos.data.SaleWithItems>>, onOpen: () -> Unit,
    content: @Composable (String, Boolean) -> Unit,
) {
    val settings by vm.settings.collectAsState()
    val saved = settings.tablePositions
    var editing by rememberSaveable { mutableStateOf(false) }
    var moving by remember { mutableStateOf<String?>(null) }
    // Positions being dragged right now; saved when the finger is lifted.
    val dragged = remember { mutableStateMapOf<String, Pair<Float, Float>>() }
    val tileW = 112.dp
    val tileH = 92.dp

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = editing, onClick = { editing = !editing },
                leadingIcon = { Icon(if (editing) Icons.Rounded.Check else Icons.Rounded.OpenWith, null) },
                label = { Text(if (editing) tr("Konec úprav") else tr("Upravit rozmístění")) })
            if (editing) TextButton(onClick = { dragged.clear(); vm.resetTablePlan() }) { Text(tr("Seřadit do řad")) }
            Text(if (editing) tr("Stoly přetáhněte na jejich místo.") else tr("Podržte stůl s účtem pro přesun na jiný stůl."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 14.dp).padding(bottom = 14.dp)) {
            val density = LocalDensity.current
            val freeW = with(density) { (maxWidth - tileW).coerceAtLeast(1.dp).toPx() }
            val freeH = with(density) { (maxHeight - tileH).coerceAtLeast(1.dp).toPx() }
            // Tables without a saved place go in rows, like on the grid.
            val perRow = ((maxWidth + 12.dp) / (tileW + 12.dp)).toInt().coerceAtLeast(1)
            val rowsAvailable = ((maxHeight + 12.dp) / (tileH + 12.dp)).toInt().coerceAtLeast(1)
            // Places come from the full list, so showing only one waiter's tables does not move them.
            val all = settings.tableList
            val rowsNeeded = (all.size + perRow - 1) / perRow
            fun defaultPlace(i: Int): Pair<Float, Float> {
                val col = i % perRow
                val row = i / perRow
                val x = with(density) { (col * (tileW + 12.dp)).toPx() } / freeW
                // More rows than fit on the screen: spread them over the height (they overlap a little)
                // instead of stacking tables on top of each other.
                val y = if (rowsNeeded > rowsAvailable) row.toFloat() / (rowsNeeded - 1).coerceAtLeast(1)
                    else with(density) { (row * (tileH + 12.dp)).toPx() } / freeH
                return x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)
            }
            tables.forEach { name ->
                val i = all.indexOf(name).coerceAtLeast(0)
                val (fx, fy) = dragged[name] ?: saved[name] ?: defaultPlace(i)
                val bills = byTable[name].orEmpty()
                val busy = bills.isNotEmpty()
                val tap = Modifier.combinedClickable(
                    onClick = {
                        if (busy) vm.editOrder(bills.first()) else vm.startTable(name)
                        onOpen()
                    },
                    onLongClick = { if (busy) moving = name },
                )
                val drag = Modifier.pointerInput(name, freeW, freeH) {
                    detectDragGestures(
                        onDragEnd = { dragged[name]?.let { (x, y) -> vm.placeTable(name, x, y) } },
                        onDragCancel = { dragged.remove(name) },
                    ) { change, amount ->
                        change.consume()
                        val (cx, cy) = dragged[name] ?: saved[name] ?: defaultPlace(i)
                        dragged[name] = (cx + amount.x / freeW).coerceIn(0f, 1f) to (cy + amount.y / freeH).coerceIn(0f, 1f)
                    }
                }
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = tileColors(busy),
                    border = if (editing) BorderStroke(2.dp, FlosViolet) else null,
                    modifier = Modifier
                        .offset { IntOffset((fx * freeW).roundToInt(), (fy * freeH).roundToInt()) }
                        .size(tileW, tileH)
                        .then(if (editing) drag else tap),
                ) { content(name, busy) }
            }
        }
    }

    moving?.let { from ->
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text(tr("Přesunout účet ze stolu %s", from)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Vyberte stůl. Má-li už účet, zůstanou tam oba účty zvlášť."), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        settings.tableList.filter { it != from }.forEach { to ->
                            val taken = byTable[to].orEmpty().isNotEmpty()
                            FilterChip(selected = false, onClick = { vm.moveTable(from, to); moving = null },
                                label = { Text(if (taken) tr("Stůl %s", to) + " · " + tr("obsazený") else tr("Stůl %s", to)) })
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moving = null }) { Text(tr("Zrušit")) } },
        )
    }
}
