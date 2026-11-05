package cz.flos.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Kitchen screen: every open order (tables, delivery, takeaway) as a ticket with its items and how long it has
 * been waiting. The cook taps Hotovo when it is ready; the ticket goes grey and the waiter sees it.
 * Meant for a second tablet in the kitchen or the till itself.
 */
@Composable
fun KitchenScreen(vm: PosViewModel) {
    val orders by vm.openOrders.collectAsState()
    var showReady by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

    val waiting = orders.filter { it.sale.kitchenReadyAt == null }.sortedBy { it.sale.openedAt ?: it.sale.createdAt }
    val ready = orders.filter { it.sale.kitchenReadyAt != null }.sortedByDescending { it.sale.kitchenReadyAt }
    val shown = if (showReady) waiting + ready else waiting

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Čeká: %d", waiting.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            FilterChip(selected = showReady, onClick = { showReady = !showReady }, label = { Text(tr("Ukázat hotové (%d)", ready.size)) })
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(tr("Žádné objednávky pro kuchyň. Objednávka se tu objeví, když ji obsluha uloží ke stolu nebo odloží."),
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(220.dp),
            contentPadding = PaddingValues(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalItemSpacing = 12.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(shown, key = { it.sale.id }) { sw ->
                val done = sw.sale.kitchenReadyAt != null
                val minutes = ((now - (sw.sale.openedAt ?: sw.sale.createdAt)) / 60_000).coerceAtLeast(0)
                val late = !done && minutes >= 20
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            done -> MaterialTheme.colorScheme.surfaceVariant
                            late -> MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.secondaryContainer
                        },
                    ),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                sw.sale.tableName?.let { tr("Stůl %s", it) } ?: tr("Objednávka č. %d", sw.sale.orderNumber ?: 0),
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                            )
                            Text(tr("%d min", minutes), fontWeight = FontWeight.Bold)
                        }
                        if (sw.sale.note.isNotBlank()) Text(sw.sale.note, style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        sw.items.forEach { item ->
                            Row {
                                Text("${cz.flos.core.Qty.format(item.quantity)}×", fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                                Text(item.name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        if (done) OutlinedButton(onClick = { vm.kitchenReady(sw, false) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Vrátit do přípravy")) }
                        else Button(onClick = { vm.kitchenReady(sw, true) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Hotovo")) }
                    }
                }
            }
        }
    }
}
