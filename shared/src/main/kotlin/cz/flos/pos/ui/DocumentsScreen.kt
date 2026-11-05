package cz.flos.pos.ui

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.flos.core.DocumentKind
import cz.flos.core.Money
import cz.flos.pos.data.DocStatus
import cz.flos.pos.data.DocumentEntity
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

internal val DOC_KINDS = listOf(DocumentKind.QUOTE, DocumentKind.PROFORMA, DocumentKind.SALES_ORDER)

internal fun kindLabel(k: DocumentKind): String = when (k) {
    DocumentKind.QUOTE -> tr("Cenová nabídka")
    DocumentKind.PROFORMA -> tr("Zálohová faktura")
    DocumentKind.SALES_ORDER -> tr("Objednávka zákazníka")
    else -> k.name
}

private fun statusLabel(s: DocStatus): String = when (s) {
    DocStatus.OPEN -> tr("Otevřený")
    DocStatus.DONE -> tr("Prodáno")
    DocStatus.CANCELLED -> tr("Zrušeno")
}

internal val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M.yyyy")

/** "5.11.2026", "5.11." (this year) → the date, or null. */
internal fun parseDay(text: String): LocalDate? {
    val t = text.trim().trimEnd('.')
    val parts = t.split('.').map { it.trim() }
    val d = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
    val y = parts.getOrNull(2)?.toIntOrNull()?.let { if (it < 100) 2000 + it else it } ?: LocalDate.now().year
    return runCatching { LocalDate.of(y, m, d) }.getOrNull()
}

/**
 * Quotations, pro forma invoices and customer orders (cakes, catering). Made from the cart,
 * printed as non-tax documents, and sold later by loading them back into the cart.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DocumentsScreen(vm: PosViewModel, onSell: () -> Unit) {
    val docs by vm.documents.collectAsState()
    val cart by vm.cart.collectAsState()
    var creating by remember { mutableStateOf<DocumentKind?>(null) }
    var filter by remember { mutableStateOf<DocumentKind?>(null) }
    var showClosed by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<DocumentEntity?>(null) }
    val fmt = remember { SimpleDateFormat("d.M. HH:mm", Locale.forLanguageTag("cs-CZ")) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("Nabídky a zakázky"), style = MaterialTheme.typography.titleLarge)
        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (cart.isEmpty()) tr("Přidejte položky na kartě Prodej, pak je zde uložte jako nabídku, zálohovou fakturu nebo objednávku.")
                    else tr("V košíku: %s položek za %s", cz.flos.core.Qty.format(cart.itemCount), Money.format(cart.total)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DOC_KINDS.forEach { k ->
                        FilledTonalButton(onClick = { creating = k }, enabled = !cart.isEmpty()) { Text(kindLabel(k)) }
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text(tr("Vše")) })
            DOC_KINDS.forEach { k -> FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text(kindLabel(k)) }) }
            FilterChip(selected = showClosed, onClick = { showClosed = !showClosed }, label = { Text(tr("I uzavřené")) })
        }
        val list = docs.filter { (filter == null || it.docKind == filter) && (showClosed || it.docStatus == DocStatus.OPEN) }
        if (list.isEmpty()) Text(tr("Žádné doklady."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { d ->
                val overdue = d.docStatus == DocStatus.OPEN && d.due?.isBefore(LocalDate.now()) == true
                Card(onClick = { opened = d }, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${kindLabel(d.docKind)} ${d.number}", fontWeight = FontWeight.SemiBold)
                            Text(
                                listOfNotNull(fmt.format(Date(d.createdAt)), d.customerName.takeIf { it.isNotBlank() },
                                    d.due?.let { tr("do %s", it.format(DAY)) }, statusLabel(d.docStatus)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(Money.format(d.total), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    creating?.let { k ->
        NewDocumentDialog(vm, k, onDismiss = { creating = null }) { note, due, print ->
            vm.saveDocument(k, note, due, print); creating = null
        }
    }
    opened?.let { d ->
        DocumentDialog(vm, d, onDismiss = { opened = null }, onSell = { vm.sellDocument(d); opened = null; onSell() },
            onCopy = { vm.copyDocument(d); opened = null; onSell() })
    }
}

@Composable
private fun NewDocumentDialog(vm: PosViewModel, kind: DocumentKind, onDismiss: () -> Unit, onSave: (String, LocalDate?, Boolean) -> Unit) {
    val settings by vm.settings.collectAsState()
    val customer by vm.customer.collectAsState()
    val default = when (kind) {
        DocumentKind.QUOTE -> LocalDate.now().plusDays(settings.quoteValidDays.toLong())
        DocumentKind.PROFORMA -> LocalDate.now().plusDays(7)
        else -> LocalDate.now().plusDays(1)
    }
    var dayText by remember { mutableStateOf(default.format(DAY)) }
    var note by remember { mutableStateOf("") }
    var print by remember { mutableStateOf(true) }
    val day = parseDay(dayText)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(kindLabel(kind)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Není daňový doklad a nic se neodesílá do EET. Účtenka se vystaví, až zákazník zaplatí."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(customer?.let { tr("Zákazník: %s", it.name) } ?: tr("Zákazníka vyberete na kartě Prodej (nepovinné)."),
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = dayText, onValueChange = { dayText = it }, singleLine = true, isError = dayText.isNotBlank() && day == null,
                    label = { Text(when (kind) { DocumentKind.QUOTE -> tr("Platí do"); DocumentKind.PROFORMA -> tr("Splatnost"); else -> tr("Termín (den vyzvednutí)") }) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (kind == DocumentKind.PROFORMA && settings.iban == null) Text(tr("Zadejte číslo účtu v Nastavení, aby se na doklad vytiskla QR Platba."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text(tr("Poznámka (adresa, kontakt, podrobnosti)")) },
                    minLines = 2, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = print, onCheckedChange = { print = it })
                    Text(tr("Vytisknout"))
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(note, day, print) }) { Text(tr("Uložit")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zrušit")) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocumentDialog(vm: PosViewModel, d: DocumentEntity, onDismiss: () -> Unit, onSell: () -> Unit, onCopy: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var contact by remember(d) { mutableStateOf<cz.flos.pos.data.CustomerEntity?>(null) }
    androidx.compose.runtime.LaunchedEffect(d.customerId) { contact = vm.customerContact(d.customerId) }
    val preview = remember(d) { vm.documentPreview(d) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${kindLabel(d.docKind)} ${d.number}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(statusLabel(d.docStatus), fontWeight = FontWeight.SemiBold)
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                    Text(preview, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(12.dp).horizontalScroll(rememberScrollState()))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { vm.printDocument(d) }) { Icon(Icons.Rounded.Print, null); Text(tr(" Tisk")) }
                    TextButton(onClick = onCopy) { Text(tr("Kopírovat do košíku")) }
                    TextButton(onClick = { sending = true }) { Text(tr("Poslat zákazníkovi")) }
                    if (d.docStatus == DocStatus.OPEN) TextButton(onClick = { vm.setDocumentStatus(d, DocStatus.CANCELLED); onDismiss() }) { Text(tr("Zrušit doklad")) }
                    else TextButton(onClick = { vm.setDocumentStatus(d, DocStatus.OPEN); onDismiss() }) { Text(tr("Znovu otevřít")) }
                    TextButton(onClick = { confirmDelete = true }) { Text(tr("Smazat"), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            if (d.docStatus == DocStatus.OPEN) Button(onClick = onSell) { Icon(Icons.Rounded.ShoppingCart, null); Text(tr(" Prodat")) }
            else TextButton(onClick = onDismiss) { Text(tr("Zavřít")) }
        },
        dismissButton = { if (d.docStatus == DocStatus.OPEN) TextButton(onClick = onDismiss) { Text(tr("Zavřít")) } },
    )
    if (sending) SendDialog(vm, tr("Poslat %s", d.number), contact?.phone.orEmpty(), contact?.email.orEmpty(), vm.documentMessage(d),
        onDismiss = { sending = false }) { set ->
            if (d.docKind == DocumentKind.SALES_ORDER) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = false, onClick = { set(vm.documentMessage(d)) }, label = { Text(tr("Potvrzení objednávky")) })
                FilterChip(selected = false, onClick = { set(vm.documentMessage(d, ready = true)) }, label = { Text(tr("Připraveno k vyzvednutí")) })
            }
        }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(tr("Smazat doklad %s?", d.number)) },
        confirmButton = { Button(onClick = { vm.deleteDocument(d); confirmDelete = false; onDismiss() }) { Text(tr("Smazat")) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Zpět")) } },
    )
}
