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
) {
    val paymentMethod: PaymentMethod get() = PaymentMethod.valueOf(payment)
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

    fun toSummary(): SaleSummary = SaleSummary(sale.received, sale.paymentMethod, toCart().vatBreakdown(), sale.cancelled)
}
