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
) {
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
    val quantity: Int,
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

    fun toSummary(): SaleSummary = SaleSummary(sale.received, sale.paymentMethod ?: PaymentMethod.CASH, toCart().vatBreakdown(), sale.cancelled)
}
