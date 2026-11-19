package cz.flos.pos.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import cz.flos.core.WebReservations
import cz.flos.pos.data.AppSettings

/**
 * The first table reservation from the website that waits for the staff: who, when, how many. The staff
 * confirm it (optionally with a table, warned about other bookings of that table) or decline it with a reason.
 * Waits while an order from a table or the website is open, so two dialogs never stack.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WebReservationDialog(vm: PosViewModel) {
    val inbox by vm.webResInbox.collectAsState()
    val orders by vm.webInbox.collectAsState()
    val tableOrders by vm.qrInbox.collectAsState()
    val w = inbox.firstOrNull() ?: return
    if (orders.isNotEmpty() || tableOrders.isNotEmpty()) return
    val r = w.reservation
    val s by vm.settings.collectAsState()
    var table by remember(w.id) { mutableStateOf("") }
    var reply by remember(w.id) { mutableStateOf("") }
    var rejecting by remember(w.id) { mutableStateOf(false) }
    val whatsapp by vm.webWhatsApp.collectAsState()
    val canWhatsApp = cz.flos.core.Notify.phoneDigits(r.phone) != null
    val clashes = if (table.isBlank()) emptyList() else vm.reservationClashes(vm.webReservationEntity(w, table))
    if (rejecting) {
        WebReservationRejectDialog(w, canWhatsApp, whatsapp, onWhatsApp = { vm.webWhatsApp.value = it }, onBack = { rejecting = false }) { reason, wa ->
            vm.rejectWebReservation(w, reason, wa)
        }
        return
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(tr("Rezervace z webu")) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SilenceButton(vm)
                Text(WebReservations.whenText(r, false) + " · " + tr("%d os.", r.guests), style = MaterialTheme.typography.titleLarge)
                Text(r.name + ", " + r.phone, style = MaterialTheme.typography.titleSmall)
                if (r.email.isNotBlank()) Text(r.email)
                if (r.note.isNotBlank()) Text(tr("Poznámka: %s", r.note))
                if (r.lang == "en") Text(tr("Zákazník psal anglicky."), style = MaterialTheme.typography.bodySmall)
                Text(tr("Stůl (nepovinné)"), style = MaterialTheme.typography.labelLarge)
                if (s.tableList.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(selected = table.isBlank(), onClick = { table = "" }, label = { Text(tr("Bez stolu")) })
                    s.tableList.forEach { t -> FilterChip(selected = table == t, onClick = { table = t }, label = { Text(t) }) }
                } else OutlinedTextField(table, { table = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(tr("Stůl")) })
                if (clashes.isNotEmpty()) Text(tr("Pozor, stůl %s je v tu dobu rezervovaný: %s", table,
                    clashes.joinToString(", ") { it.name + " " + it.start.toLocalTime().toString() }), color = MaterialTheme.colorScheme.error)
                OutlinedTextField(reply, { reply = it.take(cz.flos.core.WebOrders.MAX_REPLY) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("Zpráva pro zákazníka (nepovinné)")) }, maxLines = 3)
                if (canWhatsApp) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = whatsapp, onCheckedChange = { vm.webWhatsApp.value = it })
                    Text(tr("Poslat zákazníkovi potvrzení přes WhatsApp"))
                }
                if (inbox.size > 1) Text(tr("Čeká dalších rezervací: %d", inbox.size - 1), style = MaterialTheme.typography.labelMedium)
            }
        },
        confirmButton = { Button(onClick = { vm.acceptWebReservation(w, table, reply, canWhatsApp && whatsapp) }) { Text(tr("Potvrdit")) } },
        dismissButton = { TextButton(onClick = { rejecting = true }) { Text(tr("Odmítnout")) } },
    )
}

/** Declining a web reservation: the reason the customer sees on the website (and on WhatsApp when ticked). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WebReservationRejectDialog(
    w: cz.flos.core.WebReservationInbox, canWhatsApp: Boolean, whatsapp: Boolean, onWhatsApp: (Boolean) -> Unit,
    onBack: () -> Unit, onReject: (String, Boolean) -> Unit,
) {
    val en = w.reservation.lang == "en"
    var reason by remember(w.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(tr("Odmítnout rezervaci R%d", w.id)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Důvod pro zákazníka (uvidí ho na webu):"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WebReservations.REJECT_REASONS.forEach { (cs, english) ->
                        val text = if (en) english else cs
                        FilterChip(selected = reason == text, onClick = { reason = text }, label = { Text(text) })
                    }
                }
                if (en) Text(tr("Zákazník psal anglicky, nabídnuté důvody jsou anglicky."), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(reason, { reason = it.take(cz.flos.core.WebOrders.MAX_REPLY) }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                    label = { Text(tr("Důvod odmítnutí")) })
                if (canWhatsApp) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = whatsapp, onCheckedChange = onWhatsApp)
                    Text(tr("Poslat zákazníkovi zprávu i přes WhatsApp"))
                }
            }
        },
        confirmButton = { Button(onClick = { onReject(reason, canWhatsApp && whatsapp) }) { Text(tr("Odmítnout rezervaci")) } },
        dismissButton = { TextButton(onClick = onBack) { Text(tr("Zpět")) } },
    )
}

private val WEEKDAYS = listOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne")

/** Settings of web reservations in Pokročilé nastavení: the rules the website's form follows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WebReservationSettings(vm: PosViewModel, s: AppSettings) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!s.paired) Text(tr("Pokladna není spárovaná s back office (Nastavení → Back office). Bez toho rezervace nepřijdou."),
            color = MaterialTheme.colorScheme.error)
        Text(tr("Po zapnutí nebo změně pravidel zveřejněte menu znovu; web přijímá rezervace jen při zapnutí. Adresu webu musí správce back office povolit (FLOS_WEB_ORIGINS)."),
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { vm.publishMenu() }, enabled = s.paired) { Text(tr("Zveřejnit menu znovu")) }
        if (s.menuUrl.isNotBlank()) {
            Text(tr("Adresa menu (pro nastavení webu):"), style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(s.menuUrl, style = MaterialTheme.typography.bodySmall) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(tr("Nejvíc osob"), s.webResMaxGuests, 1..WebReservations.GUESTS_LIMIT, Modifier.weight(1f)) { v -> vm.updateSettings { it.copy(webResMaxGuests = v) } }
            NumberField(tr("Dní dopředu"), s.webResDaysAhead, 0..WebReservations.DAYS_LIMIT, Modifier.weight(1f)) { v -> vm.updateSettings { it.copy(webResDaysAhead = v) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeField(tr("Od"), s.webResFrom, Modifier.weight(1f)) { v -> vm.updateSettings { it.copy(webResFrom = v) } }
            TimeField(tr("Do (poslední čas)"), s.webResTo, Modifier.weight(1f)) { v -> vm.updateSettings { it.copy(webResTo = v) } }
        }
        NumberField(tr("Nejméně minut předem"), s.webResNotice, 0..7 * 24 * 60, Modifier.fillMaxWidth()) { v -> vm.updateSettings { it.copy(webResNotice = v) } }
        Text(tr("Zavřeno (tyto dny web rezervaci nenabídne)"), style = MaterialTheme.typography.labelLarge)
        val closed = vm.reservationRules(s).closedDays
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WEEKDAYS.forEachIndexed { i, name ->
                val day = i + 1
                FilterChip(selected = day in closed, onClick = {
                    val next = if (day in closed) closed - day else closed + day
                    vm.updateSettings { it.copy(webResClosedDays = next.sorted().joinToString(",")) }
                }, label = { Text(tr(name)) })
            }
        }
        Text(tr("Potvrzená rezervace se uloží do Rezervací se značkou WEB R a číslem."), style = MaterialTheme.typography.bodySmall)
        OrderAlarmSettings(vm, s)
    }
}

/** "HH:mm" field: saves only a valid time (also accepts 1930 or 9:30). */
@Composable
private fun TimeField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    val parsed = cz.flos.core.WebOrders.parseClock(text)
    OutlinedTextField(text, { t ->
        text = t.take(5)
        cz.flos.core.WebOrders.parseClock(text)?.let { onChange(it.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))) }
    }, label = { Text(label) }, singleLine = true, isError = parsed == null, modifier = modifier,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
}
