package cz.flos.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.horizontalScroll
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

/**
 * The first order from the website that waits for the staff: delivery or pick-up, the customer, what
 * they want at this till's prices, and the time it will be ready. The customer's page shows the answer.
 */
@Composable
fun WebOrderDialog(vm: PosViewModel) {
    val inbox by vm.webInbox.collectAsState()
    val w = inbox.firstOrNull() ?: return
    val o = w.order
    val (cart, unmatched) = remember(w.id) { vm.webOrderCart(w) }
    val delivery = o.type == cz.flos.core.WebOrders.DELIVERY
    // The restaurant sets the time; a time the customer asked for is offered first and chosen by default.
    val now = remember(w.id) { java.time.LocalTime.now().withSecond(0).withNano(0) }
    val wish = remember(w.id) { cz.flos.core.WebOrders.wishMinutes(o.time, now.hour * 60 + now.minute) }
    val choices = remember(w.id) { (listOfNotNull(wish) + cz.flos.core.WebOrders.READY_MINUTES).distinct() }
    var minutes by remember(w.id) { mutableStateOf(wish ?: if (delivery) 45 else 20) }
    val canWhatsApp = cz.flos.core.Notify.phoneDigits(o.customer?.phone.orEmpty()) != null
    val whatsapp by vm.webWhatsApp.collectAsState()
    fun clock(m: Int) = now.plusMinutes(m.toLong()).format(java.time.format.DateTimeFormatter.ofPattern("H:mm"))
    AlertDialog(
        onDismissRequest = {},
        title = { Text(tr("Objednávka z webu: %s", if (delivery) tr("Rozvoz") else tr("Osobní odběr"))) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                o.customer?.let { c ->
                    Text(c.name + ", " + c.phone, style = MaterialTheme.typography.titleSmall)
                    if (delivery) Text(c.street + (if (c.bell.isNotBlank()) " (" + c.bell + ")" else ""))
                }
                Text(tr("Zákazník chce: %s", o.time.ifEmpty { tr("co nejdříve") }) + " · " + tr("Platba: %s", if (o.payment == "card") tr("kartou") else tr("hotově")))
                if (o.note.isNotBlank()) Text(tr("Poznámka: %s", o.note))
                cart.items.forEach { Text("${Qty.format(it.quantity)}× ${it.name}  ${Money.format(it.total)}") }
                Text(tr("Celkem: %s", Money.format(cart.total)), style = MaterialTheme.typography.titleMedium)
                if (o.total > 0 && o.total * 100 != cart.total) Text(tr("Zákazník na webu viděl %s Kč.", o.total.toString()), color = MaterialTheme.colorScheme.error)
                if (unmatched.isNotEmpty()) Text(tr("Bez produktu v pokladně (cena z webu): %s", unmatched.joinToString(", ")), color = MaterialTheme.colorScheme.error)
                Text(tr("Hotovo v"), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    choices.forEach { m ->
                        val label = if (m == wish) tr("%s (přání zákazníka)", clock(m)) else tr("%d min · %s", m, clock(m))
                        FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(label) })
                    }
                }
                if (wish != null && minutes != wish) Text(tr("Zákazník uvidí nový čas %s místo %s.", clock(minutes), o.time), color = MaterialTheme.colorScheme.error)
                if (canWhatsApp) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = whatsapp, onCheckedChange = { vm.webWhatsApp.value = it })
                    Text(tr("Poslat zákazníkovi potvrzení přes WhatsApp"))
                }
                if (inbox.size > 1) Text(tr("Čeká dalších objednávek: %d", inbox.size - 1), style = MaterialTheme.typography.labelMedium)
            }
        },
        confirmButton = { Button(onClick = { run {
            // Minutes counted again from the moment of the tap, so the chosen clock time stays exact.
            val secs = java.time.Duration.between(java.time.LocalTime.now(), now.plusMinutes(minutes.toLong())).seconds
            vm.acceptWebOrder(w, ((secs + 59) / 60).toInt().coerceIn(1, 240), canWhatsApp && whatsapp)
        } }) { Text(tr("Přijmout")) } },
        dismissButton = { TextButton(onClick = { vm.rejectWebOrder(w) }) { Text(tr("Odmítnout")) } },
    )
}

/** Settings of web ordering in Pokročilé nastavení: what it needs and the menu to publish. */
@Composable
fun WebOrderingSettings(vm: PosViewModel, s: AppSettings) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!s.paired) Text(tr("Pokladna není spárovaná s back office (Nastavení → Back office). Bez toho objednávky nepřijdou."),
            color = MaterialTheme.colorScheme.error)
        Text(tr("Po zapnutí nebo vypnutí zveřejněte menu znovu; web přijímá objednávky jen při zapnutí. Adresu webu musí správce back office povolit (FLOS_WEB_ORIGINS)."),
            style = MaterialTheme.typography.bodySmall)
        Text(tr("Pizzy z webu se párují podle čísla v menu (kód produktu), jinak podle názvu."), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { vm.publishMenu() }, enabled = s.paired) { Text(tr("Zveřejnit menu znovu")) }
        if (s.menuUrl.isNotBlank()) {
            Text(tr("Adresa menu (pro nastavení webu):"), style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(s.menuUrl, style = MaterialTheme.typography.bodySmall) }
        }
    }
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
