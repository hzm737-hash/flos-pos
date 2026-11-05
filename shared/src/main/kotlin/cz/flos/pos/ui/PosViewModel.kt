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
import cz.flos.core.Qty
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import cz.flos.pos.data.CashDao
import cz.flos.pos.data.CashKind
import cz.flos.pos.data.CashMovementEntity
import cz.flos.pos.data.CashState
import cz.flos.pos.data.BankPaymentEntity
import cz.flos.pos.data.BankSource
import cz.flos.pos.data.TipsOwner
import cz.flos.pos.data.TransferStatus
import cz.flos.pos.bank.BankCheck
import kotlinx.coroutines.delay
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
    private val cashDao: CashDao = flos.database.cash()
    private val bankDao = flos.database.bank()
    private val customerDao = flos.database.customers()
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

    /** Cash that should be in the drawer since the last shift close. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val cashState: StateFlow<CashState> = cashDao.lastClose().flatMapLatest { since ->
        combine(cashDao.since(since), salesDao.cashReceivedSince(since), salesDao.cashRefundedSince(since)) { moves, sales, refunds ->
            CashState(
                since = since,
                float = moves.filter { it.cashKind == CashKind.FLOAT }.sumOf { it.amount },
                sales = sales,
                refunds = refunds,
                cashIn = moves.filter { it.cashKind == CashKind.IN }.sumOf { it.amount },
                cashOut = moves.filter { it.cashKind == CashKind.OUT }.sumOf { it.amount },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CashState(0, 0, 0, 0, 0, 0))

    val shiftCloses = cashDao.closes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _cart = MutableStateFlow(Cart())
    val cart: StateFlow<Cart> = _cart.asStateFlow()

    private val _editingOrder = MutableStateFlow<SaleEntity?>(null)
    /** The open order currently loaded into the cart, or null for a new sale. */
    val editingOrder: StateFlow<SaleEntity?> = _editingOrder.asStateFlow()

    /** History of a period (epoch millis, end exclusive); open orders are always included. */
    fun history(from: Long, to: Long): Flow<List<SaleWithItems>> = salesDao.history(from, to)

    /** Receipts matching [text] (number, amount, item, note, table, cashier, customer) in a period. */
    fun searchHistory(text: String, from: Long, to: Long): Flow<List<SaleWithItems>> {
        val t = text.trim()
        val amount = if (t.any(Char::isDigit) && t.all { it.isDigit() || it == ',' || it == '.' || it == ' ' }) Money.parse(t) else null
        return salesDao.search(t, amount, from, to)
    }

    /** The receipt a refund belongs to, and the refunds made from this receipt. */
    suspend fun relatedReceipts(sw: SaleWithItems): Pair<SaleWithItems?, List<SaleWithItems>> =
        (sw.sale.refundOf?.let { salesDao.get(it) }) to salesDao.refundsOf(sw.sale.id)

    suspend fun customerName(id: Long?): String? = id?.let { customerDao.get(it)?.name }

    /** Puts the items of an old receipt into the cart again (the same order once more). */
    fun repeatSale(sw: SaleWithItems) {
        clearCart()
        _cart.value = sw.toCart().let { c -> c.copy(items = c.items.filter { it.quantity > 0 }) }
        sw.sale.customerId?.let { id -> viewModelScope.launch { _customer.value = customerDao.get(id) } }
        _messages.tryEmit(tr("Položky účtenky %s jsou v košíku", sw.sale.number))
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private val _busy = MutableStateFlow<String?>(null)
    /** Text of a running long operation (e.g. sending to EET), shown in the UI. */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    // ---- Bank payments ----
    val unmatchedPayments = bankDao.unmatched().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val waitingTransfers = bankDao.waitingSalesFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val bankPayments = bankDao.recent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Result of the last bank check, shown in settings and on the bank dialog. */
    private val _bankState = MutableStateFlow<String?>(null)
    val bankState: StateFlow<String?> = _bankState.asStateFlow()

    init {
        viewModelScope.launch { if (catalog.productCount() == 0) seedDemoData() }
        // Checks the bank every minute and syncs with the back office every five minutes while the till is open.
        viewModelScope.launch {
            var minute = 0
            while (true) {
                if (settings.value.bankSource != BankSource.OFF) checkBank(silent = true)
                if (settings.value.paired && minute % 5 == 0) syncBackOffice(silent = true)
                minute++
                delay(60_000)
            }
        }
    }

    // ---- Payers from abroad (SEPA QR code in euro) ----
    private val _foreignTransfer = MutableStateFlow(false)
    /** Set by the payment dialog: the next QR payment gets a SEPA QR code in euro. */
    val foreignTransfer: StateFlow<Boolean> = _foreignTransfer.asStateFlow()
    fun setForeignTransfer(on: Boolean) { _foreignTransfer.value = on }

    private var cnbRate: Pair<LocalDate, Double>? = null

    /** CZK per EUR: the owner's own rate when set, otherwise the ČNB rate, fetched once a day. */
    suspend fun eurRate(): Double? {
        settings.value.eurRateOverride?.let { return it }
        cnbRate?.takeIf { it.first == LocalDate.now() }?.let { return it.second }
        val fresh = runCatching { withContext(Dispatchers.IO) { cz.flos.core.CnbRates.fetch() }.of("EUR") }.getOrNull()
        if (fresh != null) cnbRate = LocalDate.now() to fresh
        return fresh ?: cnbRate?.second
    }

    /** SEPA QR code for [amount] in crowns, or null when there is no account or no exchange rate. */
    suspend fun prepareForeignQr(amount: Long = _cart.value.total): Pair<cz.flos.core.EpcQr, Double>? {
        val s = settings.value
        val iban = s.eurIban ?: return null
        val rate = eurRate() ?: return null
        val number = flos.settings.peekReceiptNumber(LocalDate.now().year)
        return epcFor(s, iban, amount, rate, variableSymbolOf(number), number) to rate
    }

    private fun epcFor(s: AppSettings, iban: String, amount: Long, rate: Double, vs: String, number: String) = cz.flos.core.EpcQr(
        name = s.businessName, iban = iban, amount = cz.flos.core.EpcQr.eurFor(amount, rate),
        // Czech banks read "/VS/…" in a foreign payment's message into the variable symbol.
        text = "/VS/$vs Uctenka $number", bic = cz.flos.core.EpcQr.bicForIban(iban),
    )

    fun checkBankNow() = viewModelScope.launch { checkBank(silent = false) }

    /** A statement file (GPC/ABO or CAMT.053) downloaded from any bank's internet banking. */
    fun importStatement(bytes: ByteArray) = viewModelScope.launch {
        _busy.value = tr("Načítám výpis…")
        try {
            runCatching { flos.bank.importStatement(bytes) }
                .onSuccess { r ->
                    _bankState.value = tr("Výpis načten: %d nových příchozích plateb", r.newPayments)
                    _messages.tryEmit(_bankState.value!!)
                    report(r, silent = true)
                }
                .onFailure { _messages.tryEmit(tr("Výpis nelze načíst: %s", it.message ?: it.javaClass.simpleName)) }
        } finally { _busy.value = null }
    }

    private suspend fun checkBank(silent: Boolean) {
        val time = java.time.LocalTime.now().withNano(0).withSecond(0)
        runCatching { flos.bank.check() }
            .onSuccess { r ->
                _bankState.value = tr("Naposledy zkontrolováno %s: %d nových plateb", time.toString(), r.newPayments)
                report(r, silent)
            }
            .onFailure {
                _bankState.value = tr("Kontrola banky selhala (%s): %s", time.toString(), it.message ?: it.javaClass.simpleName)
                if (!silent) _messages.tryEmit(_bankState.value!!)
            }
    }

    private suspend fun report(r: BankCheck, silent: Boolean) {
        r.tips.forEach { (sale, tip) -> recordTipInEet(sale, tip) }
        r.confirmed.forEach { sale ->
            _messages.tryEmit(if (sale.tip > 0) tr("Platba za účtenku %s přišla, spropitné %s", sale.number, Money.format(sale.tip))
                else tr("Platba za účtenku %s přišla", sale.number))
        }
        r.underpaid.forEach { _messages.tryEmit(tr("Účtenka %s: přišlo jen %s, chybí %s", it.number, Money.format(it.bankPaid), Money.format(it.transferMissing))) }
        if (r.unmatched > 0) _messages.tryEmit(tr("%d plateb nelze přiřadit, zkontrolujte je v Historii → Platby z banky", r.unmatched))
        else if (!silent && r.newPayments == 0) _messages.tryEmit(tr("Žádné nové platby"))
    }

    /**
     * When the business keeps the tips they are its takings and go to EET (outside the VAT base).
     * Tips that belong to the staff are not recorded in EET.
     */
    private suspend fun recordTipInEet(sale: SaleEntity, tip: Long) {
        if (tip <= 0 || settings.value.tipsOwner != TipsOwner.BUSINESS || sale.paymentMethod?.eet != true) return
        val now = LocalDateTime.now()
        val number = flos.settings.nextReceiptNumber(now.year)
        eet.createRecord(sale.id, number, nowMillis(now), tip, storno = false)?.let { sendToEet(it) }
    }

    fun assignPayment(p: BankPaymentEntity, sale: SaleEntity) = viewModelScope.launch {
        val (updated, tip) = flos.bank.assign(p, sale)
        recordTipInEet(updated, tip)
        _messages.tryEmit(if (updated.transferStatus == TransferStatus.CONFIRMED) tr("Platba přiřazena k účtence %s", updated.number)
            else tr("Účtenka %s: přišlo jen %s, chybí %s", updated.number, Money.format(updated.bankPaid), Money.format(updated.transferMissing)))
    }

    fun ignorePayment(p: BankPaymentEntity) = viewModelScope.launch { flos.bank.ignore(p) }

    /** The money is seen in internet banking: confirm by hand; [amount] above the total is the tip. */
    fun confirmTransfer(sale: SaleEntity, amount: Long) = viewModelScope.launch {
        val (updated, tip) = flos.bank.confirmManually(sale, amount)
        recordTipInEet(updated, tip)
        _messages.tryEmit(tr("Platba za účtenku %s potvrzena", updated.number))
    }

    /** Sets the tip of a paid sale (e.g. a card or cash tip given after the receipt was printed). */
    fun setTip(sale: SaleEntity, tip: Long) = viewModelScope.launch {
        if (tip < 0 || !sale.isPaid || sale.cancelled) return@launch
        salesDao.update(sale.copy(tip = tip))
        recordTipInEet(sale, tip - sale.tip)
        _messages.tryEmit(tr("Spropitné uloženo: %s", Money.format(tip)))
    }

    fun testBank(s: AppSettings) = viewModelScope.launch {
        _busy.value = tr("Ověřuji spojení s bankou…")
        val text = try { runCatching { flos.bank.test(s) }.getOrElse { tr("Chyba: %s", it.message ?: it.javaClass.simpleName) } } finally { _busy.value = null }
        _bankState.value = text
        _messages.tryEmit(text)
    }

    /** Tips and revenue of the current month, for the tax-free tips limit (7 % of monthly catering revenue). */
    suspend fun monthTips(month: java.time.YearMonth = java.time.YearMonth.now()): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return bankDao.tipsBetween(from, to) to bankDao.revenueBetween(from, to)
    }

    // ---- Cart ----
    private val _halfPortion = MutableStateFlow(false)
    /** "½ porce" switched on: the next product tapped is added as a half portion (0,5). */
    val halfPortion: StateFlow<Boolean> = _halfPortion.asStateFlow()
    fun toggleHalfPortion() { _halfPortion.value = !_halfPortion.value }

    /** Quantity for the product being added: 0,5 once after "½ porce", otherwise 1. */
    private fun nextQty(): Double = if (_halfPortion.value) { _halfPortion.value = false; 0.5 } else 1.0

    fun addToCart(p: ProductEntity) { val q = nextQty(); _cart.update { it.add(p.id, p.name, p.price, p.vatRate, q) } }

    /** Adds a product with the variants and extras chosen for it ("Pizza (Velká, Sýr)"). */
    fun addToCart(p: ProductEntity, name: String, price: Long) { val q = nextQty(); _cart.update { it.add(p.id, name, price, p.vatRate, q) } }

    // ---- Customers and loyalty ----
    val customers = customerDao.all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _customer = MutableStateFlow<cz.flos.pos.data.CustomerEntity?>(null)
    /** Customer chosen for the cart; gets loyalty points and their discount. */
    val customer: StateFlow<cz.flos.pos.data.CustomerEntity?> = _customer.asStateFlow()

    fun selectCustomer(c: cz.flos.pos.data.CustomerEntity?) {
        _customer.value = c
        if (c != null && c.discountPercent > 0) _cart.update { it.setDiscount(c.discountPercent) }
    }

    /** Saves a customer; [select] also picks them for the cart (a new customer made at the till). */
    fun saveCustomer(c: cz.flos.pos.data.CustomerEntity, select: Boolean = false) = viewModelScope.launch {
        val rowId = customerDao.upsert(if (c.createdAt == 0L) c.copy(createdAt = System.currentTimeMillis()) else c)
        val id = if (c.id != 0L) c.id else rowId
        if (select || (_customer.value?.id == id && id != 0L)) customerDao.get(id)?.let { selectCustomer(it) }
        _messages.tryEmit(tr("Zákazník %s uložen", c.name))
    }

    fun deleteCustomer(c: cz.flos.pos.data.CustomerEntity) = viewModelScope.launch {
        customerDao.delete(c.id)
        if (_customer.value?.id == c.id) _customer.value = null
    }

    suspend fun customerTotals(c: cz.flos.pos.data.CustomerEntity) = customerDao.totals(c.id)

    /** Finds a customer by the loyalty card code (scanned or typed). */
    suspend fun customerByCard(code: String) = customerDao.byCard(code.trim())

    /** Points for a paid amount (in haléře): one point per [AppSettings.loyaltyCrownsPerPoint] crowns. */
    private fun pointsFor(received: Long): Long {
        val per = settings.value.loyaltyCrownsPerPoint
        return if (per <= 0 || received <= 0) 0 else received / (per * 100L)
    }

    // ---- Back office ----
    private val _syncState = MutableStateFlow<String?>(null)
    val syncState: StateFlow<String?> = _syncState.asStateFlow()

    fun pairBackOffice(url: String, code: String) = viewModelScope.launch {
        _busy.value = tr("Páruji s back office…")
        try {
            runCatching { flos.backOffice.pair(url, code) }
                .onSuccess { name ->
                    _messages.tryEmit(tr("Pokladna spárována jako %s", name))
                    syncBackOffice(silent = false)
                }
                .onFailure { _messages.tryEmit(tr("Párování se nezdařilo: %s", it.message ?: it.javaClass.simpleName)) }
        } finally { _busy.value = null }
    }

    fun unpairBackOffice() {
        flos.backOffice.unpair()
        _syncState.value = null
        _messages.tryEmit(tr("Pokladna odpojena od back office"))
    }

    fun syncNow() = viewModelScope.launch { syncBackOffice(silent = false) }

    private suspend fun syncBackOffice(silent: Boolean) {
        val time = java.time.LocalTime.now().withNano(0).withSecond(0)
        runCatching { flos.backOffice.sync() }
            .onSuccess { r ->
                _syncState.value = tr("Synchronizováno %s: %d změn katalogu, odesláno %d účtenek", time.toString(), r.productsChanged, r.salesSent)
                if (!silent) _messages.tryEmit(_syncState.value!!)
            }
            .onFailure {
                _syncState.value = tr("Synchronizace selhala (%s): %s", time.toString(), it.message ?: it.javaClass.simpleName)
                if (!silent) _messages.tryEmit(_syncState.value!!)
            }
    }

    /** Switches the cashier to the staff member with this PIN (checked offline). Returns false for a wrong PIN. */
    suspend fun loginWithPin(pin: String): Boolean {
        val staff = flos.backOffice.staffForPin(pin) ?: return false
        updateSettings { it.copy(cashier = staff.name) }
        _messages.tryEmit(tr("Přihlášen(a): %s", staff.name))
        return true
    }

    /** Clocks in or out in the back office attendance. */
    fun clockInOut(pin: String) = viewModelScope.launch {
        runCatching { flos.backOffice.clock(pin) }
            .onSuccess { (name, action) -> _messages.tryEmit(if (action == "IN") tr("%s: příchod zapsán", name) else tr("%s: odchod zapsán", name)) }
            .onFailure { _messages.tryEmit(tr("Docházku nelze zapsat: %s", it.message ?: it.javaClass.simpleName)) }
    }

    // ---- Customer display ----
    data class LastPaid(val at: Long, val amount: Long, val change: Long?)
    private val _lastPaid = MutableStateFlow<LastPaid?>(null)
    /** The last payment, for the thank-you on the customer display. */
    val lastPaid: StateFlow<LastPaid?> = _lastPaid.asStateFlow()

    private val _displayQr = MutableStateFlow<Any?>(null)
    /** QR code shown to the customer while the payment dialog shows it ([QrPlatba] or [cz.flos.core.EpcQr]). */
    val displayQr: StateFlow<Any?> = _displayQr.asStateFlow()
    fun showOnDisplay(qr: Any?) { _displayQr.value = qr }

    // ---- Kitchen ----
    /** Marks an open order as ready in the kitchen (or back to preparing). */
    fun kitchenReady(sw: SaleWithItems, ready: Boolean) = viewModelScope.launch {
        salesDao.update(sw.sale.copy(kitchenReadyAt = if (ready) System.currentTimeMillis() else null))
    }

    // ---- Tables ----
    private val _pendingTable = MutableStateFlow<String?>(null)
    /** Table chosen on the table map for a new order that has not been saved yet. */
    val pendingTable: StateFlow<String?> = _pendingTable.asStateFlow()

    /** Starts a new order for a free table: an empty cart that is saved or paid with that table. */
    fun startTable(name: String) {
        _cart.value = Cart()
        _editingOrder.value = null
        _pendingTable.value = name
    }

    /** Table of the cart: the loaded order's table or the one just picked on the map. */
    val currentTable: String? get() = _editingOrder.value?.tableName ?: _pendingTable.value
    fun setQuantity(index: Int, qty: Double) = _cart.update { it.setQuantity(index, qty) }
    fun setLineDiscount(index: Int, percent: Int) = _cart.update { it.setLineDiscount(index, percent) }
    fun setCartDiscount(percent: Int) = _cart.update { it.setDiscount(percent) }
    fun clearCart() {
        _cart.update { it.clear() }
        _editingOrder.value = null
        _pendingTable.value = null
        _customer.value = null
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
        val table = currentTable
        val customer = _customer.value
        val foreign = _foreignTransfer.value && payment == PaymentMethod.QR_TRANSFER
        _foreignTransfer.value = false
        _cart.value = Cart()
        _editingOrder.value = null
        _pendingTable.value = null
        _customer.value = null
        onDone()
        viewModelScope.launch { pay(order, cart, payment, cashGiven, foreign, table, customer) }
    }

    /**
     * Split bill: pays only the chosen quantities of the cart lines ([selected]: line index →
     * quantity) as a receipt of its own. The rest stays in the cart, and on the open order or
     * table when one is loaded, for the next guest.
     */
    fun payPart(selected: Map<Int, Double>, payment: PaymentMethod, cashGiven: Long?, onDone: () -> Unit) {
        val cart = _cart.value
        val partItems = cart.items.mapIndexedNotNull { i, item ->
            val q = Qty.norm((selected[i] ?: 0.0).coerceIn(0.0, item.quantity))
            if (q >= Qty.MIN) item.copy(quantity = q) else null
        }
        if (partItems.isEmpty()) return
        val restItems = cart.items.mapIndexedNotNull { i, item ->
            val q = Qty.norm(item.quantity - (selected[i] ?: 0.0).coerceIn(0.0, item.quantity))
            if (q >= Qty.MIN) item.copy(quantity = q) else null
        }
        if (restItems.isEmpty()) return checkout(payment, cashGiven, onDone)
        val part = Cart(partItems, cart.discountPercent)
        val rest = Cart(restItems, cart.discountPercent)
        val order = _editingOrder.value
        val table = currentTable
        val foreign = _foreignTransfer.value && payment == PaymentMethod.QR_TRANSFER
        _foreignTransfer.value = false
        _cart.value = rest
        onDone()
        viewModelScope.launch {
            if (order != null) {
                val updated = order.copy(total = rest.total, received = rest.total)
                salesDao.replace(updated, itemsOf(rest))
                _editingOrder.value = updated
            }
            pay(null, part, payment, cashGiven, foreign, table, _customer.value)
        }
    }

    /** Pays an open order straight from the order list. */
    fun payOrder(sw: SaleWithItems, payment: PaymentMethod, cashGiven: Long?) {
        val foreign = _foreignTransfer.value && payment == PaymentMethod.QR_TRANSFER
        _foreignTransfer.value = false
        viewModelScope.launch { pay(sw.sale, sw.toCart(), payment, cashGiven, foreign) }
    }

    /**
     * Creates the receipt: only now the sale gets its receipt number, the time of payment and,
     * when the payment type is covered by EET, its EET record. Nothing is sent to the tax
     * administration for an order that is never paid.
     */
    private suspend fun pay(
        order: SaleEntity?, cart: Cart, payment: PaymentMethod, cashGiven: Long?, foreign: Boolean = false, table: String? = order?.tableName,
        customer: cz.flos.pos.data.CustomerEntity? = null,
    ) {
        val s = settings.value
        val rate = if (foreign) eurRate() else null
        val now = LocalDateTime.now()
        val millis = nowMillis(now)
        val number = flos.settings.nextReceiptNumber(now.year)
        val vs = variableSymbolOf(number)
        val epc = if (foreign && rate != null) s.eurIban?.let { epcFor(s, it, cart.total, rate, vs, number) } else null
        if (foreign && epc == null) _messages.tryEmit(tr("Kurz EUR není k dispozici, tisknu českou QR platbu"))
        val qr = if (payment == PaymentMethod.QR_TRANSFER && epc == null) qrFor(cart.total, vs, "${s.businessName} uctenka $number") else null
        val received = if (payment == PaymentMethod.CASH) Money.roundToCrowns(cart.total) else cart.total
        val sale = (order ?: SaleEntity(number = number, createdAt = millis, total = cart.total, payment = payment.name)).copy(
            number = number, createdAt = millis, total = cart.total, payment = payment.name, cashGiven = cashGiven,
            cashier = s.cashier, discountPercent = cart.discountPercent,
            variableSymbol = if (payment == PaymentMethod.QR_TRANSFER || payment == PaymentMethod.BANK_TRANSFER) vs else null,
            received = received, status = SaleStatus.PAID.name,
            bankStatus = if (payment == PaymentMethod.QR_TRANSFER || payment == PaymentMethod.BANK_TRANSFER) TransferStatus.WAITING.name else null,
            payCurrency = epc?.let { "EUR" }, payAmount = epc?.amount, eurRate = epc?.let { rate },
            tableName = table, customerId = customer?.id ?: order?.customerId,
        )
        // The sale and its EET record are stored before anything is sent or printed.
        val saleId = if (order == null) salesDao.insert(sale, itemsOf(cart)) else { salesDao.replace(sale, itemsOf(cart)); sale.id }
        val record = if (payment.eet && received != 0L) eet.createRecord(saleId, number, millis, received, storno = false) else null

        _lastPaid.value = LastPaid(System.currentTimeMillis(), received, cashGiven?.let { it - received }?.takeIf { it > 0 })
        val sent = record?.let { sendToEet(it) }
        val buyer = customer ?: sale.customerId?.let { customerDao.get(it) }
        val earned = if (buyer != null) pointsFor(received) else 0L
        if (buyer != null && earned > 0) customerDao.addPoints(buyer.id, earned)
        val note = listOfNotNull(
            table?.let { tr("Stůl %s", it) },
            buyer?.let { "Zákazník: ${it.name}" + if (earned > 0) ", body +$earned (celkem ${it.points + earned})" else "" },
            sale.note.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        val receipt = Receipt(number, now, s.cashier, cart, payment, cashGiven, qr, eet = sent?.let(::eetInfo), note = note,
            epc = epc, eurRate = epc?.let { rate })
        val drawer = s.openDrawer && payment == PaymentMethod.CASH
        if (s.autoPrint) printBlocks(ReceiptLayout(s.paperWidth.chars).layout(s.business, receipt), tr("Účtenka %s vytištěna", number), drawer)
        else {
            if (drawer) openDrawer()
            _messages.tryEmit(tr("Účtenka %s uložena", number))
        }
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
        val table = currentTable
        val customer = _customer.value
        _cart.value = Cart()
        _editingOrder.value = null
        _pendingTable.value = null
        _customer.value = null
        viewModelScope.launch {
            val s = settings.value
            val now = LocalDateTime.now()
            val sale = if (existing != null) {
                existing.copy(total = cart.total, received = cart.total, discountPercent = cart.discountPercent, note = note.trim(),
                    kitchenReadyAt = null, customerId = customer?.id ?: existing.customerId)
                    .also { salesDao.replace(it, itemsOf(cart)) }
            } else {
                val orderNo = flos.settings.nextOrderNumber()
                val millis = nowMillis(now)
                SaleEntity(
                    number = "O-$millis", createdAt = millis, openedAt = millis, total = cart.total, received = cart.total,
                    payment = "", cashier = s.cashier, discountPercent = cart.discountPercent,
                    status = SaleStatus.OPEN.name, orderNumber = orderNo, note = note.trim(), tableName = table, customerId = customer?.id,
                ).let { it.copy(id = salesDao.insert(it, itemsOf(cart))) }
            }
            _messages.tryEmit(sale.tableName?.let { tr("Stůl %s uložen", it) } ?: tr("Objednávka č. %d uložena", sale.orderNumber ?: 0))
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
        _pendingTable.value = null
        _customer.value = null
        sw.sale.customerId?.let { id -> viewModelScope.launch { _customer.value = customerDao.get(id) } }
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
        val foreign = sw.sale.isForeign && payment == PaymentMethod.QR_TRANSFER
        val qr = if (payment == PaymentMethod.QR_TRANSFER && !foreign) {
            qrFor(sw.sale.total, sw.sale.variableSymbol, "${s.businessName} uctenka ${sw.sale.number}")
        } else null
        val epc = if (foreign) s.eurIban?.let { iban ->
            cz.flos.core.EpcQr(s.businessName, iban, sw.sale.payAmount!!, "/VS/${sw.sale.variableSymbol} Uctenka ${sw.sale.number}", cz.flos.core.EpcQr.bicForIban(iban))
        } else null
        val record = eetDao.forSale(sw.sale.id).firstOrNull { !it.storno }
        if (!sw.sale.isPaid) {
            return Receipt((sw.sale.orderNumber ?: 0).toString(), created, sw.sale.cashier, sw.toCart(), PaymentMethod.CARD,
                note = sw.sale.note, kind = DocumentKind.ORDER)
        }
        val refundOf = sw.sale.refundOf?.let { salesDao.get(it)?.sale?.number }
        val note = listOfNotNull(sw.sale.tableName?.let { tr("Stůl %s", it) }, sw.sale.note.takeIf { it.isNotBlank() }).joinToString(" · ")
        return Receipt(sw.sale.number, created, sw.sale.cashier, sw.toCart(), payment, sw.sale.cashGiven, qr.takeIf { refundOf == null }, copy,
            eet = record?.let(::eetInfo), note = note, epc = epc, eurRate = epc?.let { sw.sale.eurRate }, refundOf = refundOf)
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
        salesDao.update(sw.sale.copy(cancelled = true, stornoNumber = stornoNumber, cancelledAt = millis))
        // A storno is recorded in EET only when the original sale was (not for invoices or sales before EET was switched on).
        // The storno reverses everything recorded for the sale, including a tip the business kept.
        val recorded = eetDao.forSale(sw.sale.id).filterNot { it.storno }
        val record = if (recorded.isNotEmpty()) eet.createRecord(sw.sale.id, stornoNumber, millis, -recorded.sumOf { it.amount }, storno = true) else null
        val sent = record?.let { sendToEet(it) }
        _messages.tryEmit(tr("Účtenka %s stornována dokladem %s", sw.sale.number, stornoNumber))
        if (s.autoPrint) {
            val doc = Receipt(stornoNumber, now, s.cashier, sw.toCart(), sw.sale.paymentMethod ?: PaymentMethod.CASH, null, null,
                eet = sent?.let(::eetInfo), stornoOf = sw.sale.number)
            printBlocks(ReceiptLayout(s.paperWidth.chars).layout(s.business, doc), tr("Storno doklad vytištěn"))
        }
    }

    // ---- Refunds of single items ----

    /** How many of each line of [sw] can still be returned (lines already returned are subtracted). */
    suspend fun refundable(sw: SaleWithItems): List<Double> {
        val done = salesDao.refundsOf(sw.sale.id).flatMap { it.items }
        return sw.items.map { item ->
            val returned = done.filter { it.productId == item.productId && it.name == item.name && it.unitPrice == item.unitPrice }.sumOf { -it.quantity }
            Qty.norm(item.quantity - returned).coerceAtLeast(0.0)
        }
    }

    /**
     * Returns some items of a paid receipt ([quantities]: line index → quantity). A refund
     * document (vratka) with its own number and negative quantities is stored, recorded in EET
     * with a negative amount when the original was, and printed. The money goes back the same
     * way it was paid.
     */
    fun refund(sw: SaleWithItems, quantities: Map<Int, Double>) = viewModelScope.launch {
        if (!sw.sale.isPaid || sw.sale.cancelled || sw.sale.isRefund) return@launch
        val left = refundable(sw)
        val items = sw.items.mapIndexedNotNull { i, item ->
            val q = Qty.norm((quantities[i] ?: 0.0).coerceIn(0.0, left.getOrElse(i) { 0.0 }))
            if (q >= Qty.MIN) item.copy(id = 0, quantity = -q) else null
        }
        if (items.isEmpty()) return@launch
        val s = settings.value
        val payment = sw.sale.paymentMethod ?: PaymentMethod.CASH
        val cart = Cart(items.map { CartItem(it.productId, it.name, it.unitPrice, VatRate.fromPercent(it.vatPercent), it.quantity, it.discountPercent) },
            sw.sale.discountPercent)
        val now = LocalDateTime.now()
        val millis = nowMillis(now)
        val number = flos.settings.nextReceiptNumber(now.year)
        val received = if (payment == PaymentMethod.CASH) Money.roundToCrowns(cart.total) else cart.total
        val sale = SaleEntity(
            number = number, createdAt = millis, total = cart.total, payment = payment.name, cashier = s.cashier,
            discountPercent = sw.sale.discountPercent, received = received, refundOf = sw.sale.id, tableName = sw.sale.tableName,
            note = tr("Vratka k účtence %s", sw.sale.number),
        )
        val id = salesDao.insert(sale, items)
        val wasRecorded = eetDao.forSale(sw.sale.id).any { !it.storno }
        val record = if (wasRecorded && received != 0L) eet.createRecord(id, number, millis, received, storno = false) else null
        val sent = record?.let { sendToEet(it) }
        _messages.tryEmit(tr("Vratka %s: vraťte zákazníkovi %s", number, Money.format(-received)))
        if (payment == PaymentMethod.CASH && s.openDrawer) openDrawer()
        if (s.autoPrint) {
            val doc = Receipt(number, now, s.cashier, cart, payment, eet = sent?.let(::eetInfo), refundOf = sw.sale.number)
            printBlocks(ReceiptLayout(s.paperWidth.chars).layout(s.business, doc), tr("Vratka vytištěna"))
        }
    }

    // ---- Statistics ----

    /** Statistics for the days [from]..[to] (inclusive), compared with the same number of days before. */
    /** Sales as the statistics need them, with product names and categories from the catalogue. */
    private suspend fun statSales(list: List<SaleWithItems>): List<cz.flos.core.StatSale> {
        val products = catalog.allProducts().associateBy { it.id }
        val categories = categories.value.associateBy { it.id }
        return list.map { sw ->
            cz.flos.core.StatSale(
                at = sw.sale.createdAt, received = sw.sale.received, tip = sw.sale.tip,
                payment = sw.sale.paymentMethod ?: PaymentMethod.CASH, cashier = sw.sale.cashier, cancelled = sw.sale.cancelled,
                refund = sw.sale.isRefund, number = sw.sale.number,
                items = sw.toCart().items.map { item ->
                    val product = products[item.productId]
                    cz.flos.core.StatItem(
                        name = product?.name ?: item.name,
                        category = product?.categoryId?.let(categories::get)?.name ?: tr("Bez kategorie"),
                        quantity = item.quantity, total = item.total,
                    )
                },
            )
        }
    }

    private fun startOf(d: LocalDate) = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    suspend fun statistics(from: LocalDate, to: LocalDate): cz.flos.core.Statistics {
        val days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1
        val current = salesDao.between(startOf(from), startOf(to.plusDays(1)))
        val previous = salesDao.between(startOf(from.minusDays(days)), startOf(from))
        return cz.flos.core.Statistics.of(statSales(current), from, to, ZoneId.systemDefault(), statSales(previous))
    }

    /** Receipt statistics: how many receipts, how big, when, and the largest ones. */
    suspend fun receiptStatistics(from: LocalDate, to: LocalDate): cz.flos.core.ReceiptStatistics {
        val list = salesDao.between(startOf(from), startOf(to.plusDays(1)))
        return cz.flos.core.ReceiptStatistics.of(statSales(list), from, to, ZoneId.systemDefault())
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
        val day = salesDao.between(from, to)
        val transfers = day.map { it.sale }.filter { it.transferStatus != null && !it.cancelled }
        val waitingAll = bankDao.waitingSales()
        val dueBefore = System.currentTimeMillis() - settings.value.transferDueDays * 86_400_000L
        val late = bankDao.confirmedBetween(from, to).filter { it.createdAt < from }
        val summary = cz.flos.core.TransferSummary(
            confirmedCount = transfers.count { it.transferStatus == TransferStatus.CONFIRMED },
            confirmed = transfers.filter { it.transferStatus == TransferStatus.CONFIRMED }.sumOf { it.received },
            waitingCount = transfers.count { it.awaitsTransfer },
            waiting = transfers.sumOf { it.transferMissing },
            lateCount = late.size,
            late = late.sumOf { it.received },
            openCount = waitingAll.size,
            open = waitingAll.sumOf { it.transferMissing },
            overdueCount = waitingAll.count { it.createdAt < dueBefore },
            overdue = waitingAll.filter { it.createdAt < dueBefore }.sumOf { it.transferMissing },
        )
        return DailyReport.of(date, day.map { it.toSummary() }).copy(transfers = summary.takeIf { it.isNotEmpty })
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

    private fun printBlocks(blocks: List<ReceiptBlock>, okMessage: String, drawer: Boolean = false) {
        val s = settings.value
        viewModelScope.launch {
            val bytes = EscPosEncoder(s.paperWidth, s.charset).encode(blocks, openDrawer = drawer)
            runCatching { printer.print(s, bytes) }
                .onSuccess { _messages.tryEmit(okMessage) }
                .onFailure { _messages.tryEmit(tr("Tisk se nezdařil: %s", it.message)) }
        }
    }

    fun notify(text: String) { _messages.tryEmit(text) }

    /** Opens the cash drawer connected to the receipt printer. */
    fun openDrawer() {
        val s = settings.value
        viewModelScope.launch {
            val bytes = EscPosEncoder(s.paperWidth, s.charset).encode(emptyList(), cut = false, openDrawer = true)
            runCatching { printer.print(s, bytes) }
                .onFailure { _messages.tryEmit(tr("Tisk se nezdařil: %s", it.message)) }
        }
    }

    // ---- Cash drawer and shifts ----
    fun cashMovement(kind: CashKind, amount: Long, note: String) = viewModelScope.launch {
        if (amount <= 0) return@launch
        cashDao.insert(CashMovementEntity(at = System.currentTimeMillis(), kind = kind.name, amount = amount, note = note.trim(),
            cashier = settings.value.cashier))
        if (settings.value.openDrawer) openDrawer()
        _messages.tryEmit(tr("%s: %s", tr(kind.label), Money.format(amount)))
    }

    /**
     * Closes the shift: stores the counted cash next to the expected amount, prints a shift
     * report and, when [leaveFloat] is above zero, starts the next shift with that change money.
     */
    fun closeShift(counted: Long, leaveFloat: Long) = viewModelScope.launch {
        val s = settings.value
        val state = cashState.value
        val now = System.currentTimeMillis()
        cashDao.insert(CashMovementEntity(at = now, kind = CashKind.CLOSE.name, amount = counted, cashier = s.cashier, expected = state.expected))
        if (leaveFloat > 0) {
            cashDao.insert(CashMovementEntity(at = now + 1, kind = CashKind.FLOAT.name, amount = leaveFloat, cashier = s.cashier))
        }
        val zone = ZoneId.systemDefault()
        val fmt = java.time.format.DateTimeFormatter.ofPattern("d.M.yyyy HH:mm")
        val layout = ReceiptLayout(s.paperWidth.chars)
        fun row(label: String, amount: Long) = ReceiptBlock.Line(layout.twoCol(label, Money.format(amount)))
        val blocks = buildList {
            add(ReceiptBlock.Line(s.businessName, Align.CENTER, bold = true))
            add(ReceiptBlock.Line("UZAVŘENÍ SMĚNY", Align.CENTER, bold = true, large = true))
            if (state.since > 0) add(ReceiptBlock.Line("Od: " + fmt.format(Instant.ofEpochMilli(state.since).atZone(zone))))
            add(ReceiptBlock.Line("Do: " + fmt.format(Instant.ofEpochMilli(now).atZone(zone))))
            if (s.cashier.isNotBlank()) add(ReceiptBlock.Line("Pokladník: ${s.cashier}"))
            add(ReceiptBlock.Separator)
            add(row("Počáteční stav", state.float))
            add(row("Hotovostní tržby", state.sales))
            add(row("Storna v hotovosti", -state.refunds))
            add(row("Vklady", state.cashIn))
            add(row("Výběry", -state.cashOut))
            add(ReceiptBlock.Separator)
            add(row("Očekáváno", state.expected))
            add(row("Spočítáno", counted))
            add(row("Rozdíl", counted - state.expected))
            if (leaveFloat > 0) add(row("Ponecháno na další směnu", leaveFloat))
            add(ReceiptBlock.Feed(3))
        }
        printBlocks(blocks, tr("Směna uzavřena"))
    }

    /** Looks up a product by its barcode and adds it to the cart. Returns false when no product has that code. */
    suspend fun addByBarcode(code: String): Boolean {
        val p = salesDao.productByBarcode(code.trim()) ?: return false
        addToCart(p)
        return true
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
