package cz.flos.pos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.flos.core.AddonGroup
import cz.flos.core.Addons
import cz.flos.core.LineNote
import cz.flos.core.Money
import cz.flos.core.OptionChoice
import cz.flos.core.OptionGroup
import cz.flos.core.ProductOptions
import cz.flos.core.Qty
import cz.flos.core.VatRate
import cz.flos.pos.data.AppSettings
import cz.flos.pos.data.ProductEntity

// Selling extras added in Flos v2: quantity typed before tapping a product, add-ons from Settings,
// kitchen notes on a line, open-price products and items typed in by hand.

/** Quantity for the next product tapped ("5 ×"). Empty or Zrušit = back to 1. */
@Composable
internal fun NextQtyDialog(current: Double?, onDismiss: () -> Unit, onSet: (Double?) -> Unit) {
    var text by remember { mutableStateOf(current?.let(Qty::format).orEmpty()) }
    val parsed = Qty.parse(text)?.takeIf { it >= Qty.MIN }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Množství další položky")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Zadejte počet a pak klepněte na produkt. Platí jen pro jednu položku."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(2.0, 3.0, 4.0, 5.0, 6.0, 10.0).chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { q ->
                            FilterChip(selected = parsed == q, onClick = { onSet(q) }, shape = RoundedCornerShape(50),
                                label = { Text(Qty.format(q) + " ×", fontWeight = FontWeight.Bold) })
                        }
                    }
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text(tr("Množství")) },
                    isError = text.isNotBlank() && parsed == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { parsed?.let(onSet) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { Button(onClick = { onSet(parsed) }, enabled = parsed != null) { Text(tr("Nastavit")) } },
        dismissButton = { TextButton(onClick = { onSet(null) }) { Text(tr("Zrušit")) } },
    )
}

/** Ready-made notes as chips plus a free note; used when adding a product and on a cart line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteEditor(note: String, quick: List<String>, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("Poznámka pro kuchyň"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        if (quick.isNotEmpty()) {
            val chosen = note.split(',').map { it.trim().lowercase() }.toSet()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                quick.forEach { q ->
                    FilterChip(selected = q.lowercase() in chosen, onClick = { onChange(LineNote.toggle(note, q)) },
                        label = { Text(q) }, shape = RoundedCornerShape(50))
                }
            }
        }
        OutlinedTextField(
            value = note, onValueChange = { onChange(it.replace(LineNote.SEP.trim(), "")) },
            placeholder = { Text(tr("Např. bez soli, na talíř zvlášť")) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Shown when a product with variants, extras or add-ons, or an open-price product, is tapped:
 * the choices, a price field when the price is typed in at the till, and a kitchen note.
 * [onAdd] gets the line name (with choices and note) and the unit price.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProductPickDialog(
    p: ProductEntity,
    groups: List<OptionGroup>,
    quickNotes: List<String>,
    showNotes: Boolean,
    onDismiss: () -> Unit,
    onAdd: (name: String, price: Long) -> Unit,
) {
    var chosen by remember { mutableStateOf(ProductOptions.defaults(groups)) }
    var note by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf(if (p.openPrice && p.price > 0) Money.format(p.price, "") else "") }
    val typed = Money.parse(priceText)?.takeIf { it >= 0 }
    val base = if (p.openPrice) typed ?: 0L else p.price
    val (name, price) = ProductOptions.apply(p.name, base, groups, chosen)
    val ok = !p.openPrice || typed != null
    val add = { if (ok) onAdd(LineNote.join(name, note), price) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(p.name) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (p.openPrice) {
                    OutlinedTextField(
                        value = priceText, onValueChange = { priceText = it },
                        label = { Text(tr("Cena vč. DPH (Kč)")) },
                        isError = priceText.isNotBlank() && typed == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { add() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                groups.forEachIndexed { gi, g ->
                    Text(g.name + if (g.multiple) " " + tr("(libovolně)") else "", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        g.choices.forEachIndexed { ci, c ->
                            val selected = ci in chosen[gi].orEmpty()
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val now = chosen[gi].orEmpty()
                                    val next = when {
                                        !g.multiple -> setOf(ci)
                                        selected -> now - ci
                                        else -> now + ci
                                    }
                                    chosen = chosen + (gi to next)
                                },
                                label = { Text(c.name + if (c.price != 0L) "  " + (if (c.price > 0) "+" else "") + Money.format(c.price) else "") },
                                shape = RoundedCornerShape(50),
                            )
                        }
                    }
                }
                if (showNotes) Column(Modifier.padding(top = 8.dp)) { NoteEditor(note, quickNotes) { note = it } }
            }
        },
        confirmButton = { Button(onClick = add, enabled = ok) { Text(tr("Přidat · %s", Money.format(price))) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zrušit")) } },
    )
}

/** Kitchen note on a line already in the cart. */
@Composable
internal fun LineNoteDialog(lineName: String, quickNotes: List<String>, onDismiss: () -> Unit, onSet: (String) -> Unit) {
    val (base, current) = LineNote.split(lineName)
    var note by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(base) },
        text = { NoteEditor(note, quickNotes) { note = it } },
        confirmButton = { Button(onClick = { onSet(note) }) { Text(tr("Uložit")) } },
        dismissButton = {
            Row {
                if (current.isNotEmpty()) TextButton(onClick = { onSet("") }) { Text(tr("Smazat poznámku")) }
                TextButton(onClick = onDismiss) { Text(tr("Zrušit")) }
            }
        },
    )
}

/** Something that is not in the catalogue: name, price and VAT rate typed in at the till. */
@Composable
internal fun ManualItemDialog(vatPayer: Boolean, onDismiss: () -> Unit, onAdd: (String, Long, VatRate) -> Unit) {
    var name by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var vat by remember { mutableStateOf(if (vatPayer) VatRate.STANDARD else VatRate.ZERO) }
    val price = Money.parse(priceText)?.takeIf { it >= 0 }
    val ok = name.isNotBlank() && price != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Ruční položka")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Pro zboží, které není v seznamu produktů. Cenu zadáte sami."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, label = { Text(tr("Název")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    priceText, { priceText = it }, label = { Text(tr("Cena vč. DPH (Kč)")) }, singleLine = true,
                    isError = priceText.isNotBlank() && price == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
                if (vatPayer) {
                    Text(tr("Sazba DPH"), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VatRate.entries.forEach { r ->
                            FilterChip(selected = vat == r, onClick = { vat = r }, label = { Text(r.label) })
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { if (ok) onAdd(name, price!!, vat) }, enabled = ok) { Text(tr("Přidat")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zrušit")) } },
    )
}

// ---- Settings: add-ons for categories and ready-made kitchen notes ----

@Composable
fun AddonsSettings(vm: PosViewModel, s: AppSettings, showNotes: Boolean = true) {
    val categories by vm.categories.collectAsState()
    val list = Addons.parse(s.addons)
    var editing by remember { mutableStateOf<Int?>(null) }
    fun save(next: List<AddonGroup>) = vm.updateSettings { it.copy(addons = Addons.format(next)) }

    if (showNotes) Section(tr("Přídavky a poznámky pro kuchyň"))
    ExtrasHint(tr("Přídavky se nabídnou po klepnutí na produkt z vybraných kategorií (nebo u všech produktů). Mají vlastní cenu a lze je vybrat nebo nechat být."))
    list.forEachIndexed { i, a ->
        Card(Modifier.fillMaxWidth().clickable { editing = i }) {
            Column(Modifier.padding(12.dp)) {
                Text(a.group.name + if (a.group.multiple) " " + tr("(libovolně)") else "", fontWeight = FontWeight.Bold)
                Text(a.group.choices.joinToString(", ") { c -> c.name + if (c.price != 0L) " " + (if (c.price > 0) "+" else "") + Money.format(c.price) else "" },
                    style = MaterialTheme.typography.bodySmall)
                Text(if (a.forAll) tr("Všechny produkty") else tr("Kategorie: %s", a.categories.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    OutlinedButton(onClick = { editing = -1 }) { Icon(Icons.Rounded.Add, null); Text(tr("  Přidat skupinu přídavků")) }
    if (showNotes) OutlinedTextField(
        s.quickNotes, { v -> vm.updateSettings { it.copy(quickNotes = v) } },
        label = { Text(tr("Rychlé poznámky (oddělené čárkou)")) }, minLines = 2, modifier = Modifier.fillMaxWidth(),
        supportingText = { Text(tr("Nabídnou se při přidání produktu a po klepnutí na název položky v košíku.")) },
    )

    editing?.let { i ->
        AddonGroupDialog(
            initial = list.getOrNull(i),
            categoryNames = categories.map { it.name },
            onDismiss = { editing = null },
            onDelete = { save(list.filterIndexed { j, _ -> j != i }); editing = null },
            onSave = { g -> save(if (i in list.indices) list.toMutableList().also { it[i] = g } else list + g); editing = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddonGroupDialog(
    initial: AddonGroup?,
    categoryNames: List<String>,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (AddonGroup) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.group?.name.orEmpty()) }
    var multiple by remember { mutableStateOf(initial?.group?.multiple ?: true) }
    val cats = remember { mutableStateListOf<String>().also { it.addAll(initial?.categories.orEmpty()) } }
    // Each row: name and price text as typed.
    val rows = remember {
        mutableStateListOf<Pair<String, String>>().also { l ->
            initial?.group?.choices?.forEach { c -> l.add(c.name to if (c.price == 0L) "" else Money.format(c.price, "")) }
            if (l.isEmpty()) l.add("" to "")
        }
    }
    val choices = rows.mapNotNull { (n, p) ->
        // Names may not contain the characters the stored text uses as separators.
        val clean = n.replace(Regex("[,;:\\[\\]*]"), " ").trim()
        if (clean.isEmpty()) null else OptionChoice(clean, if (p.isBlank()) 0L else Money.parse(p) ?: return@mapNotNull null)
    }
    val badPrice = rows.any { (n, p) -> n.isNotBlank() && p.isNotBlank() && Money.parse(p) == null }
    val cleanName = name.replace(Regex("[:\\[\\]*]"), " ").trim()
    val ok = cleanName.isNotEmpty() && choices.isNotEmpty() && !badPrice

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) tr("Nová skupina přídavků") else tr("Upravit přídavky")) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(tr("Název skupiny (např. Přísady, Omáčka)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Lze vybrat více"), Modifier.weight(1f))
                    Switch(checked = multiple, onCheckedChange = { multiple = it })
                }
                if (!multiple) Text(tr("Vybere se právě jedna možnost, první je předvybraná."), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tr("Přídavky a ceny"), style = MaterialTheme.typography.labelLarge)
                rows.forEachIndexed { i, (n, p) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(n, { rows[i] = it to p }, label = { Text(tr("Název")) }, singleLine = true, modifier = Modifier.weight(1.6f))
                        OutlinedTextField(
                            p, { rows[i] = n to it }, label = { Text(tr("+ Kč")) }, singleLine = true,
                            isError = p.isNotBlank() && Money.parse(p) == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { rows.removeAt(i); if (rows.isEmpty()) rows.add("" to "") }) { Icon(Icons.Rounded.Close, tr("Odebrat")) }
                    }
                }
                TextButton(onClick = { rows.add("" to "") }) { Icon(Icons.Rounded.Add, null); Text(tr(" Další přídavek")) }
                Text(tr("Pro kategorie"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = cats.isEmpty(), onClick = { cats.clear() }, label = { Text(tr("Všechny produkty")) })
                    categoryNames.forEach { c ->
                        val on = cats.any { it.equals(c, ignoreCase = true) }
                        FilterChip(selected = on, onClick = { if (on) cats.removeAll { it.equals(c, ignoreCase = true) } else cats.add(c) }, label = { Text(c) })
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = ok, onClick = {
                onSave(AddonGroup(OptionGroup(cleanName, multiple, choices), cats.map { it.replace(Regex("[,;\\[\\]]"), " ").trim() }.filter { it.isNotEmpty() }))
            }) { Text(tr("Uložit")) }
        },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = onDelete) { Text(tr("Smazat")) }
                TextButton(onClick = onDismiss) { Text(tr("Zrušit")) }
            }
        },
    )
}

/** Name of a cart line without its note, and the note in italics under it. */
@Composable
internal fun LineNameWithNote(name: String) {
    val (base, note) = LineNote.split(name)
    Text(base, fontWeight = FontWeight.Medium)
    if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ExtrasHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
