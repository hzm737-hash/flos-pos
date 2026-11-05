package cz.flos.core

data class CartItem(
    val productId: Long,
    val name: String,
    val unitPrice: Long,
    val vatRate: VatRate,
    /** Pieces or portions; can be fractional, such as 0.5 for a half portion. Negative on refunds. */
    val quantity: Double = 1.0,
    /** Discount on this line in percent (0–100). */
    val discountPercent: Int = 0,
) {
    val gross: Long get() = Math.round(unitPrice * quantity)
    val discount: Long get() = Math.round(gross * discountPercent / 100.0)
    val total: Long get() = gross - discount
}

/** An immutable shopping cart. Every change returns a new Cart, which suits Compose state. */
data class Cart(val items: List<CartItem> = emptyList(), val discountPercent: Int = 0) {

    val subtotal: Long get() = items.sumOf { it.total }

    val cartDiscount: Long get() = Math.round(subtotal * discountPercent / 100.0)

    val total: Long get() = subtotal - cartDiscount

    val itemCount: Double get() = Qty.norm(items.sumOf { it.quantity })

    fun isEmpty() = items.isEmpty()

    fun add(productId: Long, name: String, unitPrice: Long, vatRate: VatRate, qty: Double = 1.0): Cart {
        // Variants of one product ("Pizza (velká)") stay on separate lines.
        val idx = items.indexOfFirst { it.productId == productId && it.name == name && it.unitPrice == unitPrice && it.discountPercent == 0 }
        return if (idx >= 0) {
            copy(items = items.toMutableList().also { it[idx] = it[idx].copy(quantity = Qty.norm(it[idx].quantity + qty)) })
        } else {
            copy(items = items + CartItem(productId, name, unitPrice, vatRate, qty))
        }
    }

    fun setQuantity(index: Int, qty: Double): Cart =
        if (Qty.norm(qty) < Qty.MIN) remove(index)
        else copy(items = items.toMutableList().also { it[index] = it[index].copy(quantity = Qty.norm(qty)) })

    fun setLineDiscount(index: Int, percent: Int): Cart =
        copy(items = items.toMutableList().also { it[index] = it[index].copy(discountPercent = percent.coerceIn(0, 100)) })

    fun setDiscount(percent: Int): Cart = copy(discountPercent = percent.coerceIn(0, 100))

    fun remove(index: Int): Cart = copy(items = items.filterIndexed { i, _ -> i != index })

    fun clear(): Cart = Cart()

    /**
     * VAT recap per rate. The cart-wide discount is spread across rates in proportion to
     * their share, with any rounding remainder put on the largest rate so totals match.
     */
    fun vatBreakdown(): List<VatLine> {
        val byRate = items.groupBy { it.vatRate }.mapValues { (_, l) -> l.sumOf { it.total } }
        if (byRate.isEmpty()) return emptyList()
        val sub = subtotal
        val adjusted = byRate.mapValues { (_, g) -> if (sub == 0L) g else g - Math.round(cartDiscount * g.toDouble() / sub) }
            .toMutableMap()
        val diff = total - adjusted.values.sum()
        if (diff != 0L) {
            val biggest = adjusted.maxBy { it.value }.key
            adjusted[biggest] = adjusted.getValue(biggest) + diff
        }
        return adjusted.entries.sortedByDescending { it.key.percent }.map { (rate, gross) -> Vat.split(gross, rate) }
    }
}
