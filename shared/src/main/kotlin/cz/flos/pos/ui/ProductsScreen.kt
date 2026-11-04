package cz.flos.pos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.flos.core.Money
import cz.flos.core.VatRate
import cz.flos.pos.data.CategoryEntity
import cz.flos.pos.data.ProductEntity

private val palette = TilePalette

/** Grid of icons to choose from; null means "no icon" (or the category icon for a product). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun IconPicker(selected: String?, noneLabel: String, onSelect: (String?) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(noneLabel) })
        ProductIcons.all.forEach { (key, icon) ->
            val on = key == selected
            Box(
                Modifier.size(40.dp)
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .clickable { onSelect(key) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, key, tint = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ProductsScreen(vm: PosViewModel) {
    val products by vm.products.collectAsState()
    val categories by vm.categories.collectAsState()
    val settings by vm.settings.collectAsState()
    var editing by remember { mutableStateOf<ProductEntity?>(null) }
    var editingCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    val catName = categories.associate { it.id to it.name }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Text(tr("Kategorie"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(categories, key = { it.id }) { c ->
                    InputChip(
                        selected = false,
                        onClick = { editingCategory = c },
                        label = { Text(c.name) },
                        leadingIcon = { Box(Modifier.size(12.dp).background(Color(c.color), CircleShape)) },
                    )
                }
                item {
                    FilterChip(selected = false, onClick = { editingCategory = CategoryEntity(name = "", color = palette[categories.size % palette.size]) },
                        label = { Text(tr("Nová kategorie")) }, leadingIcon = { Icon(Icons.Filled.Add, null) })
                }
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
                items(products, key = { it.id }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editing = p }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.Medium)
                            Text(
                                listOfNotNull(p.categoryId?.let { catName[it] }, if (settings.vatPayer) tr("DPH %s", "${p.vatPercent} %") else null).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(Money.format(p.price))
                    }
                    HorizontalDivider()
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { editing = ProductEntity(name = "", price = 0, vatPercent = if (settings.vatPayer) 21 else 0) },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text(tr("Nový produkt")) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    editing?.let { p ->
        ProductDialog(p, categories, settings.vatPayer,
            onDismiss = { editing = null },
            onSave = { vm.saveProduct(it); editing = null },
            onDelete = { vm.deleteProduct(p); editing = null })
    }
    editingCategory?.let { c ->
        CategoryDialog(c,
            onDismiss = { editingCategory = null },
            onSave = { vm.saveCategory(it); editingCategory = null },
            onDelete = { vm.deleteCategory(c); editingCategory = null })
    }
}

@Composable
private fun ProductDialog(
    initial: ProductEntity,
    categories: List<CategoryEntity>,
    vatPayer: Boolean,
    onDismiss: () -> Unit,
    onSave: (ProductEntity) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var price by remember { mutableStateOf(if (initial.price == 0L) "" else Money.format(initial.price, "")) }
    var vat by remember { mutableStateOf(initial.vatPercent) }
    var category by remember { mutableStateOf(initial.categoryId) }
    var icon by remember { mutableStateOf(initial.icon) }
    var barcode by remember { mutableStateOf(initial.barcode.orEmpty()) }
    var options by remember { mutableStateOf(initial.options.orEmpty()) }
    val parsed = Money.parse(price)
    val valid = name.isNotBlank() && parsed != null && parsed >= 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) tr("Nový produkt") else tr("Upravit produkt")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(tr("Název")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(barcode, { barcode = it }, label = { Text(tr("Čárový kód (EAN)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    price, { price = it }, label = { Text(tr("Cena vč. DPH (Kč)")) }, singleLine = true,
                    isError = price.isNotEmpty() && parsed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
                if (vatPayer) {
                    Text(tr("Sazba DPH"), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VatRate.entries.forEach { r ->
                            FilterChip(selected = vat == r.percent, onClick = { vat = r.percent }, label = { Text(r.label) })
                        }
                    }
                }
                Text(tr("Kategorie"), style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    (listOf<CategoryEntity?>(null) + categories).chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { c ->
                                FilterChip(selected = category == c?.id, onClick = { category = c?.id }, label = { Text(c?.name ?: tr("Bez kategorie")) })
                            }
                        }
                    }
                }
                Text(tr("Ikona"), style = MaterialTheme.typography.labelLarge)
                IconPicker(icon, tr("Podle kategorie")) { icon = it }
                Text(tr("Varianty a přídavky"), style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    options, { options = it }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Velikost: Malá, Velká +40\nPřílohy*: Sýr +15, Slanina +25") },
                    supportingText = {
                        val groups = cz.flos.core.ProductOptions.parse(options)
                        Text(if (options.isBlank()) tr("Každý řádek jedna skupina: název, dvojtečka a volby oddělené čárkou s příplatkem v Kč. Hvězdička za názvem = lze vybrat více (přídavky).")
                            else tr("Skupin: %d · voleb: %d", groups.size, groups.sumOf { it.choices.size }))
                    },
                )
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                onSave(initial.copy(name = name.trim(), price = parsed ?: 0, vatPercent = vat, categoryId = category, icon = icon,
                    barcode = barcode.trim().ifEmpty { null }, options = options.trim().ifEmpty { null }))
            }) { Text(tr("Uložit")) }
        },
        dismissButton = {
            Row {
                if (initial.id != 0L) IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, tr("Smazat")) }
                Spacer(Modifier.size(4.dp))
                TextButton(onClick = onDismiss) { Text(tr("Zrušit")) }
            }
        },
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CategoryDialog(initial: CategoryEntity, onDismiss: () -> Unit, onSave: (CategoryEntity) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var color by remember { mutableStateOf(initial.color) }
    var icon by remember { mutableStateOf(initial.icon) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) tr("Nová kategorie") else tr("Upravit kategorii")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(tr("Název")) }, singleLine = true)
                Text(tr("Barva"), style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    palette.forEach { c ->
                        Box(
                            Modifier.size(if (c == color) 38.dp else 30.dp).background(Color(c), CircleShape).clickable { color = c },
                        )
                    }
                }
                Text(tr("Ikona"), style = MaterialTheme.typography.labelLarge)
                IconPicker(icon, tr("Bez ikony")) { icon = it }
            }
        },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { onSave(initial.copy(name = name.trim(), color = color, icon = icon)) }) { Text(tr("Uložit")) } },
        dismissButton = {
            Row {
                if (initial.id != 0L) IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, tr("Smazat")) }
                TextButton(onClick = onDismiss) { Text(tr("Zrušit")) }
            }
        },
    )
}
