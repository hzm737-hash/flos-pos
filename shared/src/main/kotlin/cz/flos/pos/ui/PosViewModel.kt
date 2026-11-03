package cz.flos.pos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.flos.core.Align
import cz.flos.core.Cart
import cz.flos.core.DailyReport
import cz.flos.core.DocumentKind
import cz.flos.core.CartItem
import cz.flos.core.VatRate
import cz.flos.core.EetReceiptInfo
import cz.flos.core.EscPosEncoder
import cz.flos.core.Money
import cz.flos.core.PaymentMethod
import cz.flos.core.QrPlatba
import cz.flos.core.Receipt
import cz.flos.core.ReceiptBlock
import cz.flos.core.ReceiptLayout
import cz.flos.pos.FlosContainer
import cz.flos.pos.data.AppSettings
import cz.flos.pos.data.CategoryEntity
import cz.flos.pos.data.EetRecordEntity
import cz.flos.pos.data.EetStatus
import cz.flos.pos.data.ProductEntity
import cz.flos.pos.data.SaleEntity
import cz.flos.pos.data.SaleItemEntity
import cz.flos.pos.data.SaleStatus
import cz.flos.pos.data.SaleWithItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class PosViewModel(private val flos: FlosContainer) : ViewModel() {
    val platform = flos.platform
    private val catalog = flos.database.catalog()
    private val salesDao = flos.database.sales()
    private val eetDao = flos.database.eet()
    val printer = flos.printer
    val eet = flos.eet

    val settings: StateFlow<AppSettings> = flos.settings.state
    val categories = catalog.categories().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val products = catalog.products().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val recentSales = salesDao.recent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** EET records that are still waiting to be sent or were rejected. */
    val eetOpen = eetDao.open().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val eetRecent = eetDao.recent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Orders waiting for payment (delivery, takeaway, a table). */
    val openOrders = salesDao.openOrders().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _cart = MutableStateFlow(Cart())
    val cart: StateFlow<Cart> = _cart.asStateFlow()

    private val _editingOrder = MutableStateFlow<SaleEntity?>(null)
    /** The open order currently loaded into the cart, or null for a new sale. */
    val editingOrder: StateFlow<SaleEntity?> = _editingOrder.asStateFlow()

    /** History of a period (epoch millis, end exclusive); open orders are always included. */
    fun history(from: Long, to: Long): Flow<List<SaleWithItems>> = salesDao.history(from, to)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private val _busy = MutableStateFlow<String?>(null)
    /** Text of a running long operation (e.g. sending to EET), shown in the UI. */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    init {
        viewModelScope.launch { if (catalog.productCount() == 0) seedDemoData() }
    }

    // ---- Cart ----
    fun addToCart(p: ProductEntity) = _cart.update { it.add(p.id, p.name, p.price, p.vatRate) }
    fun setQuantity(index: Int, qty: Int) = _cart.update { it.setQuantity(index, qty) }
    fun setLineDiscount(index: Int, percent: Int) = _cart.update { it.setLineDiscount(index, percent) }
    fun setCartDiscount(percent: Int) = _cart.update { it.setDiscount(percent) }
    fun clearCart() {
        _cart.update { it.clear() }
        _editingOrder.value = null
    }

    /** The QR payment for an amount, or null when no valid bank account is set. */
    fun qrFor(amount: Long, variableSymbol: String?, message: String): QrPlatba? {
        val iban = settings.value.iban ?: return null
        return QrPlatba(iban = iban, amount = amount, variableSymbol = variableSymbol, message = message)
    }

    /** Variable symbol derived from the receipt number: "2027-000123" → "2027000123". */
    private fun variableSymbolOf(number: String) = number.filter(Char::isDigit).takeLast(10)

    /**
     * QR code to show on screen before the customer pays. It uses the number the receipt is
     * about to get, so the variable symbol matches the printed receipt.
     */
    fun preparePaymentQr(amount: Long = _cart.value.total): QrPlatba? {
        val s = settings.value
        val number = flos.settings.peekReceiptNumber(LocalDate.now().year)
        return qrFor(amount, variableSymbolOf(number), "${s.businessName} uctenka $number")
    }

    private fun nowMillis(now: LocalDateTime) = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun itemsOf(cart: Cart) = cart.items.map {
        SaleItemEntity(saleId = 0, productId = it.productId, name = it.name, unitPrice = it.unitPrice,
            vatPercent = it.vatRate.percent, quantity = it.quantity, discountPercent = it.discountPercent)
    }

    /** Pays the cart. When an open order is loaded into the cart, that order is paid and closed. */
    fun checkout(payment: PaymentMethod, cashGiven: Long?, onDone: () -> Unit) {
        val cart = _cart.value
        if (cart.isEmpty()) return
        val order = _editingOrder.value
        _cart.value = Cart()
        _editingOrder.value = null
        onDone()
        viewModelScope.launch { pay(order, cart, payment, cashGiven) }
    }

    /** Pays an open order straight from the order list. */
    fun payOrder(sw: SaleWithItems, payment: PaymentMethod, cashGiven: Long?) = viewModelScope.launch {
        pay(sw.sale, sw.toCart(), payment, cashGiven)
    }

    /**
     * Creates the receipt: only now the sale gets its receipt number, the time of payment and,
     * when the payment type is covered by EET, its EET record. Nothing is sent to the tax
     * administration for an order that is never paid.
     */
    private suspend fun pay(order: SaleEntity?, cart: Cart, payment: PaymentMethod, cashGiven: Long?) {
        val s = settings.value
        val now = LocalDateTime.now()
        val millis = nowMillis(now)
        val number = flos.settings.nextReceiptNumber(now.year)
        val vs = variableSymbolOf(number)
        val qr = if (payment == PaymentMethod.QR_TRANSFER) qrFor(cart.total, vs, "${s.businessName} uctenka $number") else null
        val received = if (payment == PaymentMethod.CASH) Money.roundToCrowns(cart.total) else cart.total
        val sale = (order ?: SaleEntity(number = number, createdAt = millis, total = cart.total, payment = payment.name)).copy(
            number = number, createdAt = millis, total = cart.total, payment = payment.name, cashGiven = cashGiven,
            cashier = s.cashier, discountPercent = cart.discountPercent,
            variableSymbol = if (payment == PaymentMethod.QR_TRANSFER || payment == PaymentMethod.BANK_TRANSFER) vs else null,
            received = received, status = SaleStatus.PAID.name,
        )
        // The sale and its EET record are stored before anything is sent or printed.
        val saleId = if (order == null) salesDao.insert(sale, itemsOf(cart)) else { salesDao.replace(sale, itemsOf(cart)); sale.id }
        val record = if (payment.eet && received != 0L) eet.createRecord(saleId, number, millis, received, storno = false) else null

        val sent = record?.let { sendToEet(it) }
        val receipt = Receipt(number, now, s.cashier, cart, payment, cashGiven, qr, eet = sent?.let(::eetInfo), note = sale.note)
        if (s.autoPrint) printBlocks(ReceiptLayout(s.paperWidth.chars).layout(s.business, receipt), tr("Účtenka %s vytištěna", number))
        else _messages.tryEmit(tr("Účtenka %s uložena", number))
    }

    /** Prints the cart as a check print (předběžný účet) before payment. Nothing is stored or sent to EET. */
    fun printPreBill() {
        val cart = _cart.value
        if (cart.isEmpty()) return
        val s = settings.value
        val doc = Receipt("", LocalDateTime.now(), s.cashier, cart, PaymentMethod.CARD, note = _editingOrder.value?.note.orEmpty(),
            kind = DocumentKind.PRE_BILL)
        printBlocks(ReceiptLayout(s.paperWidth.chars).layout(s.business, doc), tr("Předběžný účet vytištěn"))
    }

    /**
     * Saves the cart as an open order (for a delivery, takeaway or table) without payment and
     * optionally prints an order ticket. It can later be paid by any method, changed or cancelled;
     * EET only sees it once it is paid.
     */
    fun saveOpenOrder(note: String, print: Boolean) {
        val cart = _cart.value
        if (cart.isEmpty()) return
        val existing = _editingOrder.value
        _cart.value = Cart()
        _editingOrder.value = null
        viewModelScope.launch {
            val s = settings.value
            val now = LocalDateTime.now()
            val sale = if (existing != null) {
                existing.copy(total = cart.total, received = cart.total, discountPercent = cart.discountPercent, note = note.trim())
                    .also { salesDao.replace(it, itemsOf(cart)) }
            } else {
                val orderNo = flos.settings.nextOrderNumber()
                val millis = nowMillis(now)
                SaleEntity(
                    number = "O-$millis", createdAt = millis, openedAt = millis, total = cart.total, received = cart.total,
                    payment = "", cashier = s.cashier, discountPercent = cart.discountPercent,
                    status = SaleStatus.OPEN.name, orderNumber = orderNo, note = note.trim(),
                ).let { it.copy(id = salesDao.insert(it, itemsOf(cart))) }
            }
            _messages.tryEmit(tr("Objednávka č. %d uložena", sale.orderNumber ?: 0))
            if (print) printOrder(sale, cart)
        }
    }

    private fun printOrder(sale: SaleEntity, cart: Cart) {
        val s = settings.value
        val opened = LocalDateTime.ofInstant(Instant.ofEpochMilli(sale.openedAt ?: sale.createdAt), ZoneId.systemDefault())
        val doc = Receipt((sale.orderNumber ?: 0).toString(), opened, sale.cashier, cart, PaymentMethod.CARD, note = sale.note,
            kind = DocumentKind.ORDER)
        printBlocks(ReceiptLayout(s.paperWidth.chars).layout(s.business, doc), tr("Objednávka vytištěna"))
    }

    fun reprintOrder(sw: SaleWithItems) = printOrder(sw.sale, sw.toCart())

    /** Loads an open order into the cart so items can be added or removed. */
    fun editOrder(sw: SaleWithItems) {
        _cart.value = sw.toCart()
        _editingOrder.value = sw.sale
    }

    /** Cancels an unpaid order. It stays in history as cancelled; nothing is sent to EET. */
    fun cancelOrder(sw: SaleWithItems, reason: String) = viewModelScope.launch {
        val note = listOf(sw.sale.note, reason.trim().takeIf { it.isNotEmpty() }?.let { tr("Zrušeno: %s", it) })
            .filterNot { it.isNullOrBlank() }.joinToString(" · ")
        salesDao.update(sw.sale.copy(status = SaleStatus.VOID.name, note = note, createdAt = System.currentTimeMillis()))
        if (_editingOrder.value?.id == sw.sale.id) clearCart()
        _messages.tryEmit(tr("Objednávka č. %d zrušena", sw.sale.orderNumber ?: 0))
    }

    /** Sends one record now; on failure the background worker takes over. */
    private suspend fun sendToEet(r: EetRecordEntity): EetRecordEntity {
        _busy.value = tr("Odesílám tržbu do EET…")
        val after = try { eet.send(r.id) ?: r } finally { _busy.value = null }
        when (after.status) {
            EetStatus.PENDING -> {
                platform.scheduleEetRetry()
                _messages.tryEmit(tr("EET neodpověděla včas, tržba se odešle automaticky později."))
            }
            EetStatus.REJECTED -> _messages.tryEmit(tr("EET odmítla tržbu: %s", after.lastError))
            else -> {}
        }
        return after
    }

    private fun eetInfo(r: EetRecordEntity) = EetReceiptInfo(r.idJednotky, r.idPokl, r.pok, r.test)

    suspend fun receiptOf(sw: SaleWithItems, copy: Boolean = true): Receipt {
        val s = settings.value
        val created = LocalDateTime.ofInstant(Instant.ofEpochMilli(sw.sale.createdAt), ZoneId.systemDefault())
        val payment = sw.sale.paymentMethod ?: PaymentMethod.CASH
        val qr = if (payment == PaymentMethod.QR_TRANSFER) {
            qrFor(sw.sale.total, sw.sale.variableSymbol, "${s.businessName} uctenka ${sw.sale.number}")
        } else null
        val record = eetDao.forSale(sw.sale.id).firstOrNull { !it.storno }
        if (!sw.sale.isPaid) {
            return Receipt((sw.sale.orderNumber ?: 0).toString(), created, sw.sale.cashier, sw.toCart(), PaymentMethod.CARD,
                note = sw.sale.note, kind = DocumentKind.ORDER)
        }
        return Receipt(sw.sale.number, created, sw.sale.cashier, sw.toCart(), payment, sw.sale.cashGiven, qr, copy,
            eet = record?.let(::eetInfo), note = sw.sale.note)
    }

    suspend fun receiptPreview(sw: SaleWithItems): String {
        val s = settings.value
        val blocks = ReceiptLayout(s.paperWidth.chars).layout(s.business, receiptOf(sw))
        return ReceiptLayout.toPlainText(blocks, s.paperWidth.chars)
    }

    suspend fun eetRecordsFor(sw: SaleWithItems): List<EetRecordEntity> = eetDao.forSale(sw.sale.id)

    fun reprint(sw: SaleWithItems) = viewModelScope.launch {
        val s = settings.value
        printBlocks(ReceiptLayout(s.paperWidth.chars).layout(s.business, receiptOf(sw)), tr("Kopie vytištěna"))
    }

    /**
     * Cancels (storno) a sale. The original stays in history marked as cancelled; a storno
     * document with its own number and a negative amount is recorded in EET and printed.
     */
    fun cancelSale(sw: SaleWithItems) = viewModelScope.launch {
        val s = settings.value
        val now = LocalDateTime.now()
        val millis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val stornoNumber = flos.settings.nextReceiptNumber(now.year)
        salesDao.update(sw.sale.copy(cancelled = true, stornoNumber = stornoNumber))
        // A storno is recorded in EET only when the original sale was (not for invoices or sales before EET was switched on).
        val recorded = eetDao.forSale(sw.sale.id).any { !it.storno }
        val record = if (recorded) eet.createRecord(sw.sale.id, stornoNumber, millis, -sw.sale.received, storno = true) else null
        val sent = record?.let { sendToEet(it) }
        _messages.tryEmit(tr("Účtenka %s stornována dokladem %s", sw.sale.number, stornoNumber))
        if (s.autoPrint) {
            val doc = Receipt(stornoNumber, now, s.cashier, sw.toCart(), sw.sale.paymentMethod ?: PaymentMethod.CASH, null, null,
                eet = sent?.let(::eetInfo), stornoOf = sw.sale.number)
            printBlocks(ReceiptLayout(s.paperWidth.chars).layout(s.business, doc), tr("Storno doklad vytištěn"))
        }
    }

    // ---- EET ----
    fun retryEetNow() = viewModelScope.launch {
        _busy.value = tr("Odesílám čekající tržby do EET…")
        val left = try { eet.sendPending() } finally { _busy.value = null }
        _messages.tryEmit(if (left == 0) tr("Všechny tržby jsou odeslané") else tr("Stále čeká %d tržeb, zkusím to znovu automaticky", left))
        if (left > 0) platform.scheduleEetRetry()
    }

    fun requeueEet(r: EetRecordEntity) = viewModelScope.launch {
        eet.requeue(r)
        eet.send(r.id)
    }

    fun testEet() = viewModelScope.launch {
        _busy.value = tr("Ověřuji spojení s EET…")
        val text = try { eet.testConnection() } finally { _busy.value = null }
        _messages.tryEmit(text)
    }

    fun importCertificate(bytes: ByteArray, password: String) = viewModelScope.launch {
        val result = runCatching { eet.importCertificate(bytes, password) }
        result.onSuccess { cert ->
            updateSettings { it.copy(eetCertPassword = password, eetEic = it.eetEic.ifBlank { cert.eic ?: "" }) }
            _messages.tryEmit(tr("Certifikát nahrán (%s, platí do %s)", cert.eic, java.text.DateFormat.getDateInstance().format(cert.validUntil)))
        }.onFailure { _messages.tryEmit(it.message ?: tr("Certifikát se nepodařilo nahrát")) }
    }

    // ---- Reports ----
    suspend fun dailyReport(date: LocalDate): DailyReport {
        val zone = ZoneId.systemDefault()
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return DailyReport.of(date, salesDao.between(from, to).map { it.toSummary() })
    }

    fun printReport(report: DailyReport) {
        val s = settings.value
        printBlocks(report.layout(s.business, s.paperWidth.chars), tr("Uzávěrka vytištěna"))
    }

    fun testPrint() {
        val s = settings.value
        val blocks = buildList {
            add(ReceiptBlock.Line(s.businessName, Align.CENTER, bold = true, large = true))
            add(ReceiptBlock.Line(tr("Zkušební tisk Flos"), Align.CENTER))
            add(ReceiptBlock.Line("Příliš žluťoučký kůň úpěl ďábelské ódy"))
            add(ReceiptBlock.Separator)
            qrFor(10000, "1", tr("Test Flos"))?.let { add(ReceiptBlock.Qr(it.toSpayd())) }
            add(ReceiptBlock.Feed(3))
        }
        printBlocks(blocks, tr("Zkušební tisk odeslán"))
    }

    private fun printBlocks(blocks: List<ReceiptBlock>, okMessage: String) {
        val s = settings.value
        viewModelScope.launch {
            val bytes = EscPosEncoder(s.paperWidth, s.charset).encode(blocks)
            runCatching { printer.print(s, bytes) }
                .onSuccess { _messages.tryEmit(okMessage) }
                .onFailure { _messages.tryEmit(tr("Tisk se nezdařil: %s", it.message)) }
        }
    }

    // ---- Catalogue ----
    fun saveProduct(p: ProductEntity) = viewModelScope.launch { catalog.upsertProduct(p) }
    fun deleteProduct(p: ProductEntity) = viewModelScope.launch { catalog.deactivateProduct(p.id) }
    fun saveCategory(c: CategoryEntity) = viewModelScope.launch { catalog.upsertCategory(c) }
    fun deleteCategory(c: CategoryEntity) = viewModelScope.launch {
        catalog.clearCategory(c.id)
        catalog.deleteCategory(c.id)
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) = flos.settings.update(transform)

    private suspend fun seedDemoData() {
        val drinks = catalog.upsertCategory(CategoryEntity(name = "Nápoje", color = 0xFF6C5CE7L, sortOrder = 0, icon = "cafe"))
        val food = catalog.upsertCategory(CategoryEntity(name = "Jídlo", color = 0xFFFF6B5AL, sortOrder = 1, icon = "meal"))
        val bakery = catalog.upsertCategory(CategoryEntity(name = "Pečivo", color = 0xFFF7B731L, sortOrder = 2, icon = "bakery"))
        listOf(
            ProductEntity(name = "Espresso", price = 5900, vatPercent = 12, categoryId = drinks, icon = "coffee"),
            ProductEntity(name = "Cappuccino", price = 7500, vatPercent = 12, categoryId = drinks, icon = "cafe"),
            ProductEntity(name = "Čaj", price = 5500, vatPercent = 12, categoryId = drinks, icon = "tea"),
            ProductEntity(name = "Pivo 0,5 l", price = 5900, vatPercent = 21, categoryId = drinks, icon = "beer"),
            ProductEntity(name = "Pizza Margherita", price = 18900, vatPercent = 12, categoryId = food, icon = "pizza"),
            ProductEntity(name = "Burger", price = 21900, vatPercent = 12, categoryId = food, icon = "burger"),
            ProductEntity(name = "Croissant", price = 4500, vatPercent = 12, categoryId = bakery, icon = "bakery"),
            ProductEntity(name = "Dort", price = 8900, vatPercent = 12, categoryId = bakery, icon = "cake"),
        ).forEach { catalog.upsertProduct(it) }
    }
}
