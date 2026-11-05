package cz.flos.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import cz.flos.core.Money
import cz.flos.core.Waiters
import cz.flos.pos.data.SaleWithItems

/**
 * Waiters on shift: who has which open bills, who is away, and handing bills over when
 * someone leaves.
 */
@Composable
fun WaitersScreen(vm: PosViewModel) {
    val settings by vm.settings.collectAsState()
    val orders by vm.openOrders.collectAsState()
    val names = remember(settings, orders) { vm.waiterNames() }
    val away = Waiters.away(settings.waitersAway)
    var handing by remember { mutableStateOf<String?>(null) }
    var newNames by remember(settings.waiters) { mutableStateOf(settings.waiters) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("Obsluha"), style = MaterialTheme.typography.titleLarge)
        Text(tr("Kdo je na směně a kdo má které otevřené účty. Když číšník odchází, předejte jeho účty jinému."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Field(tr("Další jména obsluhy (oddělte čárkou)"), newNames) { v -> newNames = v }
        if (newNames != settings.waiters) TextButton(onClick = { vm.updateSettings { it.copy(waiters = newNames) } }) { Text(tr("Uložit jména")) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(names, key = { it }) { name ->
                val bills = orders.filter { it.sale.cashier == name }
                val isAway = name in away
                Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Badge, null)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(name + if (name == settings.cashier) " · " + tr("u pokladny") else "", fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (bills.isEmpty()) tr("Žádné otevřené účty")
                                    else tr("%d účtů, %s", bills.size, Money.format(bills.sumOf { it.sale.total })) + " · " +
                                        bills.joinToString(", ") { it.sale.tableName?.let { t -> tr("Stůl %s", t) } ?: "#${it.sale.orderNumber ?: 0}" },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(if (isAway) tr("Mimo směnu") else tr("Na směně"), style = MaterialTheme.typography.labelMedium)
                            Switch(checked = !isAway, onCheckedChange = { vm.setWaiterAway(name, !it) }, modifier = Modifier.padding(start = 8.dp))
                        }
                        if (bills.isNotEmpty()) FilledTonalButton(onClick = { handing = name }) { Text(tr("Předat účty")) }
                    }
                }
            }
        }
    }
    handing?.let { from ->
        val suggested = Waiters.suggest(from, names, away) { n -> orders.count { it.sale.cashier == n } }
        HandOverDialog(tr("Předat všechny účty: %s", from), names.filter { it != from }, away, suggested, onDismiss = { handing = null }) { to ->
            vm.handOverAll(from, to); handing = null
        }
    }
}

/** Picks the waiter who takes over; those on shift come first, the suggested one is marked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HandOverDialog(title: String, names: List<String>, away: Set<String>, suggested: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (names.isEmpty()) Text(tr("Zadejte další jména obsluhy na kartě Obsluha."))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    names.sortedBy { it in away }.forEach { n ->
                        FilterChip(selected = n == suggested, onClick = { onPick(n) },
                            label = { Text(n + if (n in away) " (" + tr("mimo směnu") + ")" else "") })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Zrušit")) } },
    )
}

/** Hand-over of one open bill, from the Orders screen. */
@Composable
internal fun HandOverOne(vm: PosViewModel, sw: SaleWithItems, onDismiss: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val orders by vm.openOrders.collectAsState()
    val names = remember(settings, orders) { vm.waiterNames() }.filter { it != sw.sale.cashier }
    val away = Waiters.away(settings.waitersAway)
    val suggested = Waiters.suggest(sw.sale.cashier, names, away) { n -> orders.count { it.sale.cashier == n } }
    HandOverDialog(tr("Předat účet č. %d", sw.sale.orderNumber ?: 0), names, away, suggested, onDismiss) { to -> vm.handOver(sw, to); onDismiss() }
}
