package cz.flos.pos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeliveryDining
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.launch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.flos.core.Cart
import cz.flos.core.Money
import cz.flos.core.Text as CoreText
import cz.flos.pos.data.ProductEntity

@Composable
fun SellScreen(vm: PosViewModel) {
    val cart by vm.cart.collectAsState()
    var paying by remember { mutableStateOf(false) }
    var parking by remember { mutableStateOf(false) }
    var showCartOnPhone by rememberSaveable { mutableStateOf(false) }

    if (isWide()) {
        Row(Modifier.fillMaxSize()) {
            ProductGrid(vm, Modifier.weight(1f))
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                shadowElevation = 6.dp,
                modifier = Modifier.width(380.dp).fillMaxHeight(),
            ) {
                CartPanel(vm, cart, onPay = { paying = true }, onPark = { parking = true })
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            if (showCartOnPhone) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showCartOnPhone = false }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Zpět")) }
                    Text(tr("Košík"), style = MaterialTheme.typography.titleLarge)
                }
                CartPanel(vm, cart, onPay = { paying = true }, onPark = { parking = true }, modifier = Modifier.weight(1f))
            } else {
                ProductGrid(vm, Modifier.weight(1f))
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.clickable(enabled = !cart.isEmpty()) { showCartOnPhone = true }.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.ShoppingBag, null, tint = Color.White)
                        Text("  ${cart.itemCount} ks", color = Color.White)
                        Spacer(Modifier.weight(1f))
                        Text(Money.format(cart.total), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = { paying = true },
                            enabled = !cart.isEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = FlosCoral, contentColor = Color.White),
                        ) { Text(tr("Zaplatit")) }
                    }
                }
            }
        }
    }

    if (paying) {
        val order by vm.editingOrder.collectAsState()
        PaymentDialog(
            vm, cart.total,
            title = order?.orderNumber?.let { tr("Objednávka č. %d", it) } ?: tr("Platba"),
            onDismiss = { paying = false },
        ) { method, given ->
            vm.checkout(method, given) { paying = false; showCartOnPhone = false }
        }
    }
    if (parking) {
        val order by vm.editingOrder.collectAsState()
        OpenOrderDialog(order?.note.orEmpty(), onDismiss = { parking = false }) { note, print ->
            vm.saveOpenOrder(note, print); parking = false; showCartOnPhone = false
        }
    }
}

@Composable
private fun ProductGrid(vm: PosViewModel, modifier: Modifier = Modifier) {
    val products by vm.products.collectAsState()
    val categories by vm.categories.collectAsState()
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val byId = categories.associateBy { it.id }
    val q = CoreText.stripDiacritics(query.trim()).lowercase()
    val shown = products
        .filter { selected == null || it.categoryId == selected }
        .filter { q.isEmpty() || CoreText.stripDiacritics(it.name).lowercase().contains(q) || it.barcode == query.trim() }
    val scope = rememberCoroutineScope()
    // A barcode scanner types the code and presses Enter: add the product with that code,
    // or the only product the search shows.
    val submit: () -> Unit = {
        val code = query.trim()
        if (code.isNotEmpty()) scope.launch {
            val added = vm.addByBarcode(code) || shown.singleOrNull()?.let { vm.addToCart(it); true } == true
            if (added) query = "" else vm.notify(tr("Produkt s kódem %s nenalezen", code))
        }
    }

    Column(modifier.fillMaxHeight()) {
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text(tr("Hledat produkt")) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, tr("Vymazat")) } },
                singleLine = true,
                shape = RoundedCornerShape(50),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }, onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
                    if ((it.key == Key.Enter || it.key == Key.NumPadEnter) && it.type == KeyEventType.KeyUp) { submit(); true }
                    else it.key == Key.Enter || it.key == Key.NumPadEnter
                },
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { FilterChip(selected = selected == null, onClick = { selected = null }, label = { Text(tr("Vše")) }, shape = RoundedCornerShape(50)) }
            items(categories, key = { it.id }) { c ->
                FilterChip(
                    selected = selected == c.id, onClick = { selected = c.id }, label = { Text(c.name) }, shape = RoundedCornerShape(50),
                    leadingIcon = ProductIcons.of(c.icon)?.let { icon -> { Icon(icon, null, Modifier.size(18.dp)) } },
                )
            }
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (query.isBlank()) tr("Žádné produkty. Přidejte je na kartě Produkty.") else tr("Nic nenalezeno"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(140.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shown, key = { it.id }) { p ->
                val category = p.categoryId?.let(byId::get)
                ProductTile(
                    p,
                    color = category?.let { Color(it.color) } ?: MaterialTheme.colorScheme.primary,
                    iconKey = p.icon ?: category?.icon,
                ) { vm.addToCart(p) }
            }
        }
    }
}

@Composable
private fun ProductTile(p: ProductEntity, color: Color, iconKey: String?, onClick: () -> Unit) {
    val content = contentColorOn(color)
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = color, contentColor = content),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp, pressedElevation = 0.dp),
        modifier = Modifier.height(112.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.Top) {
                Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                ProductIcons.of(iconKey)?.let { icon ->
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(content.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, null, Modifier.size(20.dp)) }
                }
            }
            Text(Money.format(p.price), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CartPanel(vm: PosViewModel, cart: Cart, onPay: () -> Unit, onPark: () -> Unit, modifier: Modifier = Modifier) {
    var discountDialog by remember { mutableStateOf(false) }
    val order by vm.editingOrder.collectAsState()
    Column(modifier.fillMaxHeight().padding(14.dp)) {
        order?.let { o ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DeliveryDining, null)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(tr("Objednávka č. %d", o.orderNumber ?: 0), fontWeight = FontWeight.SemiBold)
                        if (o.note.isNotBlank()) Text(o.note, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { vm.clearCart() }) { Icon(Icons.Rounded.Close, tr("Zavřít")) }
                }
            }
        }
        if (cart.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.ShoppingBag, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                    Text(tr("Košík je prázdný"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(cart.items) { i, item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontWeight = FontWeight.Medium)
                            Text(
                                Money.format(item.unitPrice) + if (item.discountPercent > 0) "  −${item.discountPercent} %" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FilledTonalIconButton(onClick = { vm.setQuantity(i, item.quantity - 1) }, modifier = Modifier.size(36.dp)) {
                            Icon(if (item.quantity == 1) Icons.Rounded.DeleteOutline else Icons.Rounded.Remove, tr("Méně"))
                        }
                        Text("${item.quantity}", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(32.dp))
                        FilledTonalIconButton(onClick = { vm.setQuantity(i, item.quantity + 1) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.Add, tr("Více"))
                        }
                        Text(Money.format(item.total, ""), modifier = Modifier.width(76.dp), textAlign = TextAlign.End)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        if (cart.cartDiscount > 0) {
            Row(Modifier.fillMaxWidth()) {
                Text(tr("Sleva %d %%", cart.discountPercent))
                Spacer(Modifier.weight(1f))
                Text(Money.format(-cart.cartDiscount))
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Celkem"), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            Text(Money.format(cart.total), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallAction(Icons.Rounded.ReceiptLong, tr("Předúčet"), enabled = !cart.isEmpty()) { vm.printPreBill() }
            SmallAction(Icons.Rounded.DeliveryDining, tr("Odložit"), enabled = !cart.isEmpty(), onClick = onPark)
            SmallAction(Icons.Rounded.Percent, tr("Sleva"), enabled = !cart.isEmpty()) { discountDialog = true }
            SmallAction(Icons.Rounded.DeleteOutline, tr("Vymazat"), enabled = !cart.isEmpty() || order != null) { vm.clearCart() }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onPay, enabled = !cart.isEmpty(),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = FlosCoral, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(58.dp),
        ) {
            Text(tr("Zaplatit") + "  " + Money.format(cart.total), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
    if (discountDialog) {
        DiscountDialog(current = cart.discountPercent, onDismiss = { discountDialog = false }) {
            vm.setCartDiscount(it); discountDialog = false
        }
    }
}

/** Icon-over-label button for the cart's secondary actions. */
@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
        modifier = Modifier.height(58.dp).width(84.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/** Saves the cart as an unpaid order: for a delivery, takeaway or a table. */
@Composable
private fun OpenOrderDialog(initialNote: String, onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit) {
    var note by remember { mutableStateOf(initialNote) }
    var print by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.DeliveryDining, null) },
        title = { Text(tr("Odložit jako objednávku")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    tr("Objednávka zůstane nezaplacená, např. pro rozvoz. Až zákazník zaplatí, dokončíte ji na kartě Objednávky libovolným způsobem platby. Pokud nezaplatí, objednávku zrušíte. Do EET se odesílá až zaplacená tržba."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text(tr("Poznámka (adresa, zákazník, stůl)")) },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { print = !print }) {
                    Checkbox(checked = print, onCheckedChange = { print = it })
                    Text(tr("Vytisknout objednávku (pro řidiče / kuchyň)"))
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(note, print) }) { Text(tr("Uložit")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zrušit")) } },
    )
}

@Composable
private fun DiscountDialog(current: Int, onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Sleva na celý nákup")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 5, 10, 15, 20, 50).chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { p ->
                            FilterChip(selected = current == p, onClick = { onSet(p) }, label = { Text(if (p == 0) tr("Bez slevy") else "$p %") })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Zavřít")) } },
    )
}
