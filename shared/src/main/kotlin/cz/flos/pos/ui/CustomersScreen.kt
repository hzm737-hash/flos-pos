package cz.flos.pos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.flos.core.Money
import cz.flos.pos.data.CustomerEntity
import cz.flos.pos.data.CustomerTotals
import kotlinx.coroutines.launch

private fun CustomerEntity.matches(q: String): Boolean {
    if (q.isBlank()) return true
    val t = q.trim().lowercase()
    return name.lowercase().contains(t) || phone.filter(Char::isDigit).contains(t.filter(Char::isDigit).ifEmpty { "\u0000" }) ||
        email.lowercase().contains(t) || cardCode?.lowercase() == t
}

/** Regular customers: contacts, personal discount, loyalty points and what they have spent. */
@Composable
fun CustomersScreen(vm: PosViewModel) {
    val customers by vm.customers.collectAsState()
    val settings by vm.settings.collectAsState()
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<CustomerEntity?>(null) }
    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { editing = CustomerEntity(name = "") }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text(tr("Nový zákazník")) })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                label = { Text(tr("Hledat jméno, telefon, e-mail nebo číslo karty")) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (settings.loyaltyCrownsPerPoint > 0) Text(
                tr("Věrnostní body: 1 bod za každých %d Kč. Body se připíšou po zaplacení účtenky s vybraným zákazníkem.", settings.loyaltyCrownsPerPoint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val list = customers.filter { it.matches(query) }
            if (list.isEmpty()) Text(tr("Zatím žádní zákazníci."), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { c ->
                    Card(onClick = { editing = c }, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Person, null)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(c.name, fontWeight = FontWeight.SemiBold)
                                Text(listOf(c.phone, c.email, c.cardCode.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(tr("%d bodů", c.points), fontWeight = FontWeight.Bold)
                                if (c.discountPercent > 0) Text(tr("sleva %d %%", c.discountPercent), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { c ->
        CustomerDialog(vm, c, onDismiss = { editing = null })
    }
}

/** Create or edit a customer; shows their visits and spending. */
@Composable
internal fun CustomerDialog(vm: PosViewModel, initial: CustomerEntity, onDismiss: () -> Unit, selectOnSave: Boolean = false) {
    var c by remember(initial) { mutableStateOf(initial) }
    var totals by remember(initial.id) { mutableStateOf<CustomerTotals?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(initial.id) { if (initial.id != 0L) totals = vm.customerTotals(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) tr("Nový zákazník") else initial.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                totals?.let { t ->
                    Text(tr("Nákupů: %d, utraceno %s", t.count, Money.format(t.total)), style = MaterialTheme.typography.bodyMedium)
                }
                Field(tr("Jméno"), c.name) { v -> c = c.copy(name = v) }
                Field(tr("Telefon"), c.phone, keyboard = KeyboardType.Phone) { v -> c = c.copy(phone = v) }
                Field(tr("E-mail"), c.email, keyboard = KeyboardType.Email) { v -> c = c.copy(email = v.trim()) }
                Field(tr("Číslo věrnostní karty (nepovinné)"), c.cardCode.orEmpty()) { v -> c = c.copy(cardCode = v.trim().ifBlank { null }) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField(tr("Sleva %"), c.discountPercent, 0..100, Modifier.weight(1f)) { v -> c = c.copy(discountPercent = v) }
                    OutlinedTextField(
                        value = c.points.toString(), singleLine = true,
                        onValueChange = { v -> v.filter(Char::isDigit).take(9).toLongOrNull()?.let { c = c.copy(points = it) } ?: run { if (v.isBlank()) c = c.copy(points = 0) } },
                        label = { Text(tr("Body")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Field(tr("Poznámka"), c.note) { v -> c = c.copy(note = v) }
                if (initial.id != 0L) {
                    Text(tr("Poznámky a dokumenty"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    NotesPanel(vm, cz.flos.pos.data.NoteTarget.CUSTOMER, initial.id)
                }
                if (initial.id != 0L) TextButton(onClick = { confirmDelete = true }) {
                    Text(tr("Smazat zákazníka"), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = { vm.saveCustomer(c, select = selectOnSave); onDismiss() }, enabled = c.name.isNotBlank()) { Text(tr("Uložit")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zrušit")) } },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(tr("Smazat zákazníka %s?", initial.name)) },
        text = { Text(tr("Účtenky zůstanou, jen už nebudou přiřazené k zákazníkovi.")) },
        confirmButton = { Button(onClick = { vm.deleteCustomer(initial); confirmDelete = false; onDismiss() }) { Text(tr("Smazat")) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Zrušit")) } },
    )
}

/** Picks the customer for the cart: search, a scanned card code, or a new customer. */
@Composable
internal fun CustomerPickerDialog(vm: PosViewModel, onDismiss: () -> Unit) {
    val customers by vm.customers.collectAsState()
    val current by vm.customer.collectAsState()
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Zákazník")) },
        text = {
            Column(Modifier.widthIn(min = 280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { v ->
                        query = v
                        // A scanner types the card code quickly; pick the customer as soon as it matches exactly.
                        if (v.length >= 4) scope.launch { vm.customerByCard(v)?.let { vm.selectCustomer(it); onDismiss() } }
                    },
                    singleLine = true, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    label = { Text(tr("Jméno, telefon nebo karta")) }, modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(customers.filter { it.matches(query) }.take(50), key = { it.id }) { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.selectCustomer(c); onDismiss() }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, fontWeight = if (c.id == current?.id) FontWeight.Bold else FontWeight.Normal)
                                if (c.phone.isNotBlank()) Text(c.phone, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(tr("%d bodů", c.points), style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { creating = true }) { Text(tr("Nový zákazník")) } },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { vm.selectCustomer(null); onDismiss() }) { Text(tr("Bez zákazníka")) }
                TextButton(onClick = onDismiss) { Text(tr("Zavřít")) }
            }
        },
    )
    if (creating) CustomerDialog(vm, CustomerEntity(name = query.trim()), onDismiss = { creating = false; onDismiss() }, selectOnSave = true)
}
