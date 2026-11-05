package cz.flos.server

import io.ktor.http.HttpStatusCode
import java.sql.Connection
import kotlin.math.roundToLong

/** Stock quantities are kept in thousandths of a unit. */
fun Double.toMilli(): Long = (this * 1000).roundToLong()
fun Long.fromMilli(): Double = this / 1000.0

object Stock {
    fun level(c: Connection, branchId: Long, productId: Long): Long =
        c.queryOne("SELECT qty_milli FROM stock_levels WHERE branch_id = ? AND product_id = ?", branchId, productId) { it.getLong(1) } ?: 0

    /** Records a movement and updates the stock level (positive = in, negative = out). */
    fun move(
        c: Connection, orgId: Long, branchId: Long, productId: Long, qtyMilli: Long, kind: String,
        note: String = "", ref: String = "", userId: Long? = null, unitCost: Long? = null,
    ) {
        if (qtyMilli == 0L) return
        c.insert(
            "INSERT INTO stock_movements (org_id, branch_id, product_id, qty_milli, kind, unit_cost, note, ref, user_id, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
            orgId, branchId, productId, qtyMilli, kind, unitCost, note, ref, userId, System.currentTimeMillis(),
        )
        val changed = c.update("UPDATE stock_levels SET qty_milli = qty_milli + ? WHERE branch_id = ? AND product_id = ?", qtyMilli, branchId, productId)
        if (changed == 0) c.update("INSERT INTO stock_levels (branch_id, product_id, qty_milli) VALUES (?,?,?)", branchId, productId, qtyMilli)
        // Receiving goods with a purchase price updates the product's cost price.
        if (unitCost != null && kind == "RECEIPT") c.update("UPDATE products SET cost_price = ?, updated_at = ? WHERE id = ?", unitCost, System.currentTimeMillis(), productId)
    }

    fun applyRequest(c: Connection, orgId: Long, req: StockMovementRequest, userId: Long?) {
        requireBranch(c, orgId, req.branchId)
        req.toBranchId?.let { requireBranch(c, orgId, it) }
        for (line in req.lines) {
            val ok = c.queryOne("SELECT id FROM products WHERE id = ? AND org_id = ?", line.productId, orgId) { it.getLong(1) }
                ?: throw ApiError(HttpStatusCode.BadRequest, "Neznámý produkt ${line.productId}")
            val q = line.qty.toMilli()
            when (req.kind) {
                "RECEIPT" -> move(c, orgId, req.branchId, ok, q, "RECEIPT", req.note, req.ref, userId, line.unitCost)
                "WASTE" -> move(c, orgId, req.branchId, ok, -q, "WASTE", req.note, req.ref, userId)
                "ADJUST" -> move(c, orgId, req.branchId, ok, q, "ADJUST", req.note, req.ref, userId)
                "INVENTORY" -> move(c, orgId, req.branchId, ok, q - level(c, req.branchId, ok), "INVENTORY", req.note, req.ref, userId)
                "TRANSFER" -> {
                    val to = req.toBranchId ?: throw ApiError(HttpStatusCode.BadRequest, "Chybí cílová provozovna")
                    if (to == req.branchId) throw ApiError(HttpStatusCode.BadRequest, "Převod do stejné provozovny")
                    move(c, orgId, req.branchId, ok, -q, "TRANSFER_OUT", req.note, req.ref, userId)
                    move(c, orgId, to, ok, q, "TRANSFER_IN", req.note, req.ref, userId)
                }
                else -> throw ApiError(HttpStatusCode.BadRequest, "Neznámý druh pohybu ${req.kind}")
            }
        }
    }

    /**
     * Deducts (sign = -1) or returns (sign = +1) the stock used by a sale. A product with a
     * recipe uses its ingredients; otherwise the product itself is deducted if stock is tracked.
     */
    fun applySale(c: Connection, orgId: Long, branchId: Long, saleNumber: String, items: List<SaleItemDto>, sign: Int) {
        for (item in items) {
            val pid = item.productId ?: continue
            val product = c.queryOne("SELECT id, track_stock FROM products WHERE id = ? AND org_id = ?", pid, orgId) { it.getLong(1) to it.getBoolean(2) } ?: continue
            val recipe = c.query(
                "SELECT r.ingredient_id, r.qty_milli, p.track_stock FROM recipe_items r JOIN products p ON p.id = r.ingredient_id WHERE r.product_id = ?", pid,
            ) { Triple(it.getLong(1), it.getLong(2), it.getBoolean(3)) }
            val kind = if (sign < 0) "SALE" else "SALE_RETURN"
            if (recipe.isNotEmpty()) {
                recipe.filter { it.third }.forEach { (ing, qty, _) -> move(c, orgId, branchId, ing, Math.round(sign * qty * item.quantity), kind, ref = saleNumber) }
            } else if (product.second) {
                move(c, orgId, branchId, pid, Math.round(sign * 1000.0 * item.quantity), kind, ref = saleNumber)
            }
        }
    }

    fun requireBranch(c: Connection, orgId: Long, branchId: Long) {
        c.queryOne("SELECT id FROM branches WHERE id = ? AND org_id = ?", branchId, orgId) { it.getLong(1) }
            ?: throw ApiError(HttpStatusCode.NotFound, "Provozovna nenalezena")
    }
}
