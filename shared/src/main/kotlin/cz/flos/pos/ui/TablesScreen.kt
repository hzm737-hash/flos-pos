package cz.flos.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    LazyVerticalGrid(
        columns = GridCells.Adaptive(130.dp),
        contentPadding = PaddingValues(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(tables, key = { it }) { name ->
            val bills = byTable[name].orEmpty()
            val busy = bills.isNotEmpty()
            val color = if (busy) FlosCoral else MaterialTheme.colorScheme.surfaceVariant
            val content = if (busy) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            Card(
                onClick = {
                    if (busy) vm.editOrder(bills.first()) else vm.startTable(name)
                    onOpen()
                },
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = color, contentColor = content),
                modifier = Modifier.height(110.dp),
            ) {
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(tr("Stůl %s", name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (busy) {
                        val minutes = Duration.ofMillis(now - (bills.minOf { it.sale.openedAt ?: it.sale.createdAt })).toMinutes()
                        Column {
                            Text(Money.format(bills.sumOf { it.sale.total }), fontWeight = FontWeight.Bold)
                            Text(tr("otevřeno %d min", minutes) + (if (bills.size > 1) " · " + tr("%d účty", bills.size) else "") +
                                (if (bills.all { it.sale.kitchenReadyAt != null }) " · " + tr("kuchyně hotovo") else ""),
                                style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        val next = cz.flos.core.Bookings.upcoming(name, bookings, java.time.LocalDateTime.now())?.let { booked[it.id] }
                        if (next != null) Text(tr("Rezervace %s", next.start.format(java.time.format.DateTimeFormatter.ofPattern("H:mm"))) + " · " + next.name,
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = FlosViolet, maxLines = 2)
                        else Text(tr("Volný"), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
