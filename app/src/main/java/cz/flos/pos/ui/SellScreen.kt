package cz.flos.pos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.flos.core.Cart
import cz.flos.core.Money
import cz.flos.pos.data.ProductEntity

@Composable
fun SellScreen(vm: PosViewModel) {
    val cart by vm.cart.collectAsState()
    var paying by remember { mutableStateOf(false) }
    var showCartOnPhone by rememberSaveable { mutableStateOf(false) }

    if (isWide()) {
        Row(Modifier.fillMaxSize()) {
            ProductGrid(vm, Modifier.weight(1f))
            Surface(tonalElevation = 2.dp, modifier = Modifier.width(360.dp).fillMaxHeight()) {
                CartPanel(vm, cart, onPay = { paying = true })
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            if (showCartOnPhone) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showCartOnPhone = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zpět") }
                    Text("Košík", style = MaterialTheme.typography.titleLarge)
                }
                CartPanel(vm, cart, onPay = { paying = true }, modifier = Modifier.weight(1f))
            } else {
                ProductGrid(vm, Modifier.weight(1f))
                Surface(color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.clickable(enabled = !cart.isEmpty()) { showCartOnPhone = true }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.ShoppingCart, null, tint = Color.White)
                        Text("  ${cart.itemCount} ks", color = Color.White)
                        Spacer(Modifier.weight(1f))
                        Text(Money.format(cart.total), color = Color.White, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = { paying = true },
                            enabled = !cart.isEmpty(),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = FlosGold, contentColor = FlosGreen),
                        ) { Text("Zaplatit") }
                    }
                }
            }
        }
    }

    if (paying) {
        PaymentDialog(vm, cart, onDismiss = { paying = false }, onPaid = { paying = false; showCartOnPhone = false })
    }
}

@Composable
private fun ProductGrid(vm: PosViewModel, modifier: Modifier = Modifier) {
    val products by vm.products.collectAsState()
    val categories by vm.categories.collectAsState()
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    val colorOf = categories.associate { it.id to Color(it.color) }
    val shown = if (selected == null) products else products.filter { it.categoryId == selected }

    Column(modifier.fillMaxHeight()) {
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { FilterChip(selected = selected == null, onClick = { selected = null }, label = { Text("Vše") }) }
            items(categories, key = { it.id }) { c ->
                FilterChip(selected = selected == c.id, onClick = { selected = c.id }, label = { Text(c.name) })
            }
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Žádné produkty. Přidejte je na kartě Produkty.")
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(132.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(shown, key = { it.id }) { p ->
                ProductTile(p, colorOf[p.categoryId] ?: MaterialTheme.colorScheme.primary) { vm.addToCart(p) }
            }
        }
    }
}

@Composable
private fun ProductTile(p: ProductEntity, color: Color, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = color, contentColor = Color.White),
        modifier = Modifier.height(96.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(Money.format(p.price), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CartPanel(vm: PosViewModel, cart: Cart, onPay: () -> Unit, modifier: Modifier = Modifier) {
    var discountDialog by remember { mutableStateOf(false) }
    Column(modifier.fillMaxHeight().padding(12.dp)) {
        if (cart.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Košík je prázdný", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        IconButton(onClick = { vm.setQuantity(i, item.quantity - 1) }) {
                            Icon(if (item.quantity == 1) Icons.Filled.Delete else Icons.Filled.Remove, "Méně")
                        }
                        Text("${item.quantity}", fontWeight = FontWeight.Bold)
                        IconButton(onClick = { vm.setQuantity(i, item.quantity + 1) }) { Icon(Icons.Filled.Add, "Více") }
                        Text(Money.format(item.total, ""), modifier = Modifier.width(72.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                    HorizontalDivider()
                }
            }
        }
        if (cart.cartDiscount > 0) {
            Row(Modifier.fillMaxWidth()) {
                Text("Sleva ${cart.discountPercent} %")
                Spacer(Modifier.weight(1f))
                Text(Money.format(-cart.cartDiscount))
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Celkem", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            Text(Money.format(cart.total), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.clearCart() }, enabled = !cart.isEmpty()) { Text("Vymazat") }
            TextButton(onClick = { discountDialog = true }, enabled = !cart.isEmpty()) {
                Icon(Icons.Filled.Percent, null); Text(" Sleva")
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onPay, enabled = !cart.isEmpty(), modifier = Modifier.height(52.dp)) {
                Text("Zaplatit", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    if (discountDialog) {
        DiscountDialog(current = cart.discountPercent, onDismiss = { discountDialog = false }) {
            vm.setCartDiscount(it); discountDialog = false
        }
    }
}

@Composable
private fun DiscountDialog(current: Int, onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleva na celý nákup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 5, 10, 15, 20, 50).chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { p ->
                            FilterChip(selected = current == p, onClick = { onSet(p) }, label = { Text(if (p == 0) "Bez slevy" else "$p %") })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zavřít") } },
    )
}
