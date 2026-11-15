package cz.flos.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.DeliveryDining
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.flos.core.Money
import cz.flos.pos.data.SaleWithItems
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Orders that are not paid yet (deliveries, takeaway, tables). Each can be paid by any method,
 * changed in the cart, printed again or cancelled. Only a paid order becomes a receipt and goes to EET.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OrdersScreen(vm: PosViewModel, onEdit: () -> Unit) {
    val orders by vm.openOrders.collectAsState()
    var paying by remember { mutableStateOf<SaleWithItems?>(null) }
    var cancelling by remember { mutableStateOf<SaleWithItems?>(null) }
    var handing by remember { mutableStateOf<SaleWithItems?>(null) }
    val fmt = remember { SimpleDateFormat("d.M. HH:mm", Locale.forLanguageTag("cs-CZ")) }

    if (orders.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.DeliveryDining, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                Text(tr("Žádné nezaplacené objednávky"), style = MaterialTheme.typography.titleMedium)
                Text(
                    tr("Na prodeji zvolte „Odložit“: objednávka se uloží bez platby, např. pro rozvoz."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Nezaplacené objednávky"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(Money.format(orders.sumOf { it.sale.total }), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(300.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(orders, key = { it.sale.id }) { sw ->
                    val s = sw.sale
                    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tr("Objednávka č. %d", s.orderNumber ?: 0), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text(fmt.format(Date(s.openedAt ?: s.createdAt)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (s.note.isNotBlank()) Text(s.note, maxLines = 6, overflow = TextOverflow.Ellipsis)
                            Text(
                                sw.items.joinToString(", ") { "${cz.flos.core.Qty.format(it.quantity)}× ${it.name}" },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3, overflow = TextOverflow.Ellipsis,
                            )
                            Text(Money.format(s.total), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.size(4.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { paying = sw }, shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = FlosMint, contentColor = Color.White),
                                ) { Text(tr("Zaplaceno")) }
                                TextButton(onClick = { vm.editOrder(sw); onEdit() }) { Icon(Icons.Rounded.Edit, null); Text(tr(" Upravit")) }
                                TextButton(onClick = { vm.reprintOrder(sw) }) { Icon(Icons.Rounded.Print, null); Text(tr(" Tisk")) }
                                TextButton(onClick = { cancelling = sw }) { Icon(Icons.Rounded.Block, null); Text(tr(" Zrušit")) }
                                TextButton(onClick = { handing = sw }) { Text(tr("Předat") + if (s.cashier.isNotBlank()) " (${s.cashier})" else "") }
                            }
                        }
                    }
                }
            }
        }
    }

    paying?.let { sw ->
        PaymentDialog(vm, sw.sale.total, title = tr("Objednávka č. %d", sw.sale.orderNumber ?: 0), onDismiss = { paying = null }) { method, given ->
            vm.payOrder(sw, method, given); paying = null
        }
    }
    handing?.let { sw -> HandOverOne(vm, sw, onDismiss = { handing = null }) }
    cancelling?.let { sw ->
        var reason by remember(sw) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { cancelling = null },
            title = { Text(tr("Zrušit objednávku č. %d?", sw.sale.orderNumber ?: 0)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Objednávka nebyla zaplacena, proto se nic neodesílá do EET. Zůstane v historii jako zrušená."))
                    OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text(tr("Důvod (nezaplaceno, vráceno, …)")) }, singleLine = true)
                }
            },
            confirmButton = {
                val needReason = vm.settings.collectAsState().value.on(cz.flos.pos.data.AdvFeature.STORNO_REASON)
                Button(onClick = { vm.cancelOrder(sw, reason); cancelling = null }, enabled = !needReason || reason.isNotBlank()) { Text(tr("Zrušit objednávku")) }
            },
            dismissButton = { TextButton(onClick = { cancelling = null }) { Text(tr("Zpět")) } },
        )
    }
}
