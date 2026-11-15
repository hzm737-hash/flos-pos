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
import kotlinx.coroutines.sync.withLock
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
import androidx.room.useWriterConnection
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Product id of lines typed in by hand (not in the catalogue); the back office stores them without a product. */
const val MANUAL_ITEM_ID = 0L

/** Product id of the service charge line (Pokročilé nastavení → Servisní poplatek). */
const val SERVICE_CHARGE_ID = -1L

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

    // ---- App updates from the back office (Pokročilé nastavení → Aktualizace z back office) ----
    private val _update = MutableStateFlow<cz.flos.core.backoffice.BoUpdateInfo?>(null)
    /** A newer app build waiting in the back office, or null. */
    val appUpdate: StateFlow<cz.flos.core.backoffice.BoUpdateInfo?> = _update.asStateFlow()
    private var updateAnnounced = 0

    /** Asks the back office for a newer build; [silent] = no message when there is none. */
    private suspend fun checkAppUpdate(silent: Boolean) {
        val s = settings.value
        val platform = flos.platform.updatePlatform
        if (!s.paired || !s.on(cz.flos.pos.data.AdvFeature.APP_UPDATES) || platform == null) { _update.value = null; return }
        runCatching { flos.backOffice.checkUpdate(platform, flos.platform.appVersionCode, flos.platform.appVersionName) }
            .onSuccess { u ->
                _update.value = u.takeIf { it.available && it.url.isNotBlank() }
                val found = _update.value
                if (found != null && (found.versionCode != updateAnnounced || !silent)) {
                    updateAnnounced = found.versionCode
                    _messages.tryEmit(tr("Je k dispozici nová verze aplikace %s. Najdete ji v Nastavení → Back office.", found.versionName))
                } else if (found == null && !silent) _messages.tryEmit(tr("Aplikace je aktuální."))
            }
            .onFailure { if (!silent) _messages.tryEmit(tr("Kontrola aktualizace selhala: %s", it.message ?: it.javaClass.simpleName)) }
    }

    fun checkAppUpdateNow() = viewModelScope.launch { checkAppUpdate(silent = false) }

    /** Opens the download of the new build; Android then offers to install it over this app (data stay). */
    fun installAppUpdate() {
        val u = _update.value ?: return
        if (!flos.platform.openUrl(u.url)) _messages.tryEmit(tr("Odkaz se nepodařilo otevřít: %s", u.url))
    }

    init {
        viewModelScope.launch { if (catalog.productCount() == 0) seedDemoData() }

        // Checks the bank every minute and syncs with the back office every five minutes (settings every minute) while the till is open.
        viewModelScope.launch {
            var minute = 0
            while (true) {
                if (settings.value.bankSource != BankSource.OFF) checkBank(silent = true)
                if (settings.value.paired && minute % 5 == 0) syncBackOffice(silent = true)
                else if (settings.value.paired && settings.value.on(cz.flos.pos.data.AdvFeature.BO_SETTINGS)) runCatching { flos.backOffice.syncSettings() }
                if (settings.value.paired && settings.value.on(cz.flos.pos.data.AdvFeature.BO_LIVE)) runCatching { flos.backOffice.uploadSnapshot(tillSnapshot()) }
                if (minute % 30 == 1) checkAppUpdate(silent = true)
                minute++
                delay(60_000)
            }
        }
        // Orders from the tables and from the website: every 10 seconds while switched on.
        viewModelScope.launch {
            var round = 0
            while (true) {
                val s = settings.value
                // From the second round on (the first one runs while the till is still being set up).
                if (round > 0 && s.paired && (s.on(cz.flos.pos.data.AdvFeature.QR_ORDERING) || s.on(cz.flos.pos.data.AdvFeature.WEB_ORDERING))) {
                    syncOrderControl(pull = round % 6 == 1)
                    sendOpenTables(force = round % 30 == 1)
                }
                round++
                if (s.paired && s.on(cz.flos.pos.data.AdvFeature.QR_ORDERING)) pollTableOrders()
                // Orders from the tables on the restaurant's own website come the same way as web orders.
                if (s.paired && (s.on(cz.flos.pos.data.AdvFeature.WEB_ORDERING) ||
                        (s.on(cz.flos.pos.data.AdvFeature.QR_ORDERING) && s.tableMenuUrl.isNotBlank()))) pollWebOrders()
                delay(10_000)
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
    fun toggleHalfPortion() { _halfPortion.value = !_halfPortion.value; if (_halfPortion.value) _pendingQty.value = null }

    /** Quantity for the product being added: 0,5 once after "½ porce", otherwise 1. */
    private fun nextQty(): Double {
        _pendingQty.value?.let { q -> _pendingQty.value = null; _halfPortion.value = false; return q }
        return if (_halfPortion.value) { _halfPortion.value = false; 0.5 } else 1.0
    }

    private val _pendingQty = MutableStateFlow<Double?>(null)
    /** Quantity typed before tapping a product ("5 ×"); used once, then back to 1. */
    val pendingQty: StateFlow<Double?> = _pendingQty.asStateFlow()
    fun setPendingQty(q: Double?) { _pendingQty.value = q?.let(Qty::norm)?.takeIf { it >= Qty.MIN }; if (_pendingQty.value != null) _halfPortion.value = false }

    // ---- Add-ons, kitchen notes, open prices and manual items ----
    /** The product's own variants and extras, then the add-ons from Settings for its category. */
    fun optionGroupsFor(p: ProductEntity): List<cz.flos.core.OptionGroup> {
        val category = p.categoryId?.let { id -> categories.value.firstOrNull { it.id == id }?.name }
        val own = cz.flos.core.ProductOptions.parse(p.options)
        if (!settings.value.on(cz.flos.pos.data.AdvFeature.ADDONS)) return own
        return own + cz.flos.core.Addons.forCategory(cz.flos.core.Addons.parse(settings.value.addons), category)
    }

    /** Writes a kitchen note on one cart line ("pálivé, bez cibule"); an empty note removes it. */
    fun setLineNote(index: Int, note: String) = _cart.update { cart ->
        val item = cart.items.getOrNull(index) ?: return@update cart
        cart.rename(index, cz.flos.core.LineNote.join(cz.flos.core.LineNote.split(item.name).first, note))
    }

    /** An open-price product with the price typed in at the till ([name] may carry extras and a note). */
    fun addOpenPrice(p: ProductEntity, name: String, price: Long) {
        if (!allow(cz.flos.core.Permission.MANUAL_PRICE) || price < 0) return
        val q = nextQty()
        _cart.update { it.add(p.id, name, price, p.vatRate, q) }
    }

    /** Something that is not in the catalogue: name, price and VAT typed in at the till. */
    fun addManualItem(name: String, price: Long, vat: VatRate) {
        if (!allow(cz.flos.core.Permission.MANUAL_PRICE) || price < 0 || name.isBlank()) return
        val q = nextQty()
        _cart.update { it.add(MANUAL_ITEM_ID, name.trim(), price, vat, q) }
    }

    // ---- Backup and restore (Pokročilé nastavení) ----
    /** The whole till in one .zip: database, settings with counters, certificate and attachments. */
    /** The backup encrypted with [password] (it holds bank and EET secrets and customer data). */
    suspend fun createBackup(password: String): ByteArray = cz.flos.core.BackupCrypto.encrypt(createPlainBackup(), password)

    private suspend fun createPlainBackup(): ByteArray = withContext(Dispatchers.IO) {
        // Writes the recent changes from the write-ahead log into the main file first, so the copy is
        // one consistent file even if a sale is saved meanwhile (the -wal file is still copied too).
        runCatching { flos.database.useWriterConnection { c -> c.usePrepared("PRAGMA wal_checkpoint(FULL)") { it.step() } } }
        cz.flos.pos.backup.FlosBackup.create(platform.dataDir, flos.settings.exportAll())
    }

    /** Checks a backup and leaves it to be restored at the next start; returns why not, or null when ready. */
    suspend fun stageRestore(bytes: ByteArray, password: String?): String? = withContext(Dispatchers.IO) {
        if (!can(cz.flos.core.Permission.SETTINGS)) return@withContext tr("Nemáte oprávnění: %s.", tr(cz.flos.core.Permission.SETTINGS.label))
        // Backups made before the password existed are plain .zip files and still restore.
        val zip = if (!cz.flos.core.BackupCrypto.isEncrypted(bytes)) bytes else when (val o = cz.flos.core.BackupCrypto.decrypt(bytes, password.orEmpty())) {
            is cz.flos.core.BackupCrypto.Opened.Ok -> o.zip
            cz.flos.core.BackupCrypto.Opened.WrongPassword -> return@withContext tr("Špatné heslo zálohy.")
            cz.flos.core.BackupCrypto.Opened.Damaged -> return@withContext tr("Soubor zálohy je poškozený.")
        }
        cz.flos.pos.backup.FlosBackup.check(zip, platform.dataDir)?.let { return@withContext tr(it) }
        cz.flos.pos.backup.FlosBackup.stage(zip, platform.dataDir)
        null
    }

    /** True when a backup file needs its password before it can be restored. */
    fun backupNeedsPassword(bytes: ByteArray): Boolean = cz.flos.core.BackupCrypto.isEncrypted(bytes)

    // ---- Service charge (Pokročilé nastavení) ----
    /** Adds the service charge line to the cart, or removes it when it is there. */
    fun toggleServiceCharge() {
        if (!settings.value.on(cz.flos.pos.data.AdvFeature.SERVICE_CHARGE)) return
        _cart.update { c ->
            val i = c.items.indexOfFirst { it.productId == SERVICE_CHARGE_ID }
            if (i >= 0) c.remove(i)
            else withServiceCharge(c.copy(items = c.items + CartItem(SERVICE_CHARGE_ID, "", 0, VatRate.fromPercent(settings.value.serviceChargeVat), 1.0)))
        }
    }

    /** The service charge line follows the rest of the cart: [percent] of everything else, one piece, no line discount. */
    private fun withServiceCharge(c: Cart): Cart {
        val i = c.items.indexOfFirst { it.productId == SERVICE_CHARGE_ID }
        if (i < 0) return c
        val s = settings.value
        val pct = s.serviceChargePercent.coerceIn(0, 100)
        val base = c.items.filter { it.productId != SERVICE_CHARGE_ID }.sumOf { it.total }
        val line = c.items[i]
        val want = line.copy(
            name = "Servisní poplatek $pct %", unitPrice = Math.round(base * pct / 100.0).coerceAtLeast(0),
            quantity = 1.0, discountPercent = 0,
        )
        return if (want == line) c else c.copy(items = c.items.toMutableList().also { it[i] = want })
    }

    init {
        // Keeps the service charge up to date whenever the cart changes.
        viewModelScope.launch { _cart.collect { c -> val n = withServiceCharge(c); if (n != c) _cart.value = n } }
    }

    fun addToCart(p: ProductEntity) {
        // An open-price product needs its price typed in (tap the tile); never sell it for 0 by accident.
        if (p.openPrice) { _messages.tryEmit(tr("%s: cena se zadává při prodeji, klepněte na produkt.", p.name)); return }
        val q = nextQty()
        val (unit, auto) = priced(p)
        _cart.update { it.add(p.id, p.name, unit, p.vatRate, q, auto) }
    }

    /** Adds a product with the variants and extras chosen for it ("Pizza (Velká, Sýr)"). */
    fun addToCart(p: ProductEntity, name: String, price: Long) {
        val q = nextQty()
        val (unit, auto) = priced(p, extras = price - p.price)
        _cart.update { it.add(p.id, name, unit, p.vatRate, q, auto) }
    }

    // ---- Service types, price lists and automatic discounts ----
    private val _serviceType = MutableStateFlow<String?>(null)
    /** Service type of the cart ("Na místě", "S sebou", "Rozvoz"); null = none chosen. */
    val serviceType: StateFlow<String?> = _serviceType.asStateFlow()

    private val _priceList = MutableStateFlow<String?>(null)
    /** Price list chosen by hand for the cart; null = the service type's list or the normal prices. */
    val priceList: StateFlow<String?> = _priceList.asStateFlow()

    /** Price lists the products and service types use, for the choice on the sell screen. */
    fun priceListNames(): List<String> = cz.flos.core.Pricing.priceListNames(products.value.map { it.prices }, settings.value.serviceTypeList)

    private fun serviceNamed(name: String?): cz.flos.core.ServiceType? =
        name?.let { n -> settings.value.serviceTypeList.firstOrNull { cz.flos.core.Pricing.key(it.name) == cz.flos.core.Pricing.key(n) } }

    /** Unit price and automatic discount (percent) of [p] for a service type and price list; [extras] = variants and extras. */
    private fun priced(p: ProductEntity, extras: Long = 0, service: String? = _serviceType.value, list: String? = _priceList.value): Pair<Long, Int> {
        val svc = serviceNamed(service)
        val listed = cz.flos.core.Pricing.unitPrice(p.price, p.prices, list ?: svc?.priceList, null) + extras
        val unit = if (svc == null || svc.percent == 0.0) listed else Math.round(listed * (1 + svc.percent / 100.0)).coerceAtLeast(0)
        val category = p.categoryId?.let { id -> categories.value.firstOrNull { it.id == id }?.name }
        val rule = cz.flos.core.Pricing.discount(settings.value.discountRuleList, p.name, category, svc?.name, LocalDateTime.now())
        return unit to (rule?.percent ?: 0)
    }

    /** Switches the service type and price list; the lines already in the cart get the new prices. */
    fun setPricing(service: String?, list: String?) {
        val oldService = _serviceType.value
        val oldList = _priceList.value
        _serviceType.value = service
        _priceList.value = list
        val byId = products.value.associateBy { it.id }
        _cart.update { cart ->
            cart.copy(items = cart.items.map { item ->
                val p = byId[item.productId] ?: return@map item
                val (oldBase, oldAuto) = priced(p, 0, oldService, oldList)
                val oldPct = serviceNamed(oldService)?.percent ?: 0.0
                // Extras chosen with the product, as they were before the service type's surcharge.
                val extras = Math.round(item.unitPrice / (1 + oldPct / 100.0)) - Math.round(oldBase / (1 + oldPct / 100.0))
                val (unit, auto) = priced(p, extras, service, list)
                item.copy(unitPrice = unit, discountPercent = if (item.discountPercent == oldAuto) auto else item.discountPercent)
            })
        }
    }

    // ---- Roles and permissions ----
    /** Role of whoever is at the till now (owner when staff roles are not set up). */
    fun currentRole(): cz.flos.core.StaffRole {
        val s = settings.value
        val staff = cz.flos.core.backoffice.BackOfficeClient.staffFromJson(s.boStaff).map { it.name to it.role }
        return cz.flos.core.Permissions.roleOf(s.cashier, staff, s.staffRoles)
    }

    fun can(p: cz.flos.core.Permission): Boolean = cz.flos.core.Permissions.parse(settings.value.permissions).allowed(currentRole(), p)

    /** True when allowed; otherwise tells the cashier why nothing happened. */
    private fun allow(p: cz.flos.core.Permission): Boolean {
        if (can(p)) return true
        _messages.tryEmit(tr("Nemáte oprávnění: %s. Přepněte obsluhu na vedoucího.", tr(p.label)))
        return false
    }

    /** Highest manual discount the current cashier may give (managers and owners: 100). */
    fun discountLimit(): Int {
        val s = settings.value
        if (s.maxCashierDiscount >= 100) return 100
        val staff = cz.flos.core.backoffice.BackOfficeClient.staffFromJson(s.boStaff).firstOrNull { it.name == s.cashier }
        return if (staff?.role == "OWNER" || staff?.role == "MANAGER") 100 else s.maxCashierDiscount.coerceIn(0, 100)
    }

    private fun limited(percent: Int): Int {
        val max = discountLimit()
        if (percent > max) _messages.tryEmit(tr("Nejvyšší sleva pro pokladníka je %d %%", max))
        return percent.coerceAtMost(max)
    }

    // ---- Customers and loyalty ----
    val customers = customerDao.all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _customer = MutableStateFlow<cz.flos.pos.data.CustomerEntity?>(null)
    /** Customer chosen for the cart; gets loyalty points and their discount. */
    val customer: StateFlow<cz.flos.pos.data.CustomerEntity?> = _customer.asStateFlow()

    fun selectCustomer(c: cz.flos.pos.data.CustomerEntity?) {
        _customer.value = c
        if (c != null && c.discountPercent > 0) _cart.update { it.setDiscount(c.discountPercent) }
        if (c != null && settings.value.on(cz.flos.pos.data.AdvFeature.BIRTHDAYS)) {
            val bd = cz.flos.core.Birthdays.parse(c.birthday)
            if (bd != null && cz.flos.core.Birthdays.daysUntil(bd, LocalDate.now()) == 0) _messages.tryEmit(tr("%s má dnes narozeniny!", c.name))
        }
    }

    /** Saves a customer; [select] also picks them for the cart (a new customer made at the till). */
    fun saveCustomer(c: cz.flos.pos.data.CustomerEntity, select: Boolean = false) = viewModelScope.launch {
        if (!allow(cz.flos.core.Permission.CUSTOMERS)) return@launch
        val rowId = customerDao.upsert(if (c.createdAt == 0L) c.copy(createdAt = System.currentTimeMillis()) else c)
        val id = if (c.id != 0L) c.id else rowId
        if (select || (_customer.value?.id == id && id != 0L)) customerDao.get(id)?.let { selectCustomer(it) }
        _messages.tryEmit(tr("Zákazník %s uložen", c.name))
    }

    fun deleteCustomer(c: cz.flos.pos.data.CustomerEntity) = viewModelScope.launch {
        if (!allow(cz.flos.core.Permission.CUSTOMERS)) return@launch
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
        runCatching {
            flos.backOffice.sync().also { if (settings.value.on(cz.flos.pos.data.AdvFeature.BO_SETTINGS)) flos.backOffice.syncSettings() }
        }
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

    /**
     * Moves every open bill of table [from] to table [to] (Pokročilé nastavení → Plán stolů).
     * When [to] already has a bill, both stay there as separate bills.
     */
    fun moveTable(from: String, to: String) = viewModelScope.launch {
        if (from == to) return@launch
        val bills = openOrders.value.filter { it.sale.tableName == from }
        if (bills.isEmpty()) return@launch
        bills.forEach { salesDao.update(it.sale.copy(tableName = to)) }
        _editingOrder.value?.let { e -> if (e.tableName == from) _editingOrder.value = e.copy(tableName = to) }
        if (_pendingTable.value == from) _pendingTable.value = to
        audit(tr("Účet přesunut ze stolu %s na stůl %s", from, to), "")
        _messages.tryEmit(tr("Účet přesunut na stůl %s.", to))
    }

    /** Saves where table [name] sits on the plan; x and y run from 0 to 1. */
    fun placeTable(name: String, x: Float, y: Float) = updateSettings { s ->
        val others = s.tablePositions - name
        val all = others + (name to (x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)))
        s.copy(tablePlan = all.entries.joinToString("\n") { (n, p) -> "%.4f;%.4f;%s".format(java.util.Locale.ROOT, p.first, p.second, n) })
    }

    /** Puts every table back in rows on the plan. */
    fun resetTablePlan() = updateSettings { it.copy(tablePlan = "") }

    /** Table of the cart: the loaded order's table or the one just picked on the map. */
    val currentTable: String? get() = _editingOrder.value?.tableName ?: _pendingTable.value
    fun setQuantity(index: Int, qty: Double) = _cart.update { it.setQuantity(index, qty) }
    fun setLineDiscount(index: Int, percent: Int) { if (percent > 0 && !allow(cz.flos.core.Permission.DISCOUNT)) return; val p = limited(percent); _cart.update { it.setLineDiscount(index, p) } }
    fun setCartDiscount(percent: Int) { if (percent > 0 && !allow(cz.flos.core.Permission.DISCOUNT)) return; val p = limited(percent); _cart.update { it.setDiscount(p) } }
    fun clearCart() {
        _cart.update { it.clear() }
        _editingOrder.value = null
        _pendingTable.value = null
        _customer.value = null
        _serviceType.value = null
        _priceList.value = null
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
        val service = _serviceType.value
        _foreignTransfer.value = false
        _cart.value = Cart()
        _editingOrder.value = null
        _pendingTable.value = null
        _customer.value = null
        _serviceType.value = null
        _priceList.value = null
        onDone()
        viewModelScope.launch { pay(order, cart, payment, cashGiven, foreign, table, customer, service = service) }
    }

    /**
     * Pays the cart with several methods at once, e.g. meal vouchers and the rest in cash.
     * [parts] must add up to the cart total; [cashGiven] is the cash handed over for the cash part.
     */
    fun checkoutMixed(parts: List<cz.flos.core.PaymentPart>, cashGiven: Long?, onDone: () -> Unit) {
        val cart = _cart.value
        val used = parts.filter { it.amount > 0 }
        if (cart.isEmpty() || used.sumOf { it.amount } != cart.total) return
        if (used.size < 2) return checkout(used.firstOrNull()?.method ?: PaymentMethod.CASH, cashGiven, onDone)
        val order = _editingOrder.value
        val table = currentTable
        val customer = _customer.value
        val service = _serviceType.value
        _cart.value = Cart()
        _editingOrder.value = null
        _pendingTable.value = null
        _customer.value = null
        _serviceType.value = null
        _priceList.value = null
        onDone()
        viewModelScope.launch { pay(order, cart, cz.flos.core.PaymentPart.main(used), cashGiven, false, table, customer, used, service) }
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
        parts: List<cz.flos.core.PaymentPart> = emptyList(),
        service: String? = order?.serviceType,
    ) {
        val s = settings.value
        val mixed = parts.size > 1
        val rate = if (foreign) eurRate() else null
        val now = LocalDateTime.now()
        val millis = nowMillis(now)
        val number = flos.settings.nextReceiptNumber(now.year)
        val vs = variableSymbolOf(number)
        val epc = if (foreign && rate != null) s.eurIban?.let { epcFor(s, it, cart.total, rate, vs, number) } else null
        if (foreign && epc == null) _messages.tryEmit(tr("Kurz EUR není k dispozici, tisknu českou QR platbu"))
        val qr = if (payment == PaymentMethod.QR_TRANSFER && epc == null) qrFor(cart.total, vs, "${s.businessName} uctenka $number") else null
        val received = if (payment == PaymentMethod.CASH && !mixed) Money.roundToCrowns(cart.total) else cart.total
        val sale = (order ?: SaleEntity(number = number, createdAt = millis, total = cart.total, payment = payment.name)).copy(
            number = number, createdAt = millis, total = cart.total, payment = payment.name, cashGiven = cashGiven,
            cashier = s.cashier, discountPercent = cart.discountPercent,
            variableSymbol = if (payment == PaymentMethod.QR_TRANSFER || payment == PaymentMethod.BANK_TRANSFER) vs else null,
            received = received, status = SaleStatus.PAID.name,
            bankStatus = if (payment == PaymentMethod.QR_TRANSFER || payment == PaymentMethod.BANK_TRANSFER) TransferStatus.WAITING.name else null,
            payCurrency = epc?.let { "EUR" }, payAmount = epc?.amount, eurRate = epc?.let { rate },
            tableName = table, customerId = customer?.id ?: order?.customerId,
            paymentParts = if (mixed) cz.flos.core.PaymentPart.format(parts) else null,
            cashPart = if (mixed) parts.filter { it.method == PaymentMethod.CASH }.sumOf { it.amount } else 0,
            serviceType = service,
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
            service,
            table?.let { tr("Stůl %s", it) },
            buyer?.let { "Zákazník: ${it.name}" + if (earned > 0) ", body +$earned (celkem ${it.points + earned})" else "" },
            sale.note.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        val receipt = Receipt(number, now, s.cashier, cart, payment, cashGiven, qr, eet = sent?.let(::eetInfo), note = note,
            epc = epc, eurRate = epc?.let { rate }, parts = if (mixed) parts else emptyList())
        val drawer = s.openDrawer && (payment == PaymentMethod.CASH || sale.cashPart > 0)
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
        val service = _serviceType.value
        _cart.value = Cart()
        _editingOrder.value = null
        _pendingTable.value = null
        _customer.value = null
        _serviceType.value = null
        _priceList.value = null
        viewModelScope.launch {
            val s = settings.value
            val now = LocalDateTime.now()
            val sale = if (existing != null) {
                existing.copy(total = cart.total, received = cart.total, discountPercent = cart.discountPercent, note = note.trim(),
                    kitchenReadyAt = null, customerId = customer?.id ?: existing.customerId, serviceType = service)
                    .also { salesDao.replace(it, itemsOf(cart)) }
            } else {
                val orderNo = flos.settings.nextOrderNumber()
                val millis = nowMillis(now)
                SaleEntity(
                    number = "O-$millis", createdAt = millis, openedAt = millis, total = cart.total, received = cart.total,
                    payment = "", cashier = s.cashier, discountPercent = cart.discountPercent,
                    status = SaleStatus.OPEN.name, orderNumber = orderNo, note = note.trim(), tableName = table, customerId = customer?.id,
                    serviceType = service,
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

    // ---- Ringing for new orders and when orders are taken (ordering hours, "stop orders") ----
    private val _ringing = MutableStateFlow(false)
    /** True while the loud ring for a new order sounds; the screens show a button to stop it. */
    val ringing: StateFlow<Boolean> = _ringing.asStateFlow()

    /** A new order arrived: the loud ring until someone stops it, or one short beep (as before). */
    private fun newOrderRing() {
        val s = settings.value
        if (!s.orderAlarm) { platform.beep(); return }
        _ringing.value = true
        platform.startAlarm(s.orderAlarmSound, s.orderAlarmVolume) { _ringing.value = false }
    }

    fun stopRinging() {
        _ringing.value = false
        platform.stopAlarm()
    }

    fun testRing() = settings.value.let { platform.testAlarm(it.orderAlarmSound, it.orderAlarmVolume) }

    /** Ordering hours or "stop orders" changed here and not yet saved in the back office. */
    @Volatile private var orderControlDirty = false
    private val orderControlMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Changes the ordering hours or "stop orders" and saves them in the back office right away, which then
     * turns away orders accordingly. Unreadable hours are not saved (the message says which day).
     */
    fun setOrderControl(transform: (cz.flos.core.OrderControl) -> cz.flos.core.OrderControl) {
        val next = transform(settings.value.orderControl)
        val week = listOf(next.qrHours, next.webHours).firstNotNullOfOrNull { cz.flos.core.OrderHours.problem(it) }
        updateSettings { it.withOrderControl(next) }
        if (week != null) { _messages.tryEmit(tr(week)); return }
        orderControlDirty = true
        viewModelScope.launch { syncOrderControl(pull = false, report = true) }
    }

    /** Sends a change made here, or (with [pull]) takes the one made on another till. */
    private suspend fun syncOrderControl(pull: Boolean, report: Boolean = false) {
        if (!settings.value.paired) return
        orderControlMutex.lock()
        try {
            if (orderControlDirty) {
                val c = settings.value.orderControl
                if (listOf(c.qrHours, c.webHours).any { cz.flos.core.OrderHours.problem(it) != null }) return
                runCatching { withContext(Dispatchers.IO) { boClient().setOrderControl(c) } }
                    .onSuccess { if (settings.value.orderControl == c) orderControlDirty = false }
                    .onFailure { if (report) _messages.tryEmit(tr("Nepodařilo se uložit do back office, zkusím to znovu: %s", it.message ?: "")) }
            } else if (pull) {
                val remote = runCatching { withContext(Dispatchers.IO) { boClient().orderControl() } }.getOrNull() ?: return
                if (!orderControlDirty && remote != settings.value.orderControl) updateSettings { it.withOrderControl(remote) }
            }
        } finally { orderControlMutex.unlock() }
    }

    private var sentOpenTables: List<String>? = null

    /** Tables with an open bill here, so their guests may still order after hours (only when that is used). */
    private suspend fun sendOpenTables(force: Boolean) {
        val s = settings.value
        if (!s.on(cz.flos.pos.data.AdvFeature.QR_ORDERING) || !s.qrHoursOn || !s.openTablesAfterHours) return
        val tables = openOrders.value.mapNotNull { it.sale.tableName?.trim()?.takeIf { t -> t.isNotEmpty() } }.distinct().sorted()
        if (!force && tables == sentOpenTables) return
        runCatching { withContext(Dispatchers.IO) { boClient().sendOpenTables(tables) } }.onSuccess { sentOpenTables = tables }
    }

    // ---- Orders from the tables (QR code on the table, Pokročilé nastavení → Objednávky z QR) ----
    private val _qrInbox = MutableStateFlow<List<cz.flos.core.TableOrder>>(emptyList())
    /** Orders from the tables waiting for the staff to accept or decline them. */
    val qrInbox: StateFlow<List<cz.flos.core.TableOrder>> = _qrInbox.asStateFlow()
    /** Orders this till has already shown or taken, so a second fetch does not bring them twice. */
    private val qrSeen = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    private val qrMutex = kotlinx.coroutines.sync.Mutex()
    private val qrTaking = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    private val _tableLinks = MutableStateFlow<List<cz.flos.core.TableLink>>(emptyList())
    /** The QR code link of each table, loaded from the back office. */
    val tableLinks: StateFlow<List<cz.flos.core.TableLink>> = _tableLinks.asStateFlow()

    private fun boClient(s: AppSettings = settings.value) = cz.flos.core.backoffice.BackOfficeClient(s.boUrl, s.boToken)

    /** Fetches new orders from the tables; asks the staff or puts them straight on the bill. */
    private suspend fun pollTableOrders() {
        val s = settings.value
        val orders = runCatching { withContext(Dispatchers.IO) { boClient(s).tableOrders() } }.getOrNull() ?: return
        val fresh = orders.filter { qrSeen.add(it.id) }
        if (fresh.isEmpty()) return
        newOrderRing()
        if (s.qrOrderApprove) _qrInbox.update { it + fresh }
        else fresh.forEach { acceptTableOrder(it).join() }
    }

    /** The order's lines as cart lines priced by this till; lines that no longer match a product are left out. */
    fun tableOrderCart(o: cz.flos.core.TableOrder, service: String? = null): Pair<Cart, List<String>> {
        val byId = products.value.associateBy { it.id }
        var cart = Cart()
        val missing = mutableListOf<String>()
        for (l in o.lines) {
            val p = byId[l.id]
            if (p == null || !p.active || p.openPrice) { missing += "#${l.id}"; continue }
            val groups = optionGroupsFor(p)
            if (!cz.flos.core.TableOrders.choicesValid(groups, l.choices) || l.qty !in 1..cz.flos.core.TableOrders.MAX_QTY) { missing += p.name; continue }
            val (name, price) = cz.flos.core.ProductOptions.apply(p.name, p.price, groups, cz.flos.core.TableOrders.chosen(l.choices))
            val (unit, auto) = priced(p, extras = price - p.price, service = service, list = null)
            cart = cart.add(p.id, cz.flos.core.LineNote.join(name, cz.flos.core.TableOrders.cleanNote(l.note)), unit, p.vatRate, l.qty.toDouble(), auto)
        }
        return cart to missing
    }

    /**
     * Takes an order from a table: tells the back office first (so no other till takes it too), then adds
     * the dishes to the table's open bill, or opens a new bill, and prints a ticket for the kitchen.
     */
    fun acceptTableOrder(o: cz.flos.core.TableOrder) = viewModelScope.launch {
        if (!qrTaking.add(o.id)) return@launch // a second tap while the first one is still running
        qrMutex.lock()
        try {
            val existing = openOrders.value.firstOrNull { it.sale.tableName == o.table }
            val (lines, missing) = tableOrderCart(o, existing?.sale?.serviceType)
            if (lines.isEmpty()) {
                _qrInbox.update { list -> list.filterNot { it.id == o.id } }
                runCatching { withContext(Dispatchers.IO) { boClient().ackTableOrder(o.id, false) } }
                _messages.tryEmit(tr("Objednávku ze stolu %s nelze přijmout: položky už nejsou v nabídce. Zveřejněte menu znovu.", o.table))
                return@launch
            }
            val taken = runCatching { withContext(Dispatchers.IO) { boClient().ackTableOrder(o.id, true) } }
            if (taken.isFailure) {
                // Not confirmed: stays in the list (or is fetched again) and can be tried once more.
                if (!settings.value.qrOrderApprove) qrSeen.remove(o.id)
                _messages.tryEmit(tr("Objednávku ze stolu %s se nepodařilo potvrdit: %s", o.table, taken.exceptionOrNull()?.message ?: ""))
                return@launch
            }
            _qrInbox.update { list -> list.filterNot { it.id == o.id } }
            if (taken.getOrNull() != true) { _messages.tryEmit(tr("Objednávku ze stolu %s už vyřídila jiná pokladna.", o.table)); return@launch }

            val note = listOf(tr("QR objednávka"), o.note.trim()).filter { it.isNotEmpty() }.joinToString(": ")
            val sale = if (existing != null) {
                var cart = existing.toCart()
                lines.items.forEach { cart = cart.add(it.productId, it.name, it.unitPrice, it.vatRate, it.quantity, it.discountPercent) }
                val joinedNote = listOf(existing.sale.note, o.note.trim()).filter { it.isNotBlank() }.joinToString(" · ")
                val updated = existing.sale.copy(total = cart.total, received = cart.total, kitchenReadyAt = null, note = joinedNote)
                salesDao.replace(updated, itemsOf(cart))
                // The same bill is open in the cart: show the new dishes there too, so saving it keeps them.
                if (_editingOrder.value?.id == existing.sale.id) {
                    _cart.update { c -> lines.items.fold(c) { acc, it -> acc.add(it.productId, it.name, it.unitPrice, it.vatRate, it.quantity, it.discountPercent) } }
                    _editingOrder.value = updated
                }
                updated
            } else {
                val millis = System.currentTimeMillis()
                SaleEntity(
                    number = "O-$millis", createdAt = millis, openedAt = millis, total = lines.total, received = lines.total,
                    payment = "", cashier = settings.value.cashier, status = SaleStatus.OPEN.name,
                    orderNumber = flos.settings.nextOrderNumber(), note = note, tableName = o.table,
                ).let { it.copy(id = salesDao.insert(it, itemsOf(lines))) }
            }
            _messages.tryEmit(tr("Objednávka ze stolu %s přijata", o.table) +
                (if (missing.isNotEmpty()) " " + tr("(vynecháno: %s)", missing.joinToString(", ")) else ""))
            if (settings.value.qrOrderPrint) printOrder(sale.copy(note = note), lines)
        } finally { qrMutex.unlock(); qrTaking.remove(o.id) }
    }

    /** Declines an order from a table; the guest's page says to ask the staff. */
    fun rejectTableOrder(o: cz.flos.core.TableOrder) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { boClient().ackTableOrder(o.id, false) } }
            .onSuccess { _qrInbox.update { list -> list.filterNot { it.id == o.id } } }
            .onFailure { _messages.tryEmit(tr("Odmítnutí se nepodařilo odeslat: %s", it.message ?: "")) }
    }

    // ---- Orders from the website: delivery and pick-up (Pokročilé nastavení → Objednávky z webu) ----
    private val _webInbox = MutableStateFlow<List<cz.flos.core.WebOrderInbox>>(emptyList())
    /** Web orders waiting for the staff to accept (with a ready time) or decline. */
    val webInbox: StateFlow<List<cz.flos.core.WebOrderInbox>> = _webInbox.asStateFlow()
    private val webSeen = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    init {
        // The ring stops once every waiting order from the tables and the website is answered.
        viewModelScope.launch {
            var before = 0
            kotlinx.coroutines.flow.combine(_qrInbox, _webInbox) { a, b -> a.size + b.size }.collect { n ->
                if (before > 0 && n == 0) stopRinging()
                before = n
            }
        }
    }
    private val webTaking = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    /** Fetches new web orders; the staff always confirm them, because the customer waits for a time. */
    private suspend fun pollWebOrders() {
        val orders = runCatching { withContext(Dispatchers.IO) { boClient().webOrders() } }.getOrNull() ?: return
        val fresh = orders.filter { webSeen.add(it.id) }
        if (fresh.isEmpty()) return
        newOrderRing()
        _webInbox.update { it + fresh }
    }

    /**
     * The web order as cart lines priced by this till: pizzas matched to products by number or name
     * (see [cz.flos.core.WebOrders.match]), half & half and extras by the restaurant's rule, and the
     * delivery fee. Lines without a matching product keep the website's price; their names are returned.
     */
    fun webOrderCart(w: cz.flos.core.WebOrderInbox): Pair<Cart, List<String>> {
        val o = w.order
        val prods = products.value.filter { it.active && !it.openPrice }
        fun find(n: Int, name: String) = cz.flos.core.WebOrders.match(n, name, prods) { cz.flos.core.WebOrders.Candidate(it.id, it.name, it.barcode) }
        var cart = Cart()
        val unmatched = mutableListOf<String>()
        var vat: VatRate? = null
        for (l in o.lines) {
            val a = find(l.number, l.name)
            val b = l.half?.let { find(it, l.halfName) }
            val name = cz.flos.core.WebOrders.lineName(l, a?.name ?: l.name, b?.name ?: l.halfName)
            if (a != null && (l.half == null || b != null)) {
                val unit = cz.flos.core.WebOrders.unitPrice(a.price, b?.price, l.extras)
                cart = cart.add(a.id, name, unit, a.vatRate, l.qty.toDouble())
                if (vat == null) vat = a.vatRate
            } else {
                unmatched += if (l.half != null) "${l.name} / ${l.halfName}" else l.name
                cart = cart.add(MANUAL_ITEM_ID, name, l.unitPrice * 100, a?.vatRate ?: VatRate.fromPercent(12), l.qty.toDouble())
            }
        }
        if (o.type == cz.flos.core.WebOrders.DELIVERY && o.deliveryFee > 0) {
            cart = cart.add(MANUAL_ITEM_ID, tr("Rozvoz"), o.deliveryFee * 100, vat ?: VatRate.fromPercent(12), 1.0)
        }
        return cart to unmatched
    }

    /**
     * Accepts a web order: tells the back office first (the customer's page shows the ready time and no
     * other till takes it), then saves it as an open order and prints the ticket with the customer's
     * details. The details are only printed, not stored on the till (see the privacy policy).
     */
    /** Whether accepting a web order also opens WhatsApp with the confirmation for the customer (kept while the app runs). */
    val webWhatsApp = MutableStateFlow(true)

    fun acceptWebOrder(w: cz.flos.core.WebOrderInbox, readyMinutes: Int, whatsapp: Boolean = false, readyAt: Long? = null) = viewModelScope.launch {
        if (!webTaking.add(w.id)) return@launch
        if (w.order.type == cz.flos.core.WebOrders.TABLE) { try { acceptWebTableOrder(w, readyMinutes) } finally { webTaking.remove(w.id) }; return@launch }
        try {
            val (lines, unmatched) = webOrderCart(w)
            val taken = runCatching { withContext(Dispatchers.IO) { boClient().ackWebOrder(w.id, true, readyMinutes, readyAt) } }
            if (taken.isFailure) {
                _messages.tryEmit(tr("Objednávku z webu se nepodařilo potvrdit: %s", taken.exceptionOrNull()?.message ?: ""))
                return@launch
            }
            _webInbox.update { list -> list.filterNot { it.id == w.id } }
            if (taken.getOrNull() != true) { _messages.tryEmit(tr("Objednávku z webu už vyřídila jiná pokladna.")); return@launch }
            val o = w.order
            // A pre-order: its day and time; otherwise the clock time in the chosen minutes.
            val at = readyAt?.let { java.time.Instant.ofEpochMilli(it).atZone(cz.flos.core.OrderHours.ZONE).toLocalDateTime() }
            val ready = if (at != null) cz.flos.core.WebOrders.dayText(at.toLocalDate(), o.lang == "en") + " " + at.format(java.time.format.DateTimeFormatter.ofPattern("H:mm"))
                else LocalDateTime.now().plusMinutes(readyMinutes.toLong()).format(java.time.format.DateTimeFormatter.ofPattern("H:mm"))
            val kind = if (o.type == cz.flos.core.WebOrders.DELIVERY) tr("Rozvoz") else tr("Osobní odběr")
            val note = "WEB ${w.id} · $kind · " + (if (at != null) tr("PŘEDOBJEDNÁVKA") + " " else "") + tr("hotovo %s", ready) + " · " + tr(cz.flos.core.WebOrders.paymentText(o.payment))
            val millis = System.currentTimeMillis()
            val sale = SaleEntity(
                number = "O-$millis", createdAt = millis, openedAt = millis, total = lines.total, received = lines.total,
                payment = "", cashier = settings.value.cashier, status = SaleStatus.OPEN.name,
                orderNumber = flos.settings.nextOrderNumber(), note = note,
            ).let { it.copy(id = salesDao.insert(it, itemsOf(lines))) }
            // The confirmation goes from the shop's own WhatsApp; the cashier only presses Send.
            val phone = o.customer?.phone.orEmpty()
            if (whatsapp && cz.flos.core.Notify.phoneDigits(phone) != null) {
                val s = settings.value
                sendMessage("whatsapp", phone, cz.flos.core.WebOrders.confirmation(w.id, o, ready, s.businessName, s.phone))
            }
            _messages.tryEmit(tr("Objednávka z webu přijata, hotovo v %s", ready) +
                (if (unmatched.isNotEmpty()) " " + tr("(cena z webu: %s)", unmatched.joinToString(", ")) else ""))
            // The ticket carries the name, phone and address for the kitchen and the driver.
            val ticket = (listOf(note) + cz.flos.core.WebOrders.summary(w.id, o).drop(1)).joinToString("\n")
            printOrder(sale.copy(note = ticket), lines)
        } finally { webTaking.remove(w.id) }
    }

    /**
     * A guest's order from a table on the restaurant's website: tells the back office first, then adds the
     * dishes to the table's open bill (or opens one), like an order from the Flos QR menu, and prints the
     * kitchen ticket when that is switched on.
     */
    private suspend fun acceptWebTableOrder(w: cz.flos.core.WebOrderInbox, readyMinutes: Int) = qrMutex.withLock {
        val o = w.order
        val table = o.table
        val existing = openOrders.value.firstOrNull { it.sale.tableName == table }
        val (lines, unmatched) = webOrderCart(w)
        val taken = runCatching { withContext(Dispatchers.IO) { boClient().ackWebOrder(w.id, true, readyMinutes) } }
        if (taken.isFailure) {
            _messages.tryEmit(tr("Objednávku ze stolu %s se nepodařilo potvrdit: %s", table, taken.exceptionOrNull()?.message ?: ""))
            return@withLock
        }
        _webInbox.update { list -> list.filterNot { it.id == w.id } }
        if (taken.getOrNull() != true) { _messages.tryEmit(tr("Objednávku ze stolu %s už vyřídila jiná pokladna.", table)); return@withLock }
        val note = listOf(tr("QR objednávka") + " (web)", o.note.trim()).filter { it.isNotEmpty() }.joinToString(": ")
        val sale = withContext(Dispatchers.IO) {
            if (existing != null) {
                var cart = existing.toCart()
                lines.items.forEach { cart = cart.add(it.productId, it.name, it.unitPrice, it.vatRate, it.quantity, it.discountPercent) }
                val joinedNote = listOf(existing.sale.note, o.note.trim()).filter { it.isNotBlank() }.joinToString(" · ")
                val updated = existing.sale.copy(total = cart.total, received = cart.total, kitchenReadyAt = null, note = joinedNote)
                salesDao.replace(updated, itemsOf(cart))
                updated
            } else {
                val millis = System.currentTimeMillis()
                SaleEntity(
                    number = "O-$millis", createdAt = millis, openedAt = millis, total = lines.total, received = lines.total,
                    payment = "", cashier = settings.value.cashier, status = SaleStatus.OPEN.name,
                    orderNumber = flos.settings.nextOrderNumber(), note = note, tableName = table,
                ).let { it.copy(id = salesDao.insert(it, itemsOf(lines))) }
            }
        }
        // The same bill is open in the cart: show the new dishes there too, so saving it keeps them.
        if (existing != null && _editingOrder.value?.id == existing.sale.id) {
            _cart.update { c -> lines.items.fold(c) { acc, it -> acc.add(it.productId, it.name, it.unitPrice, it.vatRate, it.quantity, it.discountPercent) } }
            _editingOrder.value = sale
        }
        _messages.tryEmit(tr("Objednávka ze stolu %s přijata", table) +
            (if (unmatched.isNotEmpty()) " " + tr("(cena z webu: %s)", unmatched.joinToString(", ")) else ""))
        if (settings.value.qrOrderPrint) printOrder(sale.copy(note = "WEB ${w.id} · " + tr("Stůl %s", table) + (if (o.note.isNotBlank()) "\n" + o.note.trim() else "")), lines)
    }

    /** Declines a web order; the customer's page asks them to call. */
    fun rejectWebOrder(w: cz.flos.core.WebOrderInbox) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { boClient().ackWebOrder(w.id, false) } }
            .onSuccess { _webInbox.update { list -> list.filterNot { it.id == w.id } } }
            .onFailure { _messages.tryEmit(tr("Odmítnutí se nepodařilo odeslat: %s", it.message ?: "")) }
    }

    /** Loads the QR code link of every table from the back office (the menu must be published). */
    fun loadTableLinks() = viewModelScope.launch {
        val s = settings.value
        if (!s.paired) { _messages.tryEmit(tr("Nejdřív spárujte pokladnu s back office.")); return@launch }
        if (s.tableList.isEmpty()) { _messages.tryEmit(tr("Nejdřív zadejte stoly v Nastavení.")); return@launch }
        runCatching { withContext(Dispatchers.IO) { boClient(s).tableLinks(s.tableList) } }
            .onSuccess { _tableLinks.value = it }
            .onFailure { _messages.tryEmit(tr("Odkazy stolů se nepodařilo načíst: %s", it.message ?: "")) }
    }

    /** The address in a table's QR code: the Flos menu, or the restaurant's own website when it is set (tableMenuUrl). */
    fun tableQrLink(l: cz.flos.core.TableLink): String = cz.flos.core.TableOrders.websiteLink(settings.value.tableMenuUrl, l.path)

    /** Prints a QR code for every table on the receipt printer, to stand on the tables. */
    fun printTableQrs() {
        val links = _tableLinks.value
        if (links.isEmpty()) return
        val name = settings.value.businessName
        printBlocks(links.flatMap { cz.flos.core.MenuPage.poster(name, tableQrLink(it), tr("Objednejte zde"), it.table) }, tr("QR kódy stolů vytištěny"))
    }

    /** Prints the QR code of one table (long press on the table): loads its signed link from the back office first. */
    fun printTableQr(table: String) = viewModelScope.launch {
        val s = settings.value
        if (!s.paired) { _messages.tryEmit(tr("Nejdřív spárujte pokladnu s back office.")); return@launch }
        runCatching { withContext(Dispatchers.IO) { boClient(s).tableLinks(listOf(table)) } }
            .onSuccess { links ->
                val l = links.firstOrNull() ?: return@onSuccess
                printBlocks(cz.flos.core.MenuPage.poster(s.businessName, tableQrLink(l), tr("Objednejte zde"), l.table), tr("QR kód stolu %s vytištěn", l.table))
            }
            .onFailure { _messages.tryEmit(tr("Odkazy stolů se nepodařilo načíst: %s", it.message ?: "")) }
    }

    /** Loads an open order into the cart so items can be added or removed. */
    fun editOrder(sw: SaleWithItems) {
        _cart.value = sw.toCart()
        _editingOrder.value = sw.sale
        _pendingTable.value = null
        _customer.value = null
        _serviceType.value = sw.sale.serviceType
        _priceList.value = null
        sw.sale.customerId?.let { id -> viewModelScope.launch { _customer.value = customerDao.get(id) } }
    }

    /** Cancels an unpaid order. It stays in history as cancelled; nothing is sent to EET. */
    fun cancelOrder(sw: SaleWithItems, reason: String) = viewModelScope.launch {
        if (!allow(cz.flos.core.Permission.CANCEL_ORDER)) return@launch
        audit(tr("Zrušena objednávka č. %d (%s)", sw.sale.orderNumber ?: 0, Money.format(sw.sale.total)), reason)
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
        val note = listOfNotNull(sw.sale.serviceType, sw.sale.tableName?.let { tr("Stůl %s", it) }, sw.sale.note.takeIf { it.isNotBlank() }).joinToString(" · ")
        return Receipt(sw.sale.number, created, sw.sale.cashier, sw.toCart(), payment, sw.sale.cashGiven, qr.takeIf { refundOf == null }, copy,
            eet = record?.let(::eetInfo), note = note, epc = epc, eurRate = epc?.let { sw.sale.eurRate }, refundOf = refundOf,
            parts = if (sw.sale.isMixed) sw.sale.parts else emptyList())
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
    fun cancelSale(sw: SaleWithItems, reason: String = "") = viewModelScope.launch {
        if (!allow(cz.flos.core.Permission.STORNO)) return@launch
        audit(tr("Storno účtenky %s (%s)", sw.sale.number, Money.format(sw.sale.received)), reason)
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
    fun refund(sw: SaleWithItems, quantities: Map<Int, Double>, reason: String = "") = viewModelScope.launch {
        if (!allow(cz.flos.core.Permission.REFUND)) return@launch
        if (!sw.sale.isPaid || sw.sale.cancelled || sw.sale.isRefund) return@launch
        audit(tr("Vratka z účtenky %s", sw.sale.number) + ": " + quantities.entries.joinToString(", ") { (i, q) ->
            Qty.format(q) + "× " + (sw.items.getOrNull(i)?.name ?: "?") }, reason)
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
            val bytes = EscPosEncoder(s.paperWidth, s.charset, nativeQr = s.printerNativeQr).encode(blocks, openDrawer = drawer)
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
        if (!allow(cz.flos.core.Permission.CASH_MOVES)) return@launch
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
        if (!allow(cz.flos.core.Permission.CLOSE_SHIFT)) return@launch
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
        salesDao.productByBarcode(code.trim())?.let { addToCart(it); return true }
        // A label from the shop scale: product code + weight or price inside the EAN.
        val label = cz.flos.core.ScaleBarcode.parse(code, settings.value.scale) ?: return false
        val p = cz.flos.core.ScaleBarcode.pluCandidates(label).firstNotNullOfOrNull { salesDao.productByBarcode(it) } ?: return false
        val (unit, auto) = priced(p)
        val qty = cz.flos.core.ScaleBarcode.quantity(label, p.price)
        if (qty < Qty.MIN) return false
        _cart.update { it.add(p.id, p.name, unit, p.vatRate, qty, auto) }
        return true
    }

    // ---- Catalogue ----
    // Every change made here is marked, so the back office gets it with the next sync (the later change wins).
    private fun ProductEntity.edited() = copy(dirty = true, updatedAt = System.currentTimeMillis())
    private fun CategoryEntity.edited() = copy(dirty = true, updatedAt = System.currentTimeMillis())

    fun saveProduct(p: ProductEntity) = viewModelScope.launch { if (allow(cz.flos.core.Permission.PRODUCTS)) catalog.upsertProduct(p.edited()) }
    fun deleteProduct(p: ProductEntity) = viewModelScope.launch {
        if (!allow(cz.flos.core.Permission.PRODUCTS)) return@launch
        val current = catalog.allProducts().firstOrNull { it.id == p.id } ?: p
        catalog.upsertProduct(current.copy(active = false).edited())
    }
    fun saveCategory(c: CategoryEntity) = viewModelScope.launch { if (allow(cz.flos.core.Permission.PRODUCTS)) catalog.upsertCategory(c.edited()) }
    fun deleteCategory(c: CategoryEntity) = viewModelScope.launch {
        if (!allow(cz.flos.core.Permission.PRODUCTS)) return@launch
        catalog.clearCategory(c.id)
        catalog.deleteCategory(c.id)
        c.remoteId?.let { id -> flos.settings.update { s -> s.copy(boDeletedCategories = (s.boDeletedCategories.split(',').filter { it.isNotBlank() } + id.toString()).joinToString(",")) } }
    }

    /** What is going on at this till, for the back office to show (Pokročilé nastavení → Přehled pokladny v back office). */
    // Reads the database directly: this runs from init, before the properties further down exist.
    private suspend fun tillSnapshot(): cz.flos.core.backoffice.BoTillSnapshot {
        val max = cz.flos.core.backoffice.BoTillSnapshot.MAX_LIST
        val since = cashDao.lastClose().first()
        val moves = cashDao.since(since).first()
        val cashSales = salesDao.cashReceivedSince(since).first()
        val cashRefunds = salesDao.cashRefundedSince(since).first()
        val cash = CashState(since, moves.filter { it.cashKind == CashKind.FLOAT }.sumOf { it.amount }, cashSales, cashRefunds,
            moves.filter { it.cashKind == CashKind.IN }.sumOf { it.amount }, moves.filter { it.cashKind == CashKind.OUT }.sumOf { it.amount })
        return cz.flos.core.backoffice.BoTillSnapshot(
            takenAt = System.currentTimeMillis(),
            openOrders = salesDao.openOrders().first().take(max).map { o ->
                cz.flos.core.backoffice.BoOpenOrder(o.sale.orderNumber, o.sale.tableName, o.sale.note, o.sale.openedAt ?: o.sale.createdAt, o.toCart().total,
                    o.sale.cashier, o.sale.serviceType, o.sale.kitchenReadyAt != null,
                    o.items.map { cz.flos.core.backoffice.BoOrderLine(it.name, it.quantity, it.unitPrice, it.discountPercent) })
            },
            cash = cz.flos.core.backoffice.BoCashState(cash.since, cash.float, cash.sales, cash.refunds, cash.cashIn, cash.cashOut, cash.expected),
            shiftCloses = cashDao.closes(30).first().map { cz.flos.core.backoffice.BoShiftClose(it.at, it.amount, it.expected, it.cashier) },
            cashMoves = moves.takeLast(max).map { cz.flos.core.backoffice.BoCashMove(it.at, it.kind, it.amount, it.note, it.cashier) },
            customers = customerDao.all().first().take(cz.flos.core.backoffice.BoTillSnapshot.MAX_CUSTOMERS).map {
                cz.flos.core.backoffice.BoCustomer(it.name, it.phone, it.email, it.points, it.discountPercent, it.birthday, it.note, it.cardCode)
            },
            reservations = flos.database.reservations().from(System.currentTimeMillis() - 86_400_000L).first().take(max).map {
                cz.flos.core.backoffice.BoReservation(it.at, it.name, it.phone, it.guests, it.minutes, it.tableName, it.note, it.status)
            },
            documents = flos.database.documents().all().first().take(max).map {
                cz.flos.core.backoffice.BoDocument(it.kind, it.number, it.createdAt, it.customerName, it.total, it.status, it.dueDay, it.note)
            },
            audit = flos.database.notes().of(cz.flos.pos.data.NoteTarget.AUDIT.name, null).first().take(max).map { cz.flos.core.backoffice.BoAuditEntry(it.createdAt, it.text, it.author) },
            eetWaiting = eetDao.open().first().size,
        )
    }

    /** The photo of a product from the back office, when it is downloaded. */
    fun productImage(ref: String?): ByteArray? = flos.backOffice.imageBytes(ref)

    /** Marks everything made on this till before it was paired, so it is sent to the back office once. */
    fun uploadLocalCatalog() = viewModelScope.launch {
        if (!allow(cz.flos.core.Permission.PRODUCTS)) return@launch
        val now = System.currentTimeMillis()
        catalog.markLocalCategoriesForUpload(now)
        catalog.markLocalProductsForUpload(now)
        syncBackOffice(silent = false)
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) = flos.settings.update(transform)

    // ---- Quotations, pro forma invoices, customer orders ----
    private val documentDao = flos.database.documents()
    val documents = documentDao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Saves the cart as a quotation, pro forma invoice or customer order and empties the cart.
     * Nothing is stored as a sale and nothing goes to EET.
     */
    fun saveDocument(kind: DocumentKind, note: String, due: LocalDate?, print: Boolean) {
        val cart = _cart.value
        if (cart.isEmpty() || !allow(cz.flos.core.Permission.DOCUMENTS)) return
        val customer = _customer.value
        clearCart()
        viewModelScope.launch {
            val now = LocalDateTime.now()
            val number = cz.flos.core.DocumentNumbers.next(kind, now.year, documentDao.numbers(kind.name))
            val doc = cz.flos.pos.data.DocumentEntity(
                kind = kind.name, number = number, createdAt = nowMillis(now), customerId = customer?.id, customerName = customer?.name.orEmpty(),
                items = cz.flos.core.CartText.encode(cart), total = cart.total, note = note.trim(), dueDay = due?.toEpochDay(), cashier = settings.value.cashier,
            )
            val id = documentDao.upsert(doc)
            _messages.tryEmit(tr("Doklad %s uložen", number))
            if (print) printDocument(doc.copy(id = id))
        }
    }

    fun documentReceipt(d: cz.flos.pos.data.DocumentEntity): Receipt {
        val s = settings.value
        val created = LocalDateTime.ofInstant(Instant.ofEpochMilli(d.createdAt), ZoneId.systemDefault())
        val qr = if (d.docKind == DocumentKind.PROFORMA) qrFor(d.total, cz.flos.core.DocumentNumbers.variableSymbol(d.number), "${s.businessName} ${d.number}") else null
        val note = listOf(d.customerName.takeIf { it.isNotBlank() }?.let { "Zákazník: $it" }, d.note.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · ")
        return Receipt(d.number, created, d.cashier, d.cart(), PaymentMethod.QR_TRANSFER, qr = qr, note = note, kind = d.docKind, validUntil = d.due)
    }

    fun documentPreview(d: cz.flos.pos.data.DocumentEntity): String {
        val s = settings.value
        return ReceiptLayout.toPlainText(ReceiptLayout(s.paperWidth.chars).layout(s.business, documentReceipt(d)), s.paperWidth.chars)
    }

    fun printDocument(d: cz.flos.pos.data.DocumentEntity) {
        val s = settings.value
        printBlocks(ReceiptLayout(s.paperWidth.chars).layout(s.business, documentReceipt(d)), tr("Doklad %s vytištěn", d.number))
    }

    /** Puts the document's items into the cart to be sold, and marks the document as done. */
    fun sellDocument(d: cz.flos.pos.data.DocumentEntity) {
        clearCart()
        _cart.value = d.cart()
        d.customerId?.let { id -> viewModelScope.launch { _customer.value = customerDao.get(id) } }
        viewModelScope.launch { documentDao.setStatus(d.id, cz.flos.pos.data.DocStatus.DONE.name) }
        _messages.tryEmit(tr("Položky dokladu %s jsou v košíku", d.number))
    }

    /** Puts the items into the cart without closing the document (to make a new version). */
    fun copyDocument(d: cz.flos.pos.data.DocumentEntity) {
        clearCart()
        _cart.value = d.cart()
        d.customerId?.let { id -> viewModelScope.launch { _customer.value = customerDao.get(id) } }
        _messages.tryEmit(tr("Položky dokladu %s jsou v košíku", d.number))
    }

    fun setDocumentStatus(d: cz.flos.pos.data.DocumentEntity, status: cz.flos.pos.data.DocStatus) =
        viewModelScope.launch { documentDao.setStatus(d.id, status.name) }

    fun deleteDocument(d: cz.flos.pos.data.DocumentEntity) = viewModelScope.launch { documentDao.delete(d.id) }

    // ---- Table reservations ----
    private val reservationDao = flos.database.reservations()
    /** Reservations from yesterday on. */
    val reservations = reservationDao.from(nowMillis(LocalDate.now().minusDays(1).atStartOfDay()))
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun saveReservation(r: cz.flos.pos.data.ReservationEntity) = viewModelScope.launch {
        reservationDao.upsert(if (r.createdAt == 0L) r.copy(createdAt = System.currentTimeMillis()) else r)
        _messages.tryEmit(tr("Rezervace %s uložena", r.name))
    }

    fun setReservationStatus(r: cz.flos.pos.data.ReservationEntity, status: cz.flos.pos.data.BookingStatus) =
        viewModelScope.launch { reservationDao.setStatus(r.id, status.name) }

    fun deleteReservation(r: cz.flos.pos.data.ReservationEntity) = viewModelScope.launch { reservationDao.delete(r.id) }

    /** Other reservations of the same table at the same time. */
    fun reservationClashes(r: cz.flos.pos.data.ReservationEntity): List<cz.flos.pos.data.ReservationEntity> {
        val all = reservations.value
        val ids = cz.flos.core.Bookings.conflicts(r.booking().copy(active = true), all.map { it.booking() }).map { it.id }.toSet()
        return all.filter { it.id in ids }
    }

    /** Guests arrived: the reservation is marked seated and a new bill starts for its table. */
    fun seatReservation(r: cz.flos.pos.data.ReservationEntity): Boolean {
        setReservationStatus(r, cz.flos.pos.data.BookingStatus.SEATED)
        val table = r.tableName ?: return false
        val open = openOrders.value.firstOrNull { it.sale.tableName == table }
        if (open != null) editOrder(open) else startTable(table)
        return true
    }

    // ---- Products: Excel import/export, bulk edit, labels ----
    /** All products (also hidden ones) as table rows, for Excel. */
    suspend fun productTable(): List<List<Any?>> {
        val cats = categories.value.associate { it.id to it.name }
        return cz.flos.core.ProductSheet.export(catalog.allProducts().sortedBy { it.name.lowercase() }.map { it.toRow(cats) })
    }

    private fun ProductEntity.toRow(cats: Map<Long, String>) = cz.flos.core.ProductRow(
        name, price, vatPercent, categoryId?.let { cats[it] }.orEmpty(), barcode.orEmpty(), active, options.orEmpty(), prices.orEmpty(),
    )

    suspend fun exportProducts(xlsx: Boolean): ByteArray {
        val table = productTable()
        return if (xlsx) cz.flos.core.Xlsx.write("Produkty", table) else cz.flos.core.Csv.write(table)
    }

    /** What importing [bytes] (xlsx or csv) would do; nothing is changed yet. */
    suspend fun previewImport(bytes: ByteArray): Pair<cz.flos.core.ImportPlan<Long>, List<String>> {
        val sheet = runCatching { cz.flos.core.ProductSheet.parse(cz.flos.core.Spreadsheet.read(bytes)) }
            .getOrElse { cz.flos.core.SheetResult(emptyList(), listOf(tr("Soubor se nepodařilo přečíst: %s", it.message ?: ""))) }
        val cats = categories.value.associate { it.id to it.name }
        val plan = cz.flos.core.ProductSheet.plan(catalog.allProducts().map { it.id to it.toRow(cats) }, sheet.rows)
        return plan to sheet.errors
    }

    /** Adds the new products and updates the changed ones; icons, order and back office links stay. */
    fun applyImport(plan: cz.flos.core.ImportPlan<Long>) = viewModelScope.launch {
        if (!allow(cz.flos.core.Permission.PRODUCTS)) return@launch
        val cats = categories.value.associateBy { cz.flos.core.Pricing.key(it.name) }.toMutableMap()
        suspend fun categoryId(name: String): Long? {
            if (name.isBlank()) return null
            cats[cz.flos.core.Pricing.key(name)]?.let { return it.id }
            val c = CategoryEntity(name = name.trim(), sortOrder = cats.size).edited()
            val id = catalog.upsertCategory(c)
            cats[cz.flos.core.Pricing.key(name)] = c.copy(id = id)
            return id
        }
        val existing = catalog.allProducts().associateBy { it.id }
        plan.added.forEach { r ->
            catalog.upsertProduct(ProductEntity(name = r.name, price = r.price, vatPercent = r.vatPercent, categoryId = categoryId(r.category), active = r.active,
                barcode = r.barcode.ifBlank { null }, options = r.options.ifBlank { null }, prices = r.prices.ifBlank { null }).edited())
        }
        plan.changed.forEach { (id, r) ->
            val p = existing[id] ?: return@forEach
            catalog.upsertProduct(p.copy(name = r.name, price = r.price, vatPercent = r.vatPercent, categoryId = categoryId(r.category), active = r.active,
                barcode = r.barcode.ifBlank { null }, options = r.options.ifBlank { null }, prices = r.prices.ifBlank { null }).edited())
        }
        _messages.tryEmit(tr("Import hotový: %d nových, %d změněných", plan.added.size, plan.changed.size))
    }

    /** All products, also the hidden ones, for bulk edit. */
    suspend fun allProducts(): List<ProductEntity> = catalog.allProducts()

    /** Applies [change] to the products with [ids]. */
    fun bulkEdit(ids: Set<Long>, label: String, change: (ProductEntity) -> ProductEntity) = viewModelScope.launch {
        if (!allow(cz.flos.core.Permission.PRODUCTS)) return@launch
        val list = catalog.allProducts().filter { it.id in ids }
        list.forEach { catalog.upsertProduct(change(it).edited()) }
        _messages.tryEmit(tr("%s: upraveno %d produktů", label, list.size))
    }

    /** Prints price labels on the receipt printer. */
    fun printLabels(products: List<ProductEntity>, copies: Int, unitNote: String, withShop: Boolean) {
        val s = settings.value
        val blocks = products.flatMap { p ->
            cz.flos.core.Labels.layout(cz.flos.core.PriceLabel(p.name, p.price, p.barcode, unitNote.trim(), if (withShop) s.businessName else ""), copies)
        }
        if (blocks.isNotEmpty()) printBlocks(blocks, tr("Vytištěno štítků: %d", products.size * copies.coerceIn(1, 100)))
    }

    // ---- Notes and documents ----
    private val noteDao = flos.database.notes()
    private val attachmentsDir get() = java.io.File(platform.dataDir, "attachments").apply { mkdirs() }

    fun notes(target: cz.flos.pos.data.NoteTarget, id: Long?): Flow<List<cz.flos.pos.data.NoteEntity>> = noteDao.of(target.name, id)
    val allNotes = noteDao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Storno, refunds and cancelled orders with who, when and why (Pokročilé nastavení → Důvod storna a záznam). */
    val auditLog = noteDao.of(cz.flos.pos.data.NoteTarget.AUDIT.name, null).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private suspend fun audit(what: String, reason: String) {
        if (!settings.value.on(cz.flos.pos.data.AdvFeature.STORNO_REASON)) return
        val text = what + (reason.trim().takeIf { it.isNotEmpty() }?.let { "\n" + tr("Důvod: %s", it) } ?: "")
        noteDao.upsert(cz.flos.pos.data.NoteEntity(target = cz.flos.pos.data.NoteTarget.AUDIT.name, text = text,
            createdAt = System.currentTimeMillis(), author = settings.value.cashier))
    }

    /** Saves a note; [file] is an attached document with the name to show. */
    fun addNote(target: cz.flos.pos.data.NoteTarget, id: Long?, text: String, file: Pair<String, ByteArray>?) = viewModelScope.launch {
        val stored = file?.let { (title, bytes) ->
            val name = "${System.currentTimeMillis()}-" + title.filter { it.isLetterOrDigit() || it in "._-" }.ifBlank { "soubor" }.take(60)
            val saved = runCatching { withContext(Dispatchers.IO) { java.io.File(attachmentsDir, name).writeBytes(bytes) } }.isSuccess
            if (!saved) { _messages.tryEmit(tr("Dokument se nepodařilo uložit")); return@launch }
            name
        }
        noteDao.upsert(cz.flos.pos.data.NoteEntity(target = target.name, targetId = id, text = text.trim(), createdAt = System.currentTimeMillis(),
            author = settings.value.cashier, fileName = stored, fileTitle = file?.first))
    }

    fun deleteNote(n: cz.flos.pos.data.NoteEntity) = viewModelScope.launch {
        n.fileName?.let { withContext(Dispatchers.IO) { java.io.File(attachmentsDir, it).delete() } }
        noteDao.delete(n.id)
    }

    suspend fun attachment(n: cz.flos.pos.data.NoteEntity): ByteArray? =
        n.fileName?.let { withContext(Dispatchers.IO) { java.io.File(attachmentsDir, it).takeIf { f -> f.exists() }?.readBytes() } }

    // ---- Messages to customers ----
    private fun baseValues(): Map<String, String> {
        val s = settings.value
        return mapOf("provozovna" to s.businessName, "telefon" to s.phone)
    }

    fun reservationMessage(r: cz.flos.pos.data.ReservationEntity, reminder: Boolean): String {
        val s = settings.value
        val at = r.start
        return cz.flos.core.Notify.fill(if (reminder) s.msgReminder else s.msgReservation, baseValues() + mapOf(
            "jmeno" to r.name, "datum" to at.toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("d.M.yyyy")),
            "cas" to at.format(java.time.format.DateTimeFormatter.ofPattern("H:mm")), "hoste" to r.guests.toString(), "stul" to r.tableName.orEmpty(),
        ))
    }

    /** Text for a quotation / pro forma / customer order; a ready customer order gets the "ready for pick-up" text. */
    fun documentMessage(d: cz.flos.pos.data.DocumentEntity, ready: Boolean = false): String {
        val s = settings.value
        val account = s.iban?.let { cz.flos.core.CzechBankAccount.fromIban(it)?.toString() ?: it }.orEmpty()
        val title = kindLabelCs(d.docKind) + " " + d.number
        return cz.flos.core.Notify.fill(if (ready) s.msgOrderReady else s.msgDocument, baseValues() + mapOf(
            "jmeno" to d.customerName, "cislo" to if (ready) d.number else title, "castka" to Money.format(d.total),
            "ucet" to account, "vs" to cz.flos.core.DocumentNumbers.variableSymbol(d.number),
            "datum" to d.due?.format(java.time.format.DateTimeFormatter.ofPattern("d.M.yyyy")).orEmpty(),
        ))
    }

    private fun kindLabelCs(k: DocumentKind) = when (k) {
        DocumentKind.QUOTE -> "cenovou nabídku"
        DocumentKind.PROFORMA -> "zálohovou fakturu"
        DocumentKind.SALES_ORDER -> "objednávku"
        else -> "doklad"
    }

    suspend fun customerContact(id: Long?): cz.flos.pos.data.CustomerEntity? = id?.let { customerDao.get(it) }

    /**
     * Opens WhatsApp, SMS or e-mail with [text] ready to send. Returns false when there is no valid
     * number or address, or no app can open it (the caller then offers to copy the text).
     */
    fun sendMessage(channel: String, contact: String, text: String, subject: String = settings.value.businessName): Boolean {
        val url = when (channel) {
            "whatsapp" -> cz.flos.core.Notify.whatsapp(contact, text)
            "sms" -> cz.flos.core.Notify.sms(contact, text)
            else -> cz.flos.core.Notify.email(contact, subject, text)
        }
        if (url == null) { _messages.tryEmit(tr("Chybí platné telefonní číslo nebo e-mail")); return false }
        return platform.openUrl(url)
    }

    // ---- QR menu ----
    fun menuData(): cz.flos.core.MenuData {
        val s = settings.value
        val prods = products.value.filter { it.active }
        val byCat = prods.groupBy { it.categoryId }
        val cats = categories.value.map { c -> cz.flos.core.MenuCategory(c.name, byCat[c.id].orEmpty().map { it.menuItem() }) } +
            listOfNotNull(byCat[null]?.let { cz.flos.core.MenuCategory("Ostatní", it.map { p -> p.menuItem() }) })
        return cz.flos.core.MenuData(s.businessName, listOf(s.address1, s.address2).filter { it.isNotBlank() }.joinToString(", "),
            s.business.contacts, s.openingHours, cats, s.menuNote, ordering = s.on(cz.flos.pos.data.AdvFeature.QR_ORDERING),
            webOrdering = s.on(cz.flos.pos.data.AdvFeature.WEB_ORDERING))
    }

    private fun ProductEntity.menuItem(): cz.flos.core.MenuItem {
        val note = options.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")
        // With QR ordering on, guests can order the product: its id and the variants and extras the till offers.
        val photo = image.orEmpty()
        if (!settings.value.on(cz.flos.pos.data.AdvFeature.QR_ORDERING) || openPrice) return cz.flos.core.MenuItem(name, price, note, image = photo)
        return cz.flos.core.MenuItem(name, price, note, id = id, options = cz.flos.core.ProductOptions.format(optionGroupsFor(this)), image = photo)
    }

    /** The menu as a file for the owner's own web: without the photos, which live on the back office. */
    fun menuHtml(): ByteArray = menuData().let { d ->
        d.copy(categories = d.categories.map { c -> c.copy(items = c.items.map { it.copy(image = "") }) })
    }.let { cz.flos.core.MenuPage.html(it).toByteArray(Charsets.UTF_8) }

    /** Publishes the menu on the back office; the link is kept in Settings. */
    fun publishMenu() = viewModelScope.launch {
        val s = settings.value
        if (!s.paired) { _messages.tryEmit(tr("Pro zveřejnění spárujte pokladnu s back office, nebo menu uložte a nahrajte na svůj web.")); return@launch }
        _busy.value = tr("Zveřejňuji menu…")
        try {
            runCatching { withContext(Dispatchers.IO) { cz.flos.core.backoffice.BackOfficeClient(s.boUrl, s.boToken).publishMenu(menuData()) } }
                .onSuccess { url -> flos.settings.update { it.copy(menuUrl = url) }; _messages.tryEmit(tr("Menu je zveřejněné: %s", url)) }
                .onFailure { _messages.tryEmit(tr("Zveřejnění se nezdařilo: %s", it.message ?: it.javaClass.simpleName)) }
        } finally { _busy.value = null }
    }

    fun printMenuPoster(copies: Int) {
        val s = settings.value
        if (s.menuUrl.isBlank()) return
        printBlocks((1..copies.coerceIn(1, 50)).flatMap { cz.flos.core.MenuPage.poster(s.businessName, s.menuUrl) }, tr("QR menu vytištěno"))
    }

    // ---- Waiters ----
    /** Everyone who can serve: back office staff, names from Settings, and whoever has open bills. */
    fun waiterNames(): List<String> {
        val s = settings.value
        val staff = cz.flos.core.backoffice.BackOfficeClient.staffFromJson(s.boStaff).map { it.name }
        return cz.flos.core.Waiters.names(staff + listOf(s.cashier), s.waiters, openOrders.value.map { it.sale.cashier })
    }

    fun setWaiterAway(name: String, away: Boolean) = flos.settings.update { it.copy(waitersAway = cz.flos.core.Waiters.setAway(it.waitersAway, name, away)) }

    /** Gives an open bill to another waiter. */
    fun handOver(sw: SaleWithItems, to: String) = viewModelScope.launch {
        salesDao.update(sw.sale.copy(cashier = to))
        _messages.tryEmit(tr("Účet předán: %s", to))
    }

    /** Gives all open bills of [from] to [to], e.g. when a waiter ends their shift. */
    fun handOverAll(from: String, to: String) = viewModelScope.launch {
        val bills = openOrders.value.filter { it.sale.cashier == from }
        bills.forEach { salesDao.update(it.sale.copy(cashier = to)) }
        _messages.tryEmit(tr("%d účtů předáno: %s", bills.size, to))
    }

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
