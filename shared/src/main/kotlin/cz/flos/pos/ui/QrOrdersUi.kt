package cz.flos.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import cz.flos.pos.LocalPlatform
import cz.flos.pos.data.AppSettings
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.flos.core.Money
import cz.flos.core.Qty

/** The first order from a table that waits for the staff: what the guests want, accept or decline. */
@Composable
fun TableOrderDialog(vm: PosViewModel) {
    val inbox by vm.qrInbox.collectAsState()
    val o = inbox.firstOrNull() ?: return
    val (cart, missing) = vm.tableOrderCart(o, vm.openOrders.value.firstOrNull { it.sale.tableName == o.table }?.sale?.serviceType)
    AlertDialog(
        onDismissRequest = {},
        title = { Text(tr("Nová objednávka ze stolu %s", o.table)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                cart.items.forEach { Text("${Qty.format(it.quantity)}× ${it.name}  ${Money.format(it.total)}") }
                Text(tr("Celkem: %s", Money.format(cart.total)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.heightIn(min = 32.dp))
                if (o.note.isNotBlank()) Text(tr("Poznámka: %s", o.note))
                if (missing.isNotEmpty()) Text(tr("Nelze přidat (není v nabídce): %s", missing.joinToString(", ")), color = MaterialTheme.colorScheme.error)
                if (inbox.size > 1) Text(tr("Čeká dalších objednávek: %d", inbox.size - 1), style = MaterialTheme.typography.labelMedium)
            }
        },
        confirmButton = { TextButton(onClick = { vm.acceptTableOrder(o) }, enabled = cart.items.isNotEmpty()) { Text(tr("Přijmout")) } },
        dismissButton = { TextButton(onClick = { vm.rejectTableOrder(o) }) { Text(tr("Odmítnout")) } },
    )
}

/** Settings of QR ordering in Pokročilé nastavení: approval, ticket, the tables' QR codes and the link for the web. */
@Composable
fun QrOrderingSettings(vm: PosViewModel, s: AppSettings) {
    val links by vm.tableLinks.collectAsState()
    val platform = LocalPlatform.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!s.paired) Text(tr("Pokladna není spárovaná s back office (Nastavení → Back office). Bez toho objednávky nepřijdou."),
            color = MaterialTheme.colorScheme.error)
        Text(tr("Po zapnutí nebo změně produktů zveřejněte menu znovu, aby hosté viděli tlačítko „+“ u jídel."),
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { vm.publishMenu() }, enabled = s.paired) { Text(tr("Zveřejnit menu znovu")) }
        SwitchRow(tr("Objednávku nejdřív potvrdí obsluha"), tr("Vypnuto: objednávka jde rovnou na účet stolu."), s.qrOrderApprove) { v ->
            vm.updateSettings { it.copy(qrOrderApprove = v) }
        }
        SwitchRow(tr("Tisknout objednávku pro kuchyň"), tr("Po přijetí se vytiskne lístek s novými položkami."), s.qrOrderPrint) { v ->
            vm.updateSettings { it.copy(qrOrderPrint = v) }
        }
        if (s.menuUrl.isNotBlank()) {
            Text(tr("Odkaz na menu pro váš web (bez objednávání):"), style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(s.menuUrl, style = MaterialTheme.typography.bodySmall) }
        }
        Text(tr("QR kódy stolů"), style = MaterialTheme.typography.labelLarge)
        Text(tr("Každý stůl má vlastní QR kód s podpisem; objednat jde jen přes kód na stole. Stoly se berou z Nastavení."),
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.loadTableLinks() }, enabled = s.paired) { Text(tr("Načíst QR kódy stolů")) }
            Button(onClick = { vm.printTableQrs() }, enabled = links.isNotEmpty()) { Text(tr("Tisknout")) }
        }
        var shown by remember { mutableStateOf<String?>(null) }
        links.forEach { l ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(tr("Stůl %s", l.table), modifier = Modifier.weight(1f))
                TextButton(onClick = { shown = if (shown == l.table) null else l.table }) { Text(tr(if (shown == l.table) "Skrýt" else "Ukázat QR")) }
                TextButton(onClick = { platform.openUrl(l.path) }) { Text(tr("Otevřít")) }
            }
            if (shown == l.table) QrText(l.path, 200.dp)
        }
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
