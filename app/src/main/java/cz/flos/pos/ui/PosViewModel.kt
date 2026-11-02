package cz.flos.pos.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.flos.core.Cart
import cz.flos.core.DailyReport
import cz.flos.core.EscPosEncoder
import cz.flos.core.PaymentMethod
import cz.flos.core.QrPlatba
import cz.flos.core.Receipt
import cz.flos.core.ReceiptBlock
import cz.flos.core.ReceiptLayout
import cz.flos.pos.FlosApp
import cz.flos.pos.data.AppSettings
import cz.flos.pos.data.CategoryEntity
import cz.flos.pos.data.ProductEntity
import cz.flos.pos.data.SaleEntity
import cz.flos.pos.data.SaleItemEntity
import cz.flos.pos.data.SaleWithItems
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class PosViewModel(app: Application) : AndroidViewModel(app) {
    private val flos = app as FlosApp
    private val catalog = flos.database.catalog()
    private val salesDao = flos.database.sales()
    val printer = flos.printer

    val settings: StateFlow<AppSettings> = flos.settings.state
    val categories = catalog.categories().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val products = catalog.products().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val recentSales = salesDao.recent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _cart = MutableStateFlow(Cart())
    val cart: StateFlow<Cart> = _cart.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    init {
        viewModelScope.launch { if (catalog.productCount() == 0) seedDemoData() }
    }

    // ---- Cart ----
    fun addToCart(p: ProductEntity) = _cart.update { it.add(p.id, p.name, p.price, p.vatRate) }
    fun setQuantity(index: Int, qty: Int) = _cart.update { it.setQuantity(index, qty) }
    fun setLineDiscount(index: Int, percent: Int) = _cart.update { it.setLineDiscount(index, percent) }
    fun setCartDiscount(percent: Int) = _cart.update { it.setDiscount(percent) }
    fun clearCart() = _cart.update { it.clear() }

    /** The QR payment for the current cart, or null when no valid bank account is set. */
    fun qrFor(amount: Long, variableSymbol: String?, message: String): QrPlatba? {
        val iban = settings.value.iban ?: return null
        return QrPlatba(iban = iban, amount = amount, variableSymbol = variableSymbol, message = message)
    }

    /** Variable symbol derived from the receipt number: "2025-000123" → "2025000123". */
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
            val number = flos.settings.nextReceiptNumber(now.year)
            val vs = variableSymbolOf(number)
            val qr = if (payment == PaymentMethod.QR_TRANSFER) qrFor(cart.total, vs, "${s.businessName} uctenka $number") else null
            val sale = SaleEntity(
                number = number,
                createdAt = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                total = cart.total,
                payment = payment.name,
                cashGiven = cashGiven,
                cashier = s.cashier,
                discountPercent = cart.discountPercent,
                variableSymbol = if (payment == PaymentMethod.QR_TRANSFER) vs else null,
            )
            val items = cart.items.map {
                SaleItemEntity(saleId = 0, productId = it.productId, name = it.name, unitPrice = it.unitPrice,
                    vatPercent = it.vatRate.percent, quantity = it.quantity, discountPercent = it.discountPercent)
            }
            salesDao.insert(sale, items)
            _cart.value = Cart()
            onDone()
            val receipt = Receipt(number, now, s.cashier, cart, payment, cashGiven, qr)
            if (s.autoPrint) printBlocks(ReceiptLayout(s.paperWidth.chars).layout(s.business, receipt), "Účtenka $number vytištěna")
            else _messages.tryEmit("Účtenka $number uložena")
        }
    }

    fun receiptOf(sw: SaleWithItems, copy: Boolean = true): Receipt {
        val s = settings.value
        val created = LocalDateTime.ofInstant(Instant.ofEpochMilli(sw.sale.createdAt), ZoneId.systemDefault())
        val qr = if (sw.sale.paymentMethod == PaymentMethod.QR_TRANSFER) {
            qrFor(sw.sale.total, sw.sale.variableSymbol, "${s.businessName} uctenka ${sw.sale.number}")
        } else null
        return Receipt(sw.sale.number, created, sw.sale.cashier, sw.toCart(), sw.sale.paymentMethod, sw.sale.cashGiven, qr, copy)
    }

    fun receiptPreview(sw: SaleWithItems): String {
        val s = settings.value
        val blocks = ReceiptLayout(s.paperWidth.chars).layout(s.business, receiptOf(sw))
        return ReceiptLayout.toPlainText(blocks, s.paperWidth.chars)
    }

    fun reprint(sw: SaleWithItems) {
        val s = settings.value
        printBlocks(ReceiptLayout(s.paperWidth.chars).layout(s.business, receiptOf(sw)), "Kopie vytištěna")
    }

    /** Cancels (storno) a sale. The record is kept and marked, so the receipt sequence stays complete. */
    fun cancelSale(sw: SaleWithItems) = viewModelScope.launch {
        salesDao.update(sw.sale.copy(cancelled = true))
        _messages.tryEmit("Účtenka ${sw.sale.number} stornována")
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
        printBlocks(report.layout(s.business, s.paperWidth.chars), "Uzávěrka vytištěna")
    }

    fun testPrint() {
        val s = settings.value
        val blocks = buildList {
            add(ReceiptBlock.Line(s.businessName, cz.flos.core.Align.CENTER, bold = true, large = true))
            add(ReceiptBlock.Line("Zkušební tisk Flos", cz.flos.core.Align.CENTER))
            add(ReceiptBlock.Line("Příliš žluťoučký kůň úpěl ďábelské ódy"))
            add(ReceiptBlock.Separator)
            qrFor(10000, "1", "Test Flos")?.let { add(ReceiptBlock.Qr(it.toSpayd())) }
            add(ReceiptBlock.Feed(3))
        }
        printBlocks(blocks, "Zkušební tisk odeslán")
    }

    private fun printBlocks(blocks: List<ReceiptBlock>, okMessage: String) {
        val s = settings.value
        viewModelScope.launch {
            val bytes = EscPosEncoder(s.paperWidth, s.charset).encode(blocks)
            runCatching { printer.print(s.printerAddress, bytes) }
                .onSuccess { _messages.tryEmit(okMessage) }
                .onFailure { _messages.tryEmit("Tisk se nezdařil: ${it.message}") }
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
