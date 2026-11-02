package cz.flos.pos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.flos.core.Money
import cz.flos.pos.data.SaleWithItems
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(vm: PosViewModel) {
    val sales by vm.recentSales.collectAsState()
    var open by remember { mutableStateOf<SaleWithItems?>(null) }
    var confirmCancel by remember { mutableStateOf<SaleWithItems?>(null) }
    val fmt = remember { SimpleDateFormat("d.M.yyyy HH:mm", Locale.forLanguageTag("cs-CZ")) }

    if (sales.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Zatím žádné účtenky") }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(sales, key = { it.sale.id }) { sw ->
            val s = sw.sale
            Row(
                Modifier.fillMaxWidth().clickable { open = sw }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.number, fontWeight = FontWeight.Medium,
                        textDecoration = if (s.cancelled) TextDecoration.LineThrough else null)
                    Text(
                        fmt.format(Date(s.createdAt)) + " · " + s.paymentMethod.label + if (s.cancelled) " · STORNO" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (s.cancelled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(Money.format(s.total), fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider()
        }
    }

    open?.let { sw ->
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text("Účtenka ${sw.sale.number}") },
            text = {
                Text(
                    vm.receiptPreview(sw),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                )
            },
            confirmButton = { Button(onClick = { vm.reprint(sw) }) { Text("Tisknout kopii") } },
            dismissButton = {
                Row {
                    if (!sw.sale.cancelled) TextButton(onClick = { confirmCancel = sw; open = null }) { Text("Storno") }
                    TextButton(onClick = { open = null }) { Text("Zavřít") }
                }
            },
        )
    }
    confirmCancel?.let { sw ->
        AlertDialog(
            onDismissRequest = { confirmCancel = null },
            title = { Text("Stornovat účtenku ${sw.sale.number}?") },
            text = { Text("Účtenka zůstane v historii označená jako storno a nezapočítá se do tržby.") },
            confirmButton = { Button(onClick = { vm.cancelSale(sw); confirmCancel = null }) { Text("Stornovat") } },
            dismissButton = { TextButton(onClick = { confirmCancel = null }) { Text("Zpět") } },
        )
    }
}
