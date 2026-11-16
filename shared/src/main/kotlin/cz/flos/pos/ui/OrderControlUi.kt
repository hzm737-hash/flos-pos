package cz.flos.pos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import cz.flos.core.OrderChannelStatus
import cz.flos.core.OrderHours
import cz.flos.pos.LocalPlatform
import cz.flos.pos.data.AdvFeature
import cz.flos.pos.data.AppSettings

private val DAY_NAMES = listOf("Pondělí", "Úterý", "Středa", "Čtvrtek", "Pátek", "Sobota", "Neděle")

/** The clock for the status texts, ticking every half minute. */
@Composable
private fun nowMillis(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); now = System.currentTimeMillis() } }
    return now
}

private fun statusText(s: OrderChannelStatus): String = when {
    s.open -> tr("přijímáme")
    s.reason == "paused" -> tr("zastaveno")
    else -> tr("mimo čas objednávek")
}

/**
 * Under the top of every screen while orders come from the tables or the website: the big "stop ringing"
 * button while a new order rings, otherwise whether orders are taken now and the button to stop them.
 */
@Composable
fun OrderControlBar(vm: PosViewModel) {
    val s by vm.settings.collectAsState()
    val ringing by vm.ringing.collectAsState()
    val qrOn = s.on(AdvFeature.QR_ORDERING)
    val webOn = s.on(AdvFeature.WEB_ORDERING)
    var dialog by remember { mutableStateOf(false) }
    if (ringing) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.error).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Nová objednávka!"), color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = { vm.stopRinging() }, colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onError, contentColor = MaterialTheme.colorScheme.error)) { Text(tr("Vypnout zvonění")) }
        }
    }
    if (!s.paired || (!qrOn && !webOn)) return
    val now = nowMillis()
    val c = s.orderControl
    val qr = OrderHours.qrStatus(c, now)
    val web = OrderHours.webStatus(c, now)
    val closed = (qrOn && !qr.open) || (webOn && !web.open)
    val parts = listOfNotNull(
        if (qrOn) tr("ze stolů: %s", statusText(qr)) else null,
        if (webOn) tr("z webu: %s", statusText(web)) else null,
    )
    Row(Modifier.fillMaxWidth()
        .background(if (closed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant)
        .clickable { dialog = true }.padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Objednávky") + " " + parts.joinToString(" · "), style = MaterialTheme.typography.labelLarge,
            color = if (closed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        val paused = (qrOn && c.qrPaused) || (webOn && c.webPaused)
        TextButton(onClick = { dialog = true }) { Text(if (paused) tr("Obnovit") else tr("Zastavit")) }
    }
    if (dialog) OrderControlDialog(vm, s) { dialog = false }
}

/** "Stop orders": from the tables, from the website or both, with the message customers see. */
@Composable
fun OrderControlDialog(vm: PosViewModel, s: AppSettings, onClose: () -> Unit) {
    val qrOn = s.on(AdvFeature.QR_ORDERING)
    val webOn = s.on(AdvFeature.WEB_ORDERING)
    var message by remember { mutableStateOf(s.pauseMessage) }
    val now = nowMillis()
    val c = s.orderControl
    fun save(qrPaused: Boolean = c.qrPaused, webPaused: Boolean = c.webPaused) =
        vm.setOrderControl { it.copy(qrPaused = qrPaused, webPaused = webPaused, pauseMessage = message.trim().take(OrderHours.MAX_MESSAGE)) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("Příjem objednávek")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (qrOn) PauseRow(tr("Objednávky ze stolů (QR kód)"), OrderHours.qrStatus(c, now), !c.qrPaused) { save(qrPaused = !it) }
                if (webOn) PauseRow(tr("Objednávky z webu (rozvoz a odběr)"), OrderHours.webStatus(c, now), !c.webPaused) { save(webPaused = !it) }
                if (qrOn && webOn) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { save(qrPaused = true, webPaused = true) }) { Text(tr("Zastavit vše")) }
                    OutlinedButton(onClick = { save(qrPaused = false, webPaused = false) }) { Text(tr("Obnovit vše")) }
                }
                Text(tr("Zpráva pro zákazníky, když jsou objednávky zastavené"), style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(message, { message = it.take(OrderHours.MAX_MESSAGE) }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                    placeholder = { Text(OrderHours.DEFAULT_PAUSE) })
                if (message.trim() != s.pauseMessage) TextButton(onClick = { save() }) { Text(tr("Uložit zprávu")) }
                Text(tr("Zákazníci uvidí zprávu na webu i v menu u stolu. Stůl, který už má otevřený účet, může po zastavení objednat jen přes obsluhu."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tr("Časy objednávek nastavíte v Pokročilém nastavení u objednávek z QR a z webu."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { if (message.trim() != s.pauseMessage) save(); onClose() }) { Text(tr("Hotovo")) } },
    )
}

@Composable
private fun PauseRow(title: String, status: OrderChannelStatus, taking: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(if (status.open) tr("Teď přijímáme objednávky.") else if (status.reason == "paused") tr("Zastaveno, dokud je znovu nezapnete.")
                else tr("Mimo čas objednávek."), style = MaterialTheme.typography.bodySmall,
                color = if (status.open) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        }
        Switch(checked = taking, onCheckedChange = onChange)
    }
}

/** The ordering hours for the tables ([qr]) or the website, per weekday, in Pokročilé nastavení. */
@Composable
fun OrderHoursEditor(vm: PosViewModel, s: AppSettings, qr: Boolean) {
    val on = if (qr) s.qrHoursOn else s.webHoursOn
    val saved = (if (qr) s.orderControl.qrHours else s.orderControl.webHours)
    var days by remember(saved) { mutableStateOf(saved) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("Čas objednávek"), style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(if (qr) tr("Objednávky ze stolů jen v určený čas") else tr("Objednávky z webu jen v určený čas"))
                Text(tr("Vypnuto: objednat jde kdykoli. Mimo čas server objednávku odmítne a zákazník uvidí, kdy objednat může."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = on, onCheckedChange = { v -> vm.setOrderControl { if (qr) it.copy(qrHoursOn = v) else it.copy(webHoursOn = v) } })
        }
        if (on) {
        DAY_NAMES.forEachIndexed { i, name ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(name), modifier = Modifier.width(90.dp))
                OutlinedTextField(days[i], { v -> days = days.toMutableList().also { it[i] = v.take(60) } }, singleLine = true,
                    modifier = Modifier.weight(1f), placeholder = { Text(tr("zavřeno")) },
                    isError = OrderHours.parseDay(days[i]) == null)
            }
        }
        Text(tr("Pište např. 11:00-20:30, dva časy 11:00-14:00, 17:00-22:00, přes půlnoc 18:00-02:00. Prázdné = ten den objednávky nepřijímáme."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            OutlinedButton(onClick = { days = List(7) { days[0] } }) { Text(tr("Pondělí pro všechny dny")) }
            if (days != saved) Button(onClick = { vm.setOrderControl { if (qr) it.copy(qrHours = days.map(String::trim)) else it.copy(webHours = days.map(String::trim)) } },
                enabled = OrderHours.problem(days) == null) { Text(tr("Uložit časy")) }
        }
        if (qr) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(tr("Stůl s otevřeným účtem může objednávat i po čase"))
                Text(tr("Hosté, kteří ještě sedí a nezaplatili, si mohou něco přidat. Nové stoly už objednat nemohou."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = s.openTablesAfterHours, onCheckedChange = { v -> vm.setOrderControl { it.copy(openTablesAfterHours = v) } })
        }
        }
    }
}

/** The loud ring for new orders, in Pokročilé nastavení (shown with QR and with web orders: the same setting). */
@Composable
fun OrderAlarmSettings(vm: PosViewModel, s: AppSettings) {
    val platform = LocalPlatform.current
    val askNotify = platform.rememberNotificationPermission { }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("Zvonění při nové objednávce"), style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(tr("Hlasité zvonění, dokud ho někdo nevypne"))
                Text(tr("Zvoní, dokud obsluha objednávku nepřijme nebo neodmítne, nebo nestiskne „Vypnout zvonění“. Vypnuto: jedno krátké pípnutí."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = s.orderAlarm, onCheckedChange = { v -> vm.updateSettings { it.copy(orderAlarm = v) }; if (v) askNotify() })
        }
        if (s.orderAlarm) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf("ALARM" to "Budík", "RINGTONE" to "Vyzvánění", "BEEP" to "Pípání").forEach { (k, label) ->
                FilterChip(selected = s.orderAlarmSound == k, onClick = { vm.updateSettings { it.copy(orderAlarmSound = k) } }, label = { Text(tr(label)) })
            }
        }
        Text(tr("Hlasitost zvonění: %d %%", s.orderAlarmVolume))
        var volume by remember(s.orderAlarmVolume) { mutableStateOf(s.orderAlarmVolume.toFloat()) }
        Slider(value = volume, onValueChange = { volume = it }, valueRange = 10f..100f, steps = 8,
            onValueChangeFinished = { vm.updateSettings { it.copy(orderAlarmVolume = volume.toInt().coerceIn(10, 100)) } })
        Text(tr("Hlasitost nastavená tady platí pro zvonění Flos bez ohledu na hlasitost telefonu. Zvoní i v tichém režimu a obvykle i v režimu Nerušit (budíky jsou povolené)."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { vm.testRing() }) { Text(tr("Vyzkoušet zvuk")) }
        }
        if (s.orderAlarm && platform.canWatchInBackground) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Zvonit i se zhasnutou obrazovkou"))
                    Text(tr("Flos běží na pozadí a dál hlídá objednávky (v liště je stále upozornění). Telefon nebo tablet nechte v nabíječce."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = s.orderBackground, onCheckedChange = { v -> vm.updateSettings { it.copy(orderBackground = v) }; if (v) askNotify() })
            }
        }
    }
}
