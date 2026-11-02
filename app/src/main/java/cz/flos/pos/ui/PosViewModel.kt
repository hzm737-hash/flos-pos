package cz.flos.pos.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.flos.core.Align
import cz.flos.core.Cart
import cz.flos.core.DailyReport
import cz.flos.core.EetReceiptInfo
import cz.flos.core.EscPosEncoder
import cz.flos.core.Money
import cz.flos.core.PaymentMethod
import cz.flos.core.QrPlatba
import cz.flos.core.Receipt
import cz.flos.core.ReceiptBlock
import cz.flos.core.ReceiptLayout
import cz.flos.pos.FlosApp
import cz.flos.pos.data.AppSettings
import cz.flos.pos.data.CategoryEntity
import cz.flos.pos.data.EetRecordEntity
import cz.flos.pos.data.EetStatus
import cz.flos.pos.data.ProductEntity
import cz.flos.pos.data.SaleEntity
import cz.flos.pos.data.SaleItemEntity
import cz.flos.pos.data.SaleWithItems
import cz.flos.pos.eet.EetRetryWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class PosViewModel(app: Application) : AndroidViewModel(app) {
    private val flos = app as FlosApp
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

    private val _cart = MutableStateFlow(Cart())
    val cart: StateFlow<Cart> = _cart.asStateFlow()

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
    fun clearCart() = _cart.update { it.clear() }

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
    fun preparePaymentQr(): QrPlatba? {
        val s = settings.value
        val number = flos.settings.peekReceiptNumber(LocalDate.now().year)
        return qrFor(_cart.value.total, variableSymbolOf(number), "${s.businessName} uctenka $number")
    }

    fun checkout(payment: PaymentMethod, cashGiven: Long?, onDone: () -> Unit) {
        val cart = _cart.value
        if (cart.isEmpty()) return
        viewModelScope.launch {
            val s = settings.value
            val now = LocalDateTime.now()
            val millis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val number = flos.settings.nextReceiptNumber(now.year)
            val vs = variableSymbolOf(number)
            val qr = if (payment == PaymentMethod.QR_TRANSFER) qrFor(cart.total, vs, "${s.businessName} uctenka $number") else null
            val received = if (payment == PaymentMethod.CASH) Money.roundToCrowns(cart.total) else cart.total
            val sale = SaleEntity(
                number = number, createdAt = millis, total = cart.total, payment = payment.name, cashGiven = cashGiven,
                cashier = s.cashier, discountPercent = cart.discountPercent,
                variableSymbol = if (payment == PaymentMethod.QR_TRANSFER) vs else null, received = received,
            )
            val items = cart.items.map {
                SaleItemEntity(saleId = 0, productId = it.productId, name = it.name, unitPrice = it.unitPrice,
                    vatPercent = it.vatRate.percent, quantity = it.quantity, discountPercent = it.discountPercent)
            }
            // The sale and its EET record are stored before anything is sent or printed.
            val saleId = salesDao.insert(sale, items)
            val record = eet.createRecord(saleId, number, millis, received, storno = false)
            _cart.value = Cart()
            onDone()

            val sent = record?.let { sendToEet(it) }
            val receipt = Receipt(number, now, s.cashier, cart, payment, cashGiven, qr, eet = sent?.let(::eetInfo))
            if (s.autoPrint) printBlocks(ReceiptLayout(s.paperWidth.chars).layout(s.business, receipt), tr("Účtenka %s vytištěna", number))
            else _messages.tryEmit(tr("Účtenka %s uložena", number))
        }
    }

    /** Sends one record now; on failure the background worker takes over. */
    private suspend fun sendToEet(r: EetRecordEntity): EetRecordEntity {
        _busy.value = tr("Odesílám tržbu do EET…")
        val after = try { eet.send(r.id) ?: r } finally { _busy.value = null }
        when (after.status) {
            EetStatus.PENDING -> {
                EetRetryWorker.scheduleSoon(getApplication())
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
        val qr = if (sw.sale.paymentMethod == PaymentMethod.QR_TRANSFER) {
            qrFor(sw.sale.total, sw.sale.variableSymbol, "${s.businessName} uctenka ${sw.sale.number}")
        } else null
        val record = eetDao.forSale(sw.sale.id).firstOrNull { !it.storno }
        return Receipt(sw.sale.number, created, sw.sale.cashier, sw.toCart(), sw.sale.paymentMethod, sw.sale.cashGiven, qr, copy,
            eet = record?.let(::eetInfo))
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
        val record = eet.createRecord(sw.sale.id, stornoNumber, millis, -sw.sale.received, storno = true)
        val sent = record?.let { sendToEet(it) }
        _messages.tryEmit(tr("Účtenka %s stornována dokladem %s", sw.sale.number, stornoNumber))
        if (s.autoPrint) {
            val doc = Receipt(stornoNumber, now, s.cashier, sw.toCart(), sw.sale.paymentMethod, null, null,
                eet = sent?.let(::eetInfo), stornoOf = sw.sale.number)
            printBlocks(ReceiptLayout(s.paperWidth.chars).layout(s.business, doc), tr("Storno doklad vytištěn"))
        }
    }

    // ---- EET ----
    fun retryEetNow() = viewModelScope.launch {
        _busy.value = tr("Odesílám čekající tržby do EET…")
        val left = try { eet.sendPending() } finally { _busy.value = null }
        _messages.tryEmit(if (left == 0) tr("Všechny tržby jsou odeslané") else tr("Stále čeká %d tržeb, zkusím to znovu automaticky", left))
        if (left > 0) EetRetryWorker.scheduleSoon(getApplication())
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

    fun importCertificate(uri: Uri, password: String) = viewModelScope.launch {
        val result = runCatching {
            val bytes = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } ?: error(tr("Soubor nelze přečíst."))
            eet.importCertificate(bytes, password)
        }
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
        val drinks = catalog.upsertCategory(CategoryEntity(name = "Nápoje", color = 0xFF2E7D6BL, sortOrder = 0))
        val food = catalog.upsertCategory(CategoryEntity(name = "Jídlo", color = 0xFFC2603AL, sortOrder = 1))
        listOf(
            ProductEntity(name = "Espresso", price = 5900, vatPercent = 12, categoryId = drinks),
            ProductEntity(name = "Cappuccino", price = 7500, vatPercent = 12, categoryId = drinks),
            ProductEntity(name = "Čaj", price = 5500, vatPercent = 12, categoryId = drinks),
            ProductEntity(name = "Pivo 0,5 l", price = 5900, vatPercent = 21, categoryId = drinks),
            ProductEntity(name = "Croissant", price = 4500, vatPercent = 12, categoryId = food),
            ProductEntity(name = "Bageta", price = 8900, vatPercent = 12, categoryId = food),
        ).forEach { catalog.upsertProduct(it) }
    }
}
