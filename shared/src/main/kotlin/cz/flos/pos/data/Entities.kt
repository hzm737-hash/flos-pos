package cz.flos.pos.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import cz.flos.core.Cart
import cz.flos.core.CartItem
import cz.flos.core.PaymentMethod
import cz.flos.core.SaleSummary
import cz.flos.core.VatRate

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** ARGB colour used for the product tiles. */
    val color: Long = 0xFF2E7D6BL,
    val sortOrder: Int = 0,
    /** Key from ProductIcons, shown on the category's product tiles when a product has no icon of its own. */
    val icon: String? = null,
    /** Id in the back office when the category comes from there. */
    val remoteId: Long? = null,
)

@Entity(tableName = "products", indices = [Index("categoryId")])
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Price including VAT, in haléře. */
    val price: Long,
    val vatPercent: Int = 21,
    val categoryId: Long? = null,
    val active: Boolean = true,
    val sortOrder: Int = 0,
    /** Key from ProductIcons; null uses the category icon. */
    val icon: String? = null,
    /** EAN or own code; a barcode scanner typing it into the search field adds the product. */
    val barcode: String? = null,
    /** Variants and extras as text, see cz.flos.core.ProductOptions ("Velikost: Malá, Velká +40"). */
    val options: String? = null,
    /** Prices in other price lists as text, see cz.flos.core.Pricing ("Rozvoz: 89"). */
    val prices: String? = null,
    /** Id in the back office when the product comes from there. */
    val remoteId: Long? = null,
) {
    val vatRate: VatRate get() = VatRate.fromPercent(vatPercent)
}

@Entity(tableName = "sales", indices = [Index("createdAt"), Index(value = ["number"], unique = true)])
data class SaleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    /** Epoch milliseconds. */
    val createdAt: Long,
    val total: Long,
    val payment: String,
    val cashGiven: Long? = null,
    val cashier: String = "",
    val discountPercent: Int = 0,
    val variableSymbol: String? = null,
    val cancelled: Boolean = false,
    /** Number of the storno document when this sale was cancelled. */
    val stornoNumber: String? = null,
    /** Amount actually received (cash is rounded to whole crowns); this is what EET records. */
    val received: Long = total,
    /** PAID, OPEN (an order waiting for payment, e.g. a delivery) or VOID (an open order cancelled before payment). */
    val status: String = SaleStatus.PAID.name,
    /** Number of the open order ("Objednávka č. 12"); kept after payment. */
    val orderNumber: Int? = null,
    /** Free note: delivery address, customer, table. */
    val note: String = "",
    /** When the order was opened; [createdAt] is the moment of payment for paid sales. */
    val openedAt: Long? = null,
    /** When the receipt was voided (storno); used for the cash count of the shift. */
    val cancelledAt: Long? = null,
    /** Voluntary tip on top of [received]; never part of the VAT base. */
    val tip: Long = 0,
    /** For QR / bank transfer receipts: WAITING, UNDERPAID or CONFIRMED once the money is on the account. */
    val bankStatus: String? = null,
    /** What has arrived on the bank account for this receipt so far. */
    val bankPaid: Long = 0,
    val bankConfirmedAt: Long? = null,
    /** "EUR" when a payer from abroad got a SEPA QR code; [bankPaid] is then in euro cents too. */
    val payCurrency: String? = null,
    /** Amount asked in [payCurrency] (euro cents). */
    val payAmount: Long? = null,
    /** CZK per 1 EUR used for [payAmount]. */
    val eurRate: Double? = null,
    /** For a refund (vratka): id of the receipt whose items were returned. Its items have negative quantities. */
    val refundOf: Long? = null,
    /** Table of an open order or a paid table bill ("5", "Terasa 2"). */
    val tableName: String? = null,
    /** Sent to the back office (it is sent again while it is recent, so later changes such as a storno arrive too). */
    val uploaded: Boolean = false,
    /** Customer the sale belongs to (loyalty points, history). */
    val customerId: Long? = null,
    /** When the kitchen marked the order as ready; null while it is being prepared. */
    val kitchenReadyAt: Long? = null,
    /** Mixed payment, see cz.flos.core.PaymentPart ("MEAL_VOUCHER:15000,CASH:4900"); null = all by [payment]. */
    val paymentParts: String? = null,
    /** Cash share of a mixed payment, for the drawer count. */
    val cashPart: Long = 0,
    /** Service type chosen at the till: "Na místě", "S sebou", "Rozvoz"… */
    val serviceType: String? = null,
) {
    val parts: List<cz.flos.core.PaymentPart> get() = cz.flos.core.PaymentPart.parse(paymentParts)
    val isMixed: Boolean get() = parts.size > 1
    val isRefund: Boolean get() = refundOf != null
    val isForeign: Boolean get() = payCurrency != null && payCurrency != "CZK" && payAmount != null && eurRate != null
    /** Converts an amount in [payCurrency] to haléře. */
    fun toCzk(amount: Long): Long = if (isForeign) Math.round(amount * eurRate!!) else amount
    val transferStatus: TransferStatus? get() = TransferStatus.entries.firstOrNull { it.name == bankStatus }
    /** Still waiting for (part of) the money on the bank account. */
    val awaitsTransfer: Boolean get() = isPaid && !cancelled && (transferStatus == TransferStatus.WAITING || transferStatus == TransferStatus.UNDERPAID)
    /** What is still missing, in haléře. */
    val transferMissing: Long get() = when {
        !awaitsTransfer -> 0
        isForeign -> toCzk((payAmount!! - bankPaid).coerceAtLeast(0))
        else -> (received - bankPaid).coerceAtLeast(0)
    }
    val paymentMethod: PaymentMethod? get() = PaymentMethod.entries.firstOrNull { it.name == payment }
    val saleStatus: SaleStatus get() = SaleStatus.entries.firstOrNull { it.name == status } ?: SaleStatus.PAID
    val isPaid: Boolean get() = saleStatus == SaleStatus.PAID
    val isOpen: Boolean get() = saleStatus == SaleStatus.OPEN
}

/**
 * State of a sale. Only PAID sales get a receipt number, are recorded in EET and count in
 * reports. An OPEN order can still be changed, paid by any method or cancelled (VOID) without
 * anything being sent to the tax administration.
 */
enum class SaleStatus { PAID, OPEN, VOID }

/** Whether the money for a QR / bank transfer receipt has reached the bank account. */
enum class TransferStatus(val label: String) {
    WAITING("Čeká na platbu"),
    UNDERPAID("Nedoplaceno"),
    CONFIRMED("Platba přišla"),
}

@Entity(
    tableName = "sale_items",
    indices = [Index("saleId")],
    foreignKeys = [ForeignKey(entity = SaleEntity::class, parentColumns = ["id"], childColumns = ["saleId"], onDelete = ForeignKey.CASCADE)],
)
data class SaleItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleId: Long,
    val productId: Long,
    val name: String,
    val unitPrice: Long,
    val vatPercent: Int,
    /** Can be fractional, such as 0.5 for a half portion; negative on refunds. */
    val quantity: Double,
    val discountPercent: Int = 0,
)

data class SaleWithItems(
    @Embedded val sale: SaleEntity,
    @Relation(parentColumn = "id", entityColumn = "saleId") val items: List<SaleItemEntity>,
) {
    fun toCart(): Cart = Cart(
        items = items.map { CartItem(it.productId, it.name, it.unitPrice, VatRate.fromPercent(it.vatPercent), it.quantity, it.discountPercent) },
        discountPercent = sale.discountPercent,
    )

    fun toSummary(): SaleSummary = SaleSummary(sale.received, sale.paymentMethod ?: PaymentMethod.CASH, toCart().vatBreakdown(), sale.cancelled, sale.tip,
        parts = if (sale.isMixed) sale.parts else emptyList())
}
