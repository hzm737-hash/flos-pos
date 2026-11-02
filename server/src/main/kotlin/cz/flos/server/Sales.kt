package cz.flos.server

import cz.flos.core.Cart
import cz.flos.core.CartItem
import cz.flos.core.VatRate
import java.sql.Connection
import java.sql.ResultSet

object Sales {
    /**
     * Stores sales uploaded by a till. Uploading the same sale again is safe: it is matched by
     * (device, receipt number); a later upload can mark it cancelled or add the EET result.
     */
    fun ingest(c: Connection, device: Caller.Device, sales: List<SaleDto>): UploadResult {
        var accepted = 0
        var updated = 0
        val now = System.currentTimeMillis()
        for (s in sales) {
            val existing = c.queryOne("SELECT id, cancelled FROM sales WHERE device_id = ? AND number = ?", device.deviceId, s.number) {
                it.getLong(1) to it.getBoolean(2)
            }
            if (existing == null) {
                val id = c.insert(
                    """INSERT INTO sales (org_id, branch_id, device_id, number, created_at, total, received, payment, cashier, discount_percent,
                       cancelled, storno_number, eet_status, eet_pok, uploaded_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    device.orgId, device.branchId, device.deviceId, s.number, s.createdAt, s.total, s.received, s.payment, s.cashier,
                    s.discountPercent, s.cancelled, s.stornoNumber, s.eetStatus, s.eetPok, now,
                )
                for (i in s.items) {
                    c.insert(
                        "INSERT INTO sale_items (sale_id, product_id, name, unit_price, vat_percent, quantity, discount_percent) VALUES (?,?,?,?,?,?,?)",
                        id, i.productId, i.name, i.unitPrice, i.vatPercent, i.quantity, i.discountPercent,
                    )
                }
                if (!s.cancelled) Stock.applySale(c, device.orgId, device.branchId, s.number, s.items, -1)
                accepted++
            } else {
                val (id, wasCancelled) = existing
                c.update(
                    "UPDATE sales SET cancelled = ?, storno_number = ?, eet_status = ?, eet_pok = ? WHERE id = ?",
                    s.cancelled || wasCancelled, s.stornoNumber, s.eetStatus, s.eetPok, id,
                )
                if (s.cancelled && !wasCancelled) Stock.applySale(c, device.orgId, device.branchId, s.number, items(c, id), +1)
                updated++
            }
        }
        return UploadResult(accepted, updated, now)
    }

    fun items(c: Connection, saleId: Long): List<SaleItemDto> = c.query(
        "SELECT product_id, name, unit_price, vat_percent, quantity, discount_percent FROM sale_items WHERE sale_id = ? ORDER BY id", saleId,
    ) { SaleItemDto(it.longOrNull("product_id"), it.getString("name"), it.getLong("unit_price"), it.getInt("vat_percent"), it.getInt("quantity"), it.getInt("discount_percent")) }

    fun list(c: Connection, orgId: Long, from: Long, to: Long, branchId: Long?, limit: Int = 100_000, withItems: Boolean = true): List<SaleDto> {
        val sales = c.query(
            "SELECT * FROM sales WHERE org_id = ? AND created_at >= ? AND created_at < ?" +
                (if (branchId != null) " AND branch_id = ?" else "") + " ORDER BY created_at DESC LIMIT $limit",
            *listOfNotNull(orgId, from, to, branchId).toTypedArray(),
        ) { it.toSale() }
        if (!withItems || sales.isEmpty()) return sales
        val byId = itemsFor(c, sales.map { it.id })
        return sales.map { it.copy(items = byId[it.id].orEmpty()) }
    }

    private fun itemsFor(c: Connection, ids: List<Long>): Map<Long, List<SaleItemDto>> = ids.chunked(500).flatMap { chunk ->
        c.query(
            "SELECT sale_id, product_id, name, unit_price, vat_percent, quantity, discount_percent FROM sale_items WHERE sale_id IN (${chunk.joinToString(",") { "?" }}) ORDER BY id",
            *chunk.toTypedArray(),
        ) {
            it.getLong("sale_id") to SaleItemDto(it.longOrNull("product_id"), it.getString("name"), it.getLong("unit_price"), it.getInt("vat_percent"), it.getInt("quantity"), it.getInt("discount_percent"))
        }
    }.groupBy({ it.first }, { it.second })

    fun ResultSet.toSale() = SaleDto(
        id = getLong("id"), branchId = getLong("branch_id"), deviceId = getLong("device_id"), number = getString("number"),
        createdAt = getLong("created_at"), total = getLong("total"), received = getLong("received"), payment = getString("payment"),
        cashier = getString("cashier"), discountPercent = getInt("discount_percent"), cancelled = getBoolean("cancelled"),
        stornoNumber = getString("storno_number"), eetStatus = getString("eet_status"), eetPok = getString("eet_pok"),
    )

    fun cartOf(s: SaleDto) = Cart(
        items = s.items.map { CartItem(it.productId ?: 0, it.name, it.unitPrice, VatRate.fromPercent(it.vatPercent), it.quantity, it.discountPercent) },
        discountPercent = s.discountPercent,
    )
}
