package cz.flos.pos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import cz.flos.core.BulkEdit
import cz.flos.core.ImportPlan
import cz.flos.core.Money
import cz.flos.core.PriceRounding
import cz.flos.core.VatRate
import cz.flos.pos.LocalPlatform
import cz.flos.pos.data.NoteEntity
import cz.flos.pos.data.NoteTarget
import cz.flos.pos.data.ProductEntity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

private const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/** Excel import/export, bulk changes, price labels, and notes with documents. */
@Composable
fun ProductToolsScreen(vm: PosViewModel) {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf(tr("Excel"), tr("Hromadné úpravy"), tr("Štítky"), tr("Poznámky a dokumenty"))
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> ExcelTab(vm)
            1 -> BulkTab(vm)
            2 -> LabelsTab(vm)
            else -> Column(Modifier.fillMaxSize().padding(16.dp)) { NotesPanel(vm, NoteTarget.GENERAL, null, Modifier.fillMaxSize()) }
        }
    }
}

@Composable
private fun ExcelTab(vm: PosViewModel) {
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<Pair<ImportPlan<Long>, List<String>>?>(null) }
    val saveXlsx = platform.rememberFileSaver(XLSX) { ok -> vm.notify(if (ok) tr("Soubor uložen") else tr("Soubor nebyl uložen")) }
    val saveCsv = platform.rememberFileSaver("text/csv") { ok -> vm.notify(if (ok) tr("Soubor uložen") else tr("Soubor nebyl uložen")) }
    val pick = platform.rememberFilePicker { bytes -> scope.launch { preview = vm.previewImport(bytes) } }
    val day = LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_DATE)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr("Ceník v Excelu"), style = MaterialTheme.typography.titleLarge)
        Text(tr("Stáhněte produkty do Excelu, upravte ceny nebo přidejte nové řádky a nahrajte soubor zpět. Sloupce: Název, Cena, DPH %, Kategorie, EAN, Aktivní, Varianty, Ceníky. Povinné jsou jen Název a Cena."),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { saveXlsx("flos-produkty-$day.xlsx", vm.exportProducts(xlsx = true)) } }) { Icon(Icons.Rounded.Download, null); Text(tr(" Stáhnout .xlsx")) }
            FilledTonalButton(onClick = { scope.launch { saveCsv("flos-produkty-$day.csv", vm.exportProducts(xlsx = false)) } }) { Text(tr("CSV")) }
        }
        HorizontalDivider()
        Text(tr("Produkty se spárují podle EAN, jinak podle názvu. Nové se přidají, u existujících se změní cena a údaje. Nic se nemaže."),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = pick) { Icon(Icons.Rounded.Upload, null); Text(tr(" Nahrát soubor (.xlsx nebo .csv)")) }
    }
    preview?.let { (plan, errors) ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(tr("Import produktů")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("Nové: %d · změněné: %d · beze změny: %d", plan.added.size, plan.changed.size, plan.unchanged), fontWeight = FontWeight.SemiBold)
                    plan.added.take(8).forEach { Text("+ ${it.name}  ${Money.format(it.price)}", style = MaterialTheme.typography.bodySmall) }
                    plan.changed.take(8).forEach { Text("~ ${it.second.name}  ${Money.format(it.second.price)}", style = MaterialTheme.typography.bodySmall) }
                    if (plan.added.size + plan.changed.size > 16) Text("…")
                    if (errors.isNotEmpty()) {
                        Text(tr("Přeskočené řádky:"), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                        errors.take(10).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { vm.applyImport(plan); preview = null }, enabled = plan.added.isNotEmpty() || plan.changed.isNotEmpty()) { Text(tr("Importovat")) }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text(tr("Zrušit")) } },
        )
    }
}

/** Products filtered by text and category, with checkboxes; shared by bulk edit and labels. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProductChooser(vm: PosViewModel, all: List<ProductEntity>, selected: Set<Long>, onSelected: (Set<Long>) -> Unit, modifier: Modifier) {
    val cats by vm.categories.collectAsState()
    var q by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf<Long?>(null) }
    val shown = all.filter { (cat == null || it.categoryId == cat) && (q.isBlank() || cz.flos.core.Pricing.key(it.name).contains(cz.flos.core.Pricing.key(q)) || it.barcode == q.trim()) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true, leadingIcon = { Icon(Icons.Rounded.Search, null) },
            label = { Text(tr("Hledat produkt")) }, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = cat == null, onClick = { cat = null }, label = { Text(tr("Vše")) })
            cats.forEach { c -> FilterChip(selected = cat == c.id, onClick = { cat = c.id }, label = { Text(c.name) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Vybráno: %d", selected.size), modifier = Modifier.weight(1f))
            TextButton(onClick = { onSelected(selected + shown.map { it.id }) }) { Text(tr("Vybrat zobrazené")) }
            TextButton(onClick = { onSelected(emptySet()) }) { Text(tr("Nic")) }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth().clickable { onSelected(if (p.id in selected) selected - p.id else selected + p.id) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = p.id in selected, onCheckedChange = { onSelected(if (it) selected + p.id else selected - p.id) })
                    Text(p.name + if (!p.active) " (" + tr("skryto") + ")" else "", Modifier.weight(1f))
                    Text("${p.vatPercent} %  ", style = MaterialTheme.typography.bodySmall)
                    Text(Money.format(p.price), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BulkTab(vm: PosViewModel) {
    val products by vm.products.collectAsState()
    val cats by vm.categories.collectAsState()
    var all by remember { mutableStateOf<List<ProductEntity>>(emptyList()) }
    LaunchedEffect(products) { all = vm.allProducts().sortedBy { it.name.lowercase() } }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var percent by remember { mutableStateOf("10") }
    var rounding by remember { mutableStateOf(PriceRounding.CROWN) }
    var confirm by remember { mutableStateOf<Pair<String, (ProductEntity) -> ProductEntity>?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = percent, onValueChange = { percent = it.filter { c -> c.isDigit() || c in "-+,." }.take(6) }, singleLine = true,
                        label = { Text(tr("Změna ceny %")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    val pct = percent.replace(',', '.').toDoubleOrNull()
                    Button(enabled = selected.isNotEmpty() && pct != null, onClick = {
                        val p = pct ?: 0.0
                        confirm = tr("Cena %s %%", (if (p > 0) "+" else "") + percent) to { it: ProductEntity -> it.copy(price = BulkEdit.adjust(it.price, p, rounding)) }
                    }) { Text(tr("Upravit ceny")) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(PriceRounding.NONE to tr("Nezaokrouhlovat"), PriceRounding.CROWN to tr("Na celé Kč"), PriceRounding.NINETY to tr("Na ,90")).forEach { (r, l) ->
                        FilterChip(selected = rounding == r, onClick = { rounding = r }, label = { Text(l) })
                    }
                }
                Text(tr("Přesunout do kategorie / změnit DPH / skrýt:"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    cats.forEach { c ->
                        FilterChip(selected = false, enabled = selected.isNotEmpty(), onClick = { confirm = tr("Kategorie %s", c.name) to { it: ProductEntity -> it.copy(categoryId = c.id) } }, label = { Text(c.name) })
                    }
                    VatRate.entries.forEach { v ->
                        FilterChip(selected = false, enabled = selected.isNotEmpty(), onClick = { confirm = tr("DPH %d %%", v.percent) to { it: ProductEntity -> it.copy(vatPercent = v.percent) } }, label = { Text("DPH ${v.percent} %") })
                    }
                    FilterChip(selected = false, enabled = selected.isNotEmpty(), onClick = { confirm = tr("Skrýt") to { it: ProductEntity -> it.copy(active = false) } }, label = { Text(tr("Skrýt z prodeje")) })
                    FilterChip(selected = false, enabled = selected.isNotEmpty(), onClick = { confirm = tr("Zobrazit") to { it: ProductEntity -> it.copy(active = true) } }, label = { Text(tr("Vrátit do prodeje")) })
                }
            }
        }
        ProductChooser(vm, all, selected, { selected = it }, Modifier.weight(1f))
    }
    confirm?.let { (label, change) ->
        val sample = all.filter { it.id in selected }.take(5)
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(tr("%s u %d produktů?", label, selected.size)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    sample.forEach { p ->
                        val after = change(p)
                        Text(if (after.price != p.price) "${p.name}: ${Money.format(p.price)} → ${Money.format(after.price)}" else p.name, style = MaterialTheme.typography.bodySmall)
                    }
                    if (selected.size > sample.size) Text("…")
                }
            },
            confirmButton = { Button(onClick = { vm.bulkEdit(selected, label, change); confirm = null }) { Text(tr("Provést")) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("Zrušit")) } },
        )
    }
}

@Composable
private fun LabelsTab(vm: PosViewModel) {
    val products by vm.products.collectAsState()
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var copies by remember { mutableStateOf(1) }
    var unitNote by remember { mutableStateOf("") }
    var withShop by remember { mutableStateOf(true) }
    val all = products.sortedBy { it.name.lowercase() }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Cenovky se tisknou na tiskárnu účtenek: název, cena a čárový kód (EAN nebo vlastní kód produktu)."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberField(tr("Kusů od každého"), copies, 1..100, Modifier.weight(1f)) { copies = it }
            OutlinedTextField(value = unitNote, onValueChange = { unitNote = it }, singleLine = true, label = { Text(tr("Text pod cenou (např. cena za 1 kg)")) }, modifier = Modifier.weight(2f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = withShop, onCheckedChange = { withShop = it })
            Text(tr("Název provozovny nahoře"), Modifier.weight(1f))
            Button(enabled = selected.isNotEmpty(), onClick = { vm.printLabels(all.filter { it.id in selected }, copies, unitNote, withShop) }) {
                Icon(Icons.Rounded.Print, null); Text(tr(" Tisknout"))
            }
        }
        ProductChooser(vm, all, selected, { selected = it }, Modifier.weight(1f))
    }
}

/** Notes with attached documents for a customer, a product or the whole business. */
@Composable
internal fun NotesPanel(vm: PosViewModel, target: NoteTarget, id: Long?, modifier: Modifier = Modifier) {
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val notes by remember(target, id) { vm.notes(target, id) }.collectAsState(emptyList())
    var text by remember { mutableStateOf("") }
    var file by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var naming by remember { mutableStateOf<ByteArray?>(null) }
    var deleting by remember { mutableStateOf<NoteEntity?>(null) }
    val pick = platform.rememberFilePicker { bytes -> naming = bytes }
    val save = platform.rememberFileSaver("application/octet-stream") { ok -> vm.notify(if (ok) tr("Soubor uložen") else tr("Soubor nebyl uložen")) }
    val fmt = remember { SimpleDateFormat("d.M.yyyy HH:mm", Locale.forLanguageTag("cs-CZ")) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(tr("Nová poznámka")) }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = pick) { Icon(Icons.Rounded.AttachFile, null); Text(file?.first ?: tr("Přiložit dokument")) }
            Spacer1()
            Button(enabled = text.isNotBlank() || file != null, onClick = { vm.addNote(target, id, text, file); text = ""; file = null }) { Text(tr("Přidat")) }
        }
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (notes.isEmpty()) Text(tr("Zatím žádné poznámky."), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            notes.forEach { n ->
                Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(listOf(fmt.format(Date(n.createdAt)), n.author).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (n.text.isNotBlank()) Text(n.text)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            n.fileTitle?.let { t ->
                                TextButton(onClick = { scope.launch { vm.attachment(n)?.let { save(t, it) } ?: vm.notify(tr("Soubor chybí")) } }) {
                                    Icon(Icons.Rounded.AttachFile, null); Text(t)
                                }
                            }
                            Spacer1()
                            TextButton(onClick = { deleting = n }) { Text(tr("Smazat"), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
    naming?.let { bytes ->
        var name by remember(bytes) { mutableStateOf(tr("Dokument") + guessExtension(bytes)) }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text(tr("Název dokumentu")) },
            text = { Field(tr("Název souboru"), name) { name = it } },
            confirmButton = { Button(onClick = { file = name.trim().ifBlank { "Dokument" } to bytes; naming = null }) { Text(tr("Přiložit")) } },
            dismissButton = { TextButton(onClick = { naming = null }) { Text(tr("Zrušit")) } },
        )
    }
    deleting?.let { n ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("Smazat poznámku?")) },
            confirmButton = { Button(onClick = { vm.deleteNote(n); deleting = null }) { Text(tr("Smazat")) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Zpět")) } },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Spacer1() = androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))

/** ".pdf", ".jpg", ".png" from the first bytes of a file. */
private fun guessExtension(b: ByteArray): String = when {
    b.size > 4 && b[0] == '%'.code.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'D'.code.toByte() -> ".pdf"
    b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> ".jpg"
    b.size > 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> ".png"
    b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() -> ".docx"
    else -> ""
}
