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
import androidx.compose.material3.OutlinedTextField
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
                SilenceButton(vm)
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
    val atTable = o.type == cz.flos.core.WebOrders.TABLE
    // The restaurant sets the time; a time the customer asked for is offered first and chosen by default.
    val now = remember(w.id) { java.time.LocalTime.now().withSecond(0).withNano(0) }
    val wish = remember(w.id) { cz.flos.core.WebOrders.wishMinutes(o.time, now.hour * 60 + now.minute) }
    val choices = remember(w.id) { (listOfNotNull(wish) + cz.flos.core.WebOrders.READY_MINUTES).distinct() }
    // A pre-order for a later day: confirmed for the day and time the customer chose.
    val preDay = remember(w.id) { cz.flos.core.WebOrders.day(o)?.takeIf { cz.flos.core.WebOrders.isPreorder(o) } }
    val preAt = remember(w.id) {
        preDay?.let { d -> runCatching { d.atTime(java.time.LocalTime.parse(o.time)).atZone(cz.flos.core.OrderHours.ZONE).toInstant().toEpochMilli() }.getOrNull() }
    }
    var minutes by remember(w.id) { mutableStateOf(wish ?: if (delivery) 45 else 20) }
    val canWhatsApp = cz.flos.core.Notify.phoneDigits(o.customer?.phone.orEmpty()) != null
    val whatsapp by vm.webWhatsApp.collectAsState()
    val settingsNow by vm.settings.collectAsState()
    fun clock(m: Int) = now.plusMinutes(m.toLong()).format(java.time.format.DateTimeFormatter.ofPattern("H:mm"))
    // Another time typed by the staff ("Jiný čas"), on the order's day; and an optional message for the customer.
    var custom by remember(w.id) { mutableStateOf(false) }
    var customText by remember(w.id) { mutableStateOf("") }
    var reply by remember(w.id) { mutableStateOf("") }
    val orderDay = preDay ?: java.time.LocalDate.now(cz.flos.core.OrderHours.ZONE)
    val customAt = if (custom) cz.flos.core.WebOrders.readyAtFor(customText, orderDay) else null
    // Declining: a second step where the staff writes the reason the customer sees.
    var rejecting by remember(w.id) { mutableStateOf(false) }
    if (rejecting) {
        WebOrderRejectDialog(w, canWhatsApp, whatsapp, onWhatsApp = { vm.webWhatsApp.value = it }, onBack = { rejecting = false }) { reason, wa ->
            vm.rejectWebOrder(w, reason, wa)
        }
        return
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (atTable) tr("Nová objednávka ze stolu %s", o.table) + " (web)" else tr("Objednávka z webu: %s", if (delivery) tr("Rozvoz") else tr("Osobní odběr"))) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SilenceButton(vm)
                o.customer?.let { c ->
                    Text(c.name + ", " + c.phone, style = MaterialTheme.typography.titleSmall)
                    if (delivery) Text(c.street + (if (c.bell.isNotBlank()) " (" + c.bell + ")" else ""))
                }
                if (preDay != null) Text(tr("PŘEDOBJEDNÁVKA na %s v %s", cz.flos.core.WebOrders.dayText(preDay), o.time),
                    style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                if (!atTable) Text((if (preDay == null) tr("Zákazník chce: %s", o.time.ifEmpty { tr("co nejdříve") }) + " · " else "") +
                    tr("Platba: %s", tr(cz.flos.core.WebOrders.paymentText(o.payment))))
                if (o.note.isNotBlank()) Text(tr("Poznámka: %s", o.note))
                cart.items.forEach { Text("${Qty.format(it.quantity)}× ${it.name}  ${Money.format(it.total)}") }
                Text(tr("Celkem: %s", Money.format(cart.total)), style = MaterialTheme.typography.titleMedium)
                if (o.total > 0 && o.total * 100 != cart.total) Text(tr("Zákazník na webu viděl %s Kč.", o.total.toString()), color = MaterialTheme.colorScheme.error)
                if (unmatched.isNotEmpty()) Text(tr("Bez produktu v pokladně (cena z webu): %s", unmatched.joinToString(", ")), color = MaterialTheme.colorScheme.error)
                if (preAt == null) Text(tr("Hotovo v"), style = MaterialTheme.typography.labelLarge)
                if (preAt == null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    choices.forEach { m ->
                        val label = if (m == wish) tr("%s (přání zákazníka)", clock(m)) else tr("%d min · %s", m, clock(m))
                        FilterChip(selected = !custom && minutes == m, onClick = { minutes = m; custom = false }, label = { Text(label) })
                    }
                    if (!atTable) FilterChip(selected = custom, onClick = { custom = true }, label = { Text(tr("Jiný čas")) })
                }
                // A pre-order is confirmed for the customer's time, or for another time on that day.
                if (preAt != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !custom, onClick = { custom = false }, label = { Text(tr("%s (přání zákazníka)", o.time)) })
                    FilterChip(selected = custom, onClick = { custom = true }, label = { Text(tr("Jiný čas")) })
                }
                if (custom) {
                    OutlinedTextField(customText, { customText = it.take(5) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(tr("Čas, kdy bude hotovo (např. 19:30 nebo 1930)")) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        isError = customText.isNotBlank() && customAt == null)
                    if (customText.isNotBlank() && customAt == null) Text(tr("Zadejte čas, který ještě nebyl, např. 19:30."), color = MaterialTheme.colorScheme.error)
                    val t = cz.flos.core.WebOrders.parseClock(customText)
                    if (customAt != null && t != null) Text(
                        if (o.time.isNotBlank()) tr("Zákazník uvidí nový čas %s místo %s.", t.format(java.time.format.DateTimeFormatter.ofPattern("H:mm")), o.time)
                        else tr("Zákazník uvidí čas %s.", t.format(java.time.format.DateTimeFormatter.ofPattern("H:mm"))),
                        color = MaterialTheme.colorScheme.error)
                }
                if (preAt == null && !custom && wish != null && minutes != wish) Text(tr("Zákazník uvidí nový čas %s místo %s.", clock(minutes), o.time), color = MaterialTheme.colorScheme.error)
                if (!atTable) OutlinedTextField(reply, { reply = it.take(cz.flos.core.WebOrders.MAX_REPLY) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("Zpráva pro zákazníka (nepovinné)")) }, maxLines = 3,
                    placeholder = { Text(tr("Např. Rozvoz bude o 15 minut později, děkujeme za pochopení.")) })
                if (!atTable) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = settingsNow.webOrderPrint, onCheckedChange = { v -> vm.updateSettings { it.copy(webOrderPrint = v) } })
                    Text(tr("Vytisknout objednávku hned"))
                }
                if (canWhatsApp) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = whatsapp, onCheckedChange = { vm.webWhatsApp.value = it })
                    Text(tr("Poslat zákazníkovi potvrzení přes WhatsApp"))
                }
                if (inbox.size > 1) Text(tr("Čeká dalších objednávek: %d", inbox.size - 1), style = MaterialTheme.typography.labelMedium)
            }
        },
        confirmButton = { Button(enabled = !custom || customAt != null, onClick = { run {
            if (custom) {
                // The typed time, checked again at the moment of the tap.
                val at = cz.flos.core.WebOrders.readyAtFor(customText, orderDay) ?: return@run
                vm.acceptWebOrder(w, 0, canWhatsApp && whatsapp, at, reply); return@run
            }
            if (preAt != null) { vm.acceptWebOrder(w, 0, canWhatsApp && whatsapp, preAt, reply); return@run }
            // Minutes counted again from the moment of the tap, so the chosen clock time stays exact.
            val secs = java.time.Duration.between(java.time.LocalTime.now(), now.plusMinutes(minutes.toLong())).seconds
            vm.acceptWebOrder(w, ((secs + 59) / 60).toInt().coerceIn(1, 240), canWhatsApp && whatsapp, reply = reply)
        } }) {
            val t = cz.flos.core.WebOrders.parseClock(customText)?.format(java.time.format.DateTimeFormatter.ofPattern("H:mm"))
            Text(when {
                custom && customAt != null && preDay != null -> tr("Přijmout na %s %s", cz.flos.core.WebOrders.dayText(preDay), t ?: "")
                custom && customAt != null -> tr("Přijmout na %s", t ?: "")
                preDay != null && !custom -> tr("Přijmout na %s %s", cz.flos.core.WebOrders.dayText(preDay), o.time)
                else -> tr("Přijmout")
            })
        } },
        dismissButton = { TextButton(onClick = { rejecting = true }) { Text(tr("Odmítnout")) } },
    )
}

/**
 * Declining a web order: the reason the customer sees on their order page (and on WhatsApp when ticked).
 * Ready-made reasons fill the text in the customer's language; the staff can change it or write their own.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WebOrderRejectDialog(
    w: cz.flos.core.WebOrderInbox, canWhatsApp: Boolean, whatsapp: Boolean, onWhatsApp: (Boolean) -> Unit,
    onBack: () -> Unit, onReject: (String, Boolean) -> Unit,
) {
    val en = w.order.lang == "en"
    var reason by remember(w.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(tr("Odmítnout objednávku WEB %d", w.id)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Důvod pro zákazníka (uvidí ho na webu u objednávky):"), style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    cz.flos.core.WebOrders.REJECT_REASONS.forEach { (cs, english) ->
                        val text = if (en) english else cs
                        FilterChip(selected = reason == text, onClick = { reason = text }, label = { Text(text) })
                    }
                }
                if (en) Text(tr("Zákazník objednával v angličtině, nabídnuté důvody jsou anglicky."), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(reason, { reason = it.take(cz.flos.core.WebOrders.MAX_REPLY) }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                    label = { Text(tr("Důvod odmítnutí")) })
                if (canWhatsApp) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = whatsapp, onCheckedChange = onWhatsApp)
                    Text(tr("Poslat zákazníkovi zprávu i přes WhatsApp"))
                }
            }
        },
        confirmButton = { Button(onClick = { onReject(reason, canWhatsApp && whatsapp) }) { Text(tr("Odmítnout objednávku")) } },
        dismissButton = { TextButton(onClick = onBack) { Text(tr("Zpět")) } },
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
        SwitchRow(tr("Vytisknout objednávku hned po přijetí"), tr("Vypnuto: objednávku vytisknete, kdy chcete, v Objednávky → Tisk."), s.webOrderPrint) { v ->
            vm.updateSettings { it.copy(webOrderPrint = v) }
        }
        SwitchRow(tr("QR platba na objednávce"), tr("Na lístku rozvozu a osobního odběru se vytiskne QR kód s částkou (VS = číslo WEB). Potřebuje číslo účtu v nastavení."), s.webOrderQr) { v ->
            vm.updateSettings { it.copy(webOrderQr = v) }
        }
        OrderHoursEditor(vm, s, qr = false)
        OrderAlarmSettings(vm, s)
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
        OrderHoursEditor(vm, s, qr = true)
        OrderAlarmSettings(vm, s)
        if (s.menuUrl.isNotBlank()) {
            Text(tr("Odkaz na menu pro váš web (bez objednávání):"), style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(s.menuUrl, style = MaterialTheme.typography.bodySmall) }
        }
        Text(tr("Menu na vašem webu pro stoly (nepovinné)"), style = MaterialTheme.typography.labelLarge)
        var site by remember(s.tableMenuUrl) { mutableStateOf(s.tableMenuUrl) }
        OutlinedTextField(site, { site = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://pizzafresca.cz/menu") },
            label = { Text(tr("Adresa stránky s menu")) })
        if (site != s.tableMenuUrl) TextButton(onClick = { vm.updateSettings { it.copy(tableMenuUrl = site) } }) { Text(tr("Uložit")) }
        Text(tr("Vyplněno: QR kódy stolů otevřou menu na vašem webu (stůl a klíč se přidají k adrese) a web pošle objednávku do pokladny. Prázdné: menu Flos jako dosud. Po změně QR kódy vytiskněte znovu."),
            style = MaterialTheme.typography.bodySmall)
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
                TextButton(onClick = { platform.openUrl(vm.tableQrLink(l)) }) { Text(tr("Otevřít")) }
            }
            if (shown == l.table) QrText(vm.tableQrLink(l), 200.dp)
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

/** While the new order rings: stops the ring and leaves the order waiting. */
@Composable
private fun SilenceButton(vm: PosViewModel) {
    val ringing by vm.ringing.collectAsState()
    if (ringing) OutlinedButton(onClick = { vm.stopRinging() }) { Text(tr("Vypnout zvonění")) }
}
