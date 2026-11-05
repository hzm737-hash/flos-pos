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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.EventSeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.flos.pos.data.BookingStatus
import cz.flos.pos.data.ReservationEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

private fun statusLabel(s: BookingStatus): String = when (s) {
    BookingStatus.BOOKED -> tr("Rezervováno")
    BookingStatus.SEATED -> tr("Usazeni")
    BookingStatus.NO_SHOW -> tr("Nepřišli")
    BookingStatus.CANCELLED -> tr("Zrušeno")
}

private fun dayTitle(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> tr("Dnes")
        today.plusDays(1) -> tr("Zítra")
        today.minusDays(1) -> tr("Včera")
        else -> d.format(DAY)
    }
}

/** Table reservations by day: who comes, when, how many, which table. "Usadit" opens the table's bill. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReservationsScreen(vm: PosViewModel, onSeat: () -> Unit) {
    val all by vm.reservations.collectAsState()
    var editing by remember { mutableStateOf<ReservationEntity?>(null) }
    var showDone by remember { mutableStateOf(false) }
    val now = LocalDateTime.now()
    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val at = now.withMinute(0).withSecond(0).withNano(0).plusHours(1)
                    editing = ReservationEntity(name = "", at = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                },
                icon = { Icon(Icons.Rounded.Add, null) }, text = { Text(tr("Nová rezervace")) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Rezervace"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                FilterChip(selected = showDone, onClick = { showDone = !showDone }, label = { Text(tr("I vyřízené")) })
            }
            val list = all.filter { showDone || it.bookingStatus == BookingStatus.BOOKED }
            if (list.isEmpty()) Text(tr("Žádné rezervace."), color = MaterialTheme.colorScheme.onSurfaceVariant)
            val byDay = list.groupBy { it.start.toLocalDate() }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                byDay.forEach { (day, rs) ->
                    item(key = "d$day") {
                        Text(dayTitle(day) + " · " + tr("%d hostů", rs.filter { it.bookingStatus == BookingStatus.BOOKED }.sumOf { it.guests }),
                            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                    }
                    items(rs, key = { it.id }) { r ->
                        val late = r.bookingStatus == BookingStatus.BOOKED && r.start.plusMinutes(15).isBefore(now)
                        Card(onClick = { editing = r }, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = if (r.bookingStatus == BookingStatus.BOOKED) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(r.start.format(TIME), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("  " + r.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Text(tr("%d os.", r.guests))
                                }
                                Text(
                                    listOfNotNull(r.tableName?.let { tr("Stůl %s", it) }, r.phone.takeIf { it.isNotBlank() }, r.note.takeIf { it.isNotBlank() },
                                        statusLabel(r.bookingStatus), if (late) tr("zpoždění") else null).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (r.bookingStatus == BookingStatus.BOOKED) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(onClick = { if (vm.seatReservation(r)) onSeat() }) { Icon(Icons.Rounded.EventSeat, null); Text(tr(" Usadit")) }
                                    TextButton(onClick = { vm.setReservationStatus(r, BookingStatus.NO_SHOW) }) { Text(tr("Nepřišli")) }
                                    TextButton(onClick = { vm.setReservationStatus(r, BookingStatus.CANCELLED) }) { Text(tr("Zrušit")) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { r -> ReservationDialog(vm, r, onDismiss = { editing = null }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReservationDialog(vm: PosViewModel, initial: ReservationEntity, onDismiss: () -> Unit) {
    val settings by vm.settings.collectAsState()
    var r by remember(initial) { mutableStateOf(initial) }
    var dayText by remember(initial) { mutableStateOf(initial.start.toLocalDate().format(DAY)) }
    var timeText by remember(initial) { mutableStateOf(initial.start.format(TIME)) }
    var confirmDelete by remember { mutableStateOf(false) }
    val day = parseDay(dayText)
    val time = runCatching { LocalTime.parse(timeText.trim().replace('.', ':').let { if (it.length == 4 && it[1] == ':') "0$it" else it }) }.getOrNull()
    val at = if (day != null && time != null) day.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() else null
    val draft = at?.let { r.copy(at = it) }
    val clashes = draft?.let { vm.reservationClashes(it) }.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) tr("Nová rezervace") else initial.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(tr("Jméno"), r.name) { v -> r = r.copy(name = v) }
                Field(tr("Telefon"), r.phone, keyboard = KeyboardType.Phone) { v -> r = r.copy(phone = v) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = dayText, onValueChange = { dayText = it }, singleLine = true, isError = day == null,
                        label = { Text(tr("Den")) }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = timeText, onValueChange = { timeText = it }, singleLine = true, isError = time == null,
                        label = { Text(tr("Čas")) }, modifier = Modifier.weight(1f))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0L to tr("Dnes"), 1L to tr("Zítra"), 2L to tr("Pozítří")).forEach { (plus, label) ->
                        FilterChip(selected = day == LocalDate.now().plusDays(plus), onClick = { dayText = LocalDate.now().plusDays(plus).format(DAY) }, label = { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(tr("Hostů"), r.guests, 1..500, Modifier.weight(1f)) { v -> r = r.copy(guests = v) }
                    NumberField(tr("Délka (min)"), r.minutes, 15..1440, Modifier.weight(1f)) { v -> r = r.copy(minutes = v) }
                }
                if (settings.tableList.isNotEmpty()) {
                    Text(tr("Stůl"), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = r.tableName == null, onClick = { r = r.copy(tableName = null) }, label = { Text(tr("Bez stolu")) })
                        settings.tableList.forEach { t ->
                            FilterChip(selected = r.tableName == t, onClick = { r = r.copy(tableName = t) }, label = { Text(t) })
                        }
                    }
                }
                if (clashes.isNotEmpty()) Text(
                    tr("Stůl je v tu dobu už rezervovaný: %s", clashes.joinToString(", ") { "${it.start.format(TIME)} ${it.name}" }),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                )
                Field(tr("Poznámka (oslava, alergie, dětská židlička)"), r.note) { v -> r = r.copy(note = v) }
                if (initial.id != 0L) {
                    if (r.bookingStatus != BookingStatus.BOOKED) TextButton(onClick = { r = r.copy(status = BookingStatus.BOOKED.name) }) { Text(tr("Vrátit mezi rezervované")) }
                    TextButton(onClick = { confirmDelete = true }) { Text(tr("Smazat rezervaci"), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            Button(onClick = { draft?.let { vm.saveReservation(it) }; onDismiss() }, enabled = r.name.isNotBlank() && draft != null) { Text(tr("Uložit")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zrušit")) } },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(tr("Smazat rezervaci %s?", initial.name)) },
        confirmButton = { Button(onClick = { vm.deleteReservation(initial); confirmDelete = false; onDismiss() }) { Text(tr("Smazat")) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Zpět")) } },
    )
}
