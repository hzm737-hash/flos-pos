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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.TableRestaurant
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AssistChip
import cz.flos.core.OptionGroup
import cz.flos.core.ProductOptions
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.RoomService
import cz.flos.core.LineNote
import cz.flos.pos.data.AdvFeature
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
import cz.flos.core.Qty
import cz.flos.core.Text as CoreText
import cz.flos.pos.data.ProductEntity

@Composable
fun SellScreen(vm: PosViewModel) {
    val cart by vm.cart.collectAsState()
    var paying by remember { mutableStateOf(false) }
    var parking by remember { mutableStateOf(false) }
    var splitting by remember { mutableStateOf(false) }
    var partToPay by remember { mutableStateOf<Map<Int, Double>?>(null) }
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
                CartPanel(vm, cart, onPay = { paying = true }, onPark = { parking = true }, onSplit = { splitting = true })
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            if (showCartOnPhone) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showCartOnPhone = false }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Zpět")) }
                    Text(tr("Košík"), style = MaterialTheme.typography.titleLarge)
                }
                CartPanel(vm, cart, onPay = { paying = true }, onPark = { parking = true }, onSplit = { splitting = true }, modifier = Modifier.weight(1f))
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
                        Text("  ${Qty.format(cart.itemCount)} ks", color = Color.White)
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
            onMixed = { parts, given -> vm.checkoutMixed(parts, given) { paying = false; showCartOnPhone = false } },
        ) { method, given ->
            vm.checkout(method, given) { paying = false; showCartOnPhone = false }
        }
    }
    if (parking) {
        val order by vm.editingOrder.collectAsState()
        OpenOrderDialog(order?.note.orEmpty(), table = vm.currentTable, onDismiss = { parking = false }) { note, print ->
            vm.saveOpenOrder(note, print); parking = false; showCartOnPhone = false
        }
    }
    if (splitting) {
        SplitDialog(cart, onDismiss = { splitting = false }) { selection ->
            splitting = false
            partToPay = selection
        }
    }
    partToPay?.let { selection ->
        val part = cz.flos.core.Cart(
            cart.items.mapIndexedNotNull { i, item -> (selection[i] ?: 0.0).takeIf { it >= Qty.MIN }?.let { item.copy(quantity = it.coerceAtMost(item.quantity)) } },
            cart.discountPercent,
        )
        PaymentDialog(vm, part.total, title = tr("Platba části účtu"), onDismiss = { partToPay = null }) { method, given ->
            vm.payPart(selection, method, given) { partToPay = null }
        }
    }
}

/** Split bill: choose how many of each line this guest pays; the rest stays open. */
@Composable
private fun SplitDialog(cart: Cart, onDismiss: () -> Unit, onPay: (Map<Int, Double>) -> Unit) {
    var chosen by remember { mutableStateOf(mapOf<Int, Double>()) }
    val part = cz.flos.core.Cart(
        cart.items.mapIndexedNotNull { i, item -> (chosen[i] ?: 0.0).takeIf { it >= Qty.MIN }?.let { item.copy(quantity = it) } },
        cart.discountPercent,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.CallSplit, null) },
        title = { Text(tr("Rozdělit účet")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Vyberte, co platí tento host. Zbytek zůstane v košíku pro další platbu."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed(cart.items) { i, item ->
                        val q = chosen[i] ?: 0.0
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, fontWeight = FontWeight.Medium)
                                Text(tr("%s z %s", Qty.format(q), Qty.format(item.quantity)) + " · " + Money.format(item.unitPrice), style = MaterialTheme.typography.bodySmall)
                            }
                            QtyStepper(q, item.quantity) { chosen = chosen + (i to it) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { chosen = cart.items.indices.associateWith { cart.items[it].quantity } }) { Text(tr("Vybrat vše")) }
                    TextButton(onClick = { chosen = emptyMap() }) { Text(tr("Nic")) }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onPay(chosen.filterValues { it >= Qty.MIN }) }, enabled = !part.isEmpty()) {
                Text(tr("Zaplatit %s", Money.format(part.total)))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zrušit")) } },
    )
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
    var choosing by remember { mutableStateOf<Pair<ProductEntity, List<OptionGroup>>?>(null) }
    val settings by vm.settings.collectAsState()
    choosing?.let { (p, groups) ->
        ProductPickDialog(p, groups, LineNote.quickNotes(settings.quickNotes), showNotes = settings.on(AdvFeature.NOTES), onDismiss = { choosing = null }) { name, price ->
            if (p.openPrice) vm.addOpenPrice(p, name, price) else vm.addToCart(p, name, price)
            choosing = null
        }
    }
    var askQty by remember { mutableStateOf(false) }
    var manualItem by remember { mutableStateOf(false) }
    val pendingQty by vm.pendingQty.collectAsState()
    if (askQty) NextQtyDialog(pendingQty, onDismiss = { askQty = false }) { q -> vm.setPendingQty(q); askQty = false }
    if (manualItem) ManualItemDialog(settings.vatPayer, onDismiss = { manualItem = false }) { name, price, vat ->
        vm.addManualItem(name, price, vat); manualItem = false
    }
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
                modifier = Modifier.weight(1f).onPreviewKeyEvent {
                    if ((it.key == Key.Enter || it.key == Key.NumPadEnter) && it.type == KeyEventType.KeyUp) { submit(); true }
                    else it.key == Key.Enter || it.key == Key.NumPadEnter
                },
            )
            // Half portion: switch on, then tap the product; it is added as 0,5.
            val half by vm.halfPortion.collectAsState()
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = half, onClick = { vm.toggleHalfPortion() }, shape = RoundedCornerShape(50),
                label = { Text(tr("½ porce"), fontWeight = FontWeight.Bold) },
            )
            // Quantity typed in before tapping a product: "5 ×", then the product (Pokročilé nastavení).
            if (settings.on(AdvFeature.QTY_BEFORE)) {
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = pendingQty != null, onClick = { askQty = true }, shape = RoundedCornerShape(50),
                    label = { Text(pendingQty?.let { Qty.format(it) + " ×" } ?: "× " + tr("ks"), fontWeight = FontWeight.Bold) },
                )
            }
            if (settings.on(AdvFeature.OPEN_PRICE)) IconButton(onClick = { manualItem = true }) { Icon(Icons.Rounded.EditNote, tr("Ruční položka")) }
        }
        PricingChips(vm)
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
                ) {
                    val groups = vm.optionGroupsFor(p)
                    if (groups.isEmpty() && !p.openPrice) vm.addToCart(p) else choosing = p to groups
                }
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
            Text(if (p.openPrice) tr("Cena?") else Money.format(p.price), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CartPanel(vm: PosViewModel, cart: Cart, onPay: () -> Unit, onPark: () -> Unit, onSplit: () -> Unit, modifier: Modifier = Modifier) {
    var discountDialog by remember { mutableStateOf(false) }
    var quantityOf by remember { mutableStateOf<Int?>(null) }
    var noteOf by remember { mutableStateOf<Int?>(null) }
    val settings by vm.settings.collectAsState()
    val order by vm.editingOrder.collectAsState()
    val pendingTable by vm.pendingTable.collectAsState()
    Column(modifier.fillMaxHeight().padding(14.dp)) {
        if (order == null) pendingTable?.let { t ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.TableRestaurant, null)
                    Text(tr("Stůl %s", t), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    IconButton(onClick = { vm.clearCart() }) { Icon(Icons.Rounded.Close, tr("Zavřít")) }
                }
            }
        }
        order?.let { o ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (o.tableName != null) Icons.Rounded.TableRestaurant else Icons.Rounded.DeliveryDining, null)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(o.tableName?.let { tr("Stůl %s", it) } ?: tr("Objednávka č. %d", o.orderNumber ?: 0), fontWeight = FontWeight.SemiBold)
                        if (o.note.isNotBlank()) Text(o.note, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { vm.clearCart() }) { Icon(Icons.Rounded.Close, tr("Zavřít")) }
                }
            }
        }
        PeopleRow(vm)
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
                        // Tap the name for a kitchen note ("pálivé, bez cibule").
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(enabled = settings.on(AdvFeature.NOTES)) { noteOf = i }) {
                            LineNameWithNote(item.name)
                            Text(
                                Money.format(item.unitPrice) + if (item.discountPercent > 0) "  −${item.discountPercent} %" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FilledTonalIconButton(onClick = { vm.setQuantity(i, item.quantity - 1) }, modifier = Modifier.size(36.dp)) {
                            Icon(if (item.quantity <= 1.0) Icons.Rounded.DeleteOutline else Icons.Rounded.Remove, tr("Méně"))
                        }
                        // Tap the number for a half portion or any other amount.
                        Text(
                            Qty.format(item.quantity), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                            color = if (Qty.isWhole(item.quantity)) Color.Unspecified else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(44.dp).clip(RoundedCornerShape(8.dp)).clickable { quantityOf = i }.padding(vertical = 8.dp),
                        )
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
            val w = Modifier.weight(1f)
            SmallAction(Icons.Rounded.ReceiptLong, tr("Předúčet"), enabled = !cart.isEmpty(), modifier = w) { vm.printPreBill() }
            SmallAction(Icons.Rounded.DeliveryDining, tr("Odložit"), enabled = !cart.isEmpty(), modifier = w, onClick = onPark)
            SmallAction(Icons.Rounded.CallSplit, tr("Rozdělit"), enabled = !cart.isEmpty(), modifier = w, onClick = onSplit)
            SmallAction(Icons.Rounded.Percent, tr("Sleva"), enabled = !cart.isEmpty(), modifier = w) { discountDialog = true }
            if (settings.on(AdvFeature.SERVICE_CHARGE)) SmallAction(Icons.Rounded.RoomService, tr("Servis"), enabled = !cart.isEmpty(), modifier = w) { vm.toggleServiceCharge() }
            SmallAction(Icons.Rounded.DeleteOutline, tr("Vymazat"), enabled = !cart.isEmpty() || order != null || pendingTable != null, modifier = w) { vm.clearCart() }
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
    noteOf?.let { i ->
        cart.items.getOrNull(i)?.let { item ->
            LineNoteDialog(item.name, LineNote.quickNotes(settings.quickNotes), onDismiss = { noteOf = null }) { note ->
                vm.setLineNote(i, note); noteOf = null
            }
        } ?: run { noteOf = null }
    }
    quantityOf?.let { i ->
        cart.items.getOrNull(i)?.let { item ->
            QuantityDialog(item.name, item.unitPrice, item.quantity, onDismiss = { quantityOf = null }) { q ->
                vm.setQuantity(i, q); quantityOf = null
            }
        } ?: run { quantityOf = null }
    }
    if (discountDialog) {
        DiscountDialog(current = cart.discountPercent, onDismiss = { discountDialog = false }) {
            vm.setCartDiscount(it); discountDialog = false
        }
    }
}

/** Who serves (switch with a staff PIN) and which customer the cart belongs to. */
@Composable
private fun PeopleRow(vm: PosViewModel) {
    val settings by vm.settings.collectAsState()
    val customer by vm.customer.collectAsState()
    var picking by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(
            onClick = { pin = true },
            label = { Text(settings.cashier.ifBlank { tr("Obsluha") }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Rounded.Badge, null, Modifier.size(18.dp)) },
            modifier = Modifier.weight(1f),
        )
        AssistChip(
            onClick = { picking = true },
            label = {
                Text(customer?.let { c -> c.name + if (settings.loyaltyCrownsPerPoint > 0) " · " + tr("%d bodů", c.points) else "" } ?: tr("Zákazník"),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            leadingIcon = { Icon(Icons.Rounded.Person, null, Modifier.size(18.dp)) },
            modifier = Modifier.weight(1f),
        )
    }
    if (picking) CustomerPickerDialog(vm, onDismiss = { picking = false })
    if (pin) PinDialog(vm, onDismiss = { pin = false })
}

/** Icon-over-label button for the cart's secondary actions. */
@Composable
private fun SmallAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
        modifier = modifier.height(58.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/** Saves the cart as an unpaid order: for a delivery, takeaway or a table. */
@Composable
private fun OpenOrderDialog(initialNote: String, table: String?, onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit) {
    var note by remember { mutableStateOf(initialNote) }
    var print by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.DeliveryDining, null) },
        title = { Text(table?.let { tr("Uložit účet stolu %s", it) } ?: tr("Odložit jako objednávku")) },
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

/** Service type (na místě, s sebou, rozvoz) and price list of the cart; hidden when none are set up. */
@Composable
private fun PricingChips(vm: PosViewModel) {
    val s by vm.settings.collectAsState()
    val products by vm.products.collectAsState()
    val service by vm.serviceType.collectAsState()
    val list by vm.priceList.collectAsState()
    val types = s.serviceTypeList
    val lists = remember(products, s.serviceTypes) { vm.priceListNames() }
    if (types.isEmpty() && lists.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(types, key = { "t" + it.name }) { t ->
            val on = service != null && cz.flos.core.Pricing.key(service!!) == cz.flos.core.Pricing.key(t.name)
            FilterChip(
                selected = on, onClick = { vm.setPricing(if (on) null else t.name, list) }, shape = RoundedCornerShape(50),
                label = { Text(t.name + if (t.percent != 0.0) " " + (if (t.percent > 0) "+" else "") + Qty.format(t.percent) + " %" else "") },
            )
        }
        items(lists, key = { "l$it" }) { name ->
            val on = list != null && cz.flos.core.Pricing.key(list!!) == cz.flos.core.Pricing.key(name)
            FilterChip(
                selected = on, onClick = { vm.setPricing(service, if (on) null else name) }, shape = RoundedCornerShape(50),
                label = { Text(tr("Ceník %s", name)) },
            )
        }
    }
}
