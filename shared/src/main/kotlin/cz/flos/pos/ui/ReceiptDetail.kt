package cz.flos.pos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.flos.core.Money
import cz.flos.pos.data.EetRecordEntity
import cz.flos.pos.data.EetStatus
import cz.flos.pos.data.SaleStatus
import cz.flos.pos.data.SaleWithItems
import cz.flos.pos.data.TransferStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Everything about one receipt: when, who, how it was paid, the items, EET confirmation, linked refunds,
 * the printed look, and every action (copy, refund, storno, tip, payment arrived, sell again).
 * Arrows step to the older / newer receipt in the current list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReceiptDetailDialog(
    vm: PosViewModel,
    sw: SaleWithItems,
    eet: List<EetRecordEntity>,
    onDismiss: () -> Unit,
    onEditOrder: () -> Unit,
    onRepeat: () -> Unit,
    onOpenOther: (SaleWithItems) -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    val s = sw.sale
    val settings by vm.settings.collectAsState()
    var related by remember(sw) { mutableStateOf<Pair<SaleWithItems?, List<SaleWithItems>>>(null to emptyList()) }
    var customer by remember(sw) { mutableStateOf<String?>(null) }
    var records by remember(sw) { mutableStateOf(eet) }
    var preview by remember(sw) { mutableStateOf<String?>(null) }
    var showPreview by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    var editTip by remember { mutableStateOf(false) }
    var confirmTransfer by remember { mutableStateOf(false) }
    var refunding by remember { mutableStateOf(false) }
    LaunchedEffect(sw) {
        related = vm.relatedReceipts(sw)
        customer = vm.customerName(s.customerId)
        records = vm.eetRecordsFor(sw).ifEmpty { eet }
        preview = vm.receiptPreview(sw)
    }
    val dateFmt = remember { DateTimeFormatter.ofPattern("d. M. yyyy  HH:mm:ss") }
    fun date(ms: Long) = dateFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(0.96f).fillMaxHeight(0.94f),
        ) {
            Column {
                // Header: older ◀ number ▶ newer, close.
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onPrevious?.invoke() }, enabled = onPrevious != null) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, tr("Starší účtenka"))
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            when {
                                s.isOpen || s.saleStatus == SaleStatus.VOID -> tr("Objednávka č. %d", s.orderNumber ?: 0)
                                s.isRefund -> tr("Vratka %s", s.number)
                                else -> tr("Účtenka %s", s.number)
                            },
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        )
                        Text(date(s.createdAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onNext?.invoke() }, enabled = onNext != null) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, tr("Novější účtenka"))
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, tr("Zavřít")) }
                }

                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatusBanner(sw)

                    // Total and payment.
                    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tr("Celkem"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Text(Money.format(if (s.isPaid) s.received else s.total), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            }
                            if (s.isPaid && s.received != s.total) Info(tr("Před zaokrouhlením"), Money.format(s.total))
                            s.paymentMethod?.let { Info(tr("Platba"), tr(it.label) + (if (s.isForeign) " · " + Money.format(s.payAmount ?: 0, "EUR") else "")) }
                            s.cashGiven?.let { given ->
                                Info(tr("Přijato hotově"), Money.format(given))
                                if (given > s.received) Info(tr("Vráceno"), Money.format(given - s.received))
                            }
                            if (s.tip > 0) Info(tr("Spropitné"), Money.format(s.tip))
                            s.transferStatus?.let { t ->
                                Info(tr("Převod"), if (t == TransferStatus.UNDERPAID) tr("chybí %s", Money.format(s.transferMissing)) else tr(t.label))
                            }
                            s.variableSymbol?.let { Info(tr("Variabilní symbol"), it) }
                        }
                    }

                    // Who and where.
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (s.cashier.isNotBlank()) Info(tr("Obsluha"), s.cashier)
                        s.tableName?.let { Info(tr("Stůl"), it) }
                        customer?.let { Info(tr("Zákazník"), it) }
                        if (s.note.isNotBlank()) Info(tr("Poznámka"), s.note)
                        if (s.discountPercent > 0) Info(tr("Sleva na účet"), "${s.discountPercent} %")
                    }

                    // Items.
                    Text(tr("Položky"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    val cart = sw.toCart()
                    cart.items.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${cz.flos.core.Qty.format(item.quantity)}×", fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name)
                                Text(
                                    Money.format(item.unitPrice) + " · " + tr("DPH %d %%", item.vatRate.percent) +
                                        if (item.discountPercent > 0) " · −${item.discountPercent} %" else "",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(Money.format(item.total), fontWeight = FontWeight.SemiBold)
                        }
                    }
                    HorizontalDivider()
                    if (settings.vatPayer) cart.vatBreakdown().forEach { v ->
                        Info(tr("Základ %d %%", v.rate.percent), Money.format(v.base) + " · " + tr("DPH %s", Money.format(v.vat)))
                    }

                    // EET.
                    records.firstOrNull { !it.storno }?.let { r -> EetInfo(r) }
                    records.firstOrNull { it.storno }?.let { r -> EetInfo(r, storno = true) }

                    // Linked receipts.
                    val (original, refunds) = related
                    original?.let { o ->
                        LinkRow(tr("Vratka k účtence %s", o.sale.number), Money.format(o.sale.received)) { onOpenOther(o) }
                    }
                    refunds.forEach { r ->
                        LinkRow(tr("Vratka %s", r.sale.number) + " · " + date(r.sale.createdAt), Money.format(r.sale.received)) { onOpenOther(r) }
                    }

                    // How it looks printed.
                    TextButton(onClick = { showPreview = !showPreview }) {
                        Text(if (showPreview) tr("Skrýt vytištěnou podobu") else tr("Ukázat vytištěnou podobu"))
                    }
                    if (showPreview) preview?.let { text ->
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                            Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                modifier = Modifier.padding(12.dp).horizontalScroll(rememberScrollState()))
                        }
                    }
                }

                // Actions.
                HorizontalDivider()
                FlowRow(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val paidOk = s.isPaid && !s.cancelled
                    if (s.awaitsTransfer) OutlinedButton(onClick = { confirmTransfer = true }) { Text(tr("Platba přišla")) }
                    if (paidOk && !s.isRefund) OutlinedButton(onClick = { editTip = true }) { Text(tr("Spropitné")) }
                    if (paidOk) OutlinedButton(onClick = { confirmCancel = true }) {
                        Icon(Icons.Rounded.Block, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Storno"))
                    }
                    if (paidOk && !s.isRefund) OutlinedButton(onClick = { refunding = true }) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Vrátit položky"))
                    }
                    if (s.isPaid && !s.isRefund) FilledTonalButton(onClick = onRepeat) {
                        Icon(Icons.Rounded.Replay, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Prodat znovu"))
                    }
                    if (s.isOpen) Button(onClick = onEditOrder) {
                        Icon(Icons.Rounded.ShoppingCart, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Otevřít v košíku"))
                    }
                    if (s.isPaid) Button(onClick = { vm.reprint(sw) }, enabled = vm.printer.isConfigured(settings)) {
                        Icon(Icons.Rounded.Print, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Tisknout kopii"))
                    }
                }
            }
        }
    }

    if (confirmTransfer) {
        val due = s.payAmount?.takeIf { s.isForeign } ?: s.received
        val cur = if (s.isForeign) "EUR" else "Kč"
        AmountDialog(
            title = tr("Platba za účtenku %s", s.number),
            label = if (s.isForeign) tr("Přišlo na účet (EUR)") else tr("Přišlo na účet (Kč)"),
            initial = due,
            hint = { amount -> if (amount > due) tr("Spropitné: %s", Money.format(amount - due, cur))
                else if (amount < due) tr("Chybí: %s", Money.format(due - amount, cur)) else "" },
            onDismiss = { confirmTransfer = false },
        ) { vm.confirmTransfer(s, it); confirmTransfer = false }
    }
    if (editTip) AmountDialog(
        title = tr("Spropitné k účtence %s", s.number),
        label = tr("Spropitné (Kč)"),
        initial = s.tip,
        hint = { tipsHint(settings.tipsOwner) },
        onDismiss = { editTip = false },
    ) { vm.setTip(s, it); editTip = false }
    if (refunding) RefundDialog(vm, sw, onDismiss = { refunding = false })
    if (confirmCancel) {
        var reason by remember { mutableStateOf("") }
        val needReason = vm.settings.collectAsState().value.on(cz.flos.pos.data.AdvFeature.STORNO_REASON)
        AlertDialog(
        onDismissRequest = { confirmCancel = false },
        title = { Text(tr("Stornovat účtenku %s?", s.number)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Účtenka zůstane v historii označená jako storno a nezapočítá se do tržby."))
                if (needReason) OutlinedTextField(reason, { reason = it }, label = { Text(tr("Důvod (povinný)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = { vm.cancelSale(sw, reason); confirmCancel = false }, enabled = !needReason || reason.isNotBlank()) { Text(tr("Stornovat")) } },
        dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text(tr("Zpět")) } },
        )
    }
}

@Composable
private fun StatusBanner(sw: SaleWithItems) {
    val s = sw.sale
    val text = when {
        s.isOpen -> tr("Nezaplacená objednávka")
        s.saleStatus == SaleStatus.VOID -> tr("Zrušená objednávka")
        s.cancelled -> tr("STORNO") + (s.stornoNumber?.let { " · " + tr("doklad %s", it) } ?: "")
        s.isRefund -> tr("Vratka – peníze vráceny zákazníkovi")
        else -> return
    }
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(150.dp))
        Text(value, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EetInfo(r: EetRecordEntity, storno: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(if (storno) tr("EET – storno") else tr("EET"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Info(tr("Stav"), when (r.status) {
            EetStatus.CONFIRMED -> tr("potvrzeno")
            EetStatus.PENDING -> tr("čeká na odeslání (pokus %d)", r.attempts)
            EetStatus.REJECTED -> tr("odmítnuto")
            EetStatus.VERIFIED -> tr("ověřovací režim")
        } + if (r.test) " · " + tr("testovací") else "")
        r.pok?.let { Info(tr("Kód potvrzení"), it) }
        Info(tr("Pokladna / provozovna"), "${r.idPokl} / ${r.idJednotky}")
        r.lastError?.takeIf { r.status != EetStatus.CONFIRMED }?.let { Info(tr("Chyba"), it) }
    }
}

@Composable
private fun LinkRow(label: String, amount: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Text(amount, fontWeight = FontWeight.Bold)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
        }
    }
}
