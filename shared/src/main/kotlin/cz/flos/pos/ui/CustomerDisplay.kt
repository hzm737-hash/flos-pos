package cz.flos.pos.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.flos.core.Money
import cz.flos.core.QrPlatba
import kotlinx.coroutines.delay

/**
 * What the customer sees on a second screen: the items as they are added, the total, the QR code to pay,
 * and a thank-you with the change after payment. Shared by the Windows second window and the Android
 * secondary display.
 */
@Composable
fun CustomerDisplay(vm: PosViewModel) {
    val settings by vm.settings.collectAsState()
    I18n.language = settings.language
    key(settings.language) {
        FlosTheme { CustomerDisplayContent(vm) }
    }
}

@Composable
private fun CustomerDisplayContent(vm: PosViewModel) {
    val settings by vm.settings.collectAsState()
    val cart by vm.cart.collectAsState()
    val customer by vm.customer.collectAsState()
    val qr by vm.displayQr.collectAsState()
    val paid by vm.lastPaid.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(paid) { now = System.currentTimeMillis(); delay(10_000); now = System.currentTimeMillis() }
    val thanks = paid?.takeIf { cart.isEmpty() && now - it.at < 10_000 }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            thanks != null -> Centered {
                Text(tr("Děkujeme za nákup"), fontSize = 44.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Text(tr("Zaplaceno %s", Money.format(thanks.amount)), fontSize = 28.sp)
                thanks.change?.let { Text(tr("Vráceno %s", Money.format(it)), fontSize = 28.sp, color = MaterialTheme.colorScheme.primary) }
            }
            cart.isEmpty() -> Centered {
                Text(settings.businessName.ifBlank { "Flos" }, fontSize = 48.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(tr("Dobrý den"), fontSize = 28.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> Row(Modifier.fillMaxSize().padding(32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    customer?.let { c ->
                        Text(
                            c.name + if (settings.loyaltyCrownsPerPoint > 0) " · " + tr("%d bodů", c.points) else "",
                            fontSize = 22.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(cart.items) { item ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${cz.flos.core.Qty.format(item.quantity)}×", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
                                Text(item.name, fontSize = 24.sp, modifier = Modifier.weight(1f))
                                Text(Money.format(item.total), fontSize = 24.sp)
                            }
                            HorizontalDivider()
                        }
                    }
                    if (cart.cartDiscount > 0) Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(tr("Sleva %d %%", cart.discountPercent), fontSize = 22.sp, modifier = Modifier.weight(1f))
                        Text(Money.format(-cart.cartDiscount), fontSize = 22.sp)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Celkem"), fontSize = 32.sp, modifier = Modifier.weight(1f))
                        Text(Money.format(cart.total), fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    }
                }
                qr?.let { code ->
                    Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.background(Color.White)) {
                            when (code) {
                                is QrPlatba -> QrImage(code, size = 320.dp)
                                is cz.flos.core.EpcQr -> QrImage(code, size = 320.dp)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(tr("Naskenujte kód v bankovní aplikaci"), fontSize = 20.sp, textAlign = TextAlign.Center)
                        if (code is cz.flos.core.EpcQr) Text(Money.format(code.amount, "EUR"), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        content()
    }
}
