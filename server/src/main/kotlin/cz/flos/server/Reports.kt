package cz.flos.server

import cz.flos.core.Money
import java.sql.Connection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Reports {
    val ZONE: ZoneId = ZoneId.of("Europe/Prague")
    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZONE)

    fun summary(c: Connection, orgId: Long, from: Long, to: Long, branchId: Long?): SummaryReport {
        val all = Sales.list(c, orgId, from, to, branchId)
        val sales = all.filterNot { it.cancelled }
        val branchNames = c.query("SELECT id, name FROM branches WHERE org_id = ?", orgId) { it.getLong(1) to it.getString(2) }.toMap()
        val costs = unitCosts(c, orgId)

        val revenue = sales.sumOf { it.received }
        val vat = sales.flatMap { Sales.cartOf(it).vatBreakdown() }.groupBy { it.rate.percent }
            .map { (rate, l) -> VatRow(rate, l.sumOf { it.base }, l.sumOf { it.vat }, l.sumOf { it.gross }) }
            .sortedByDescending { it.rate }

        val products = sales.flatMap { s ->
            val cart = Sales.cartOf(s)
            // Spread the receipt discount over its lines so product revenue adds up to the total.
            val factor = if (cart.subtotal == 0L) 1.0 else cart.total.toDouble() / cart.subtotal
            s.items.zip(cart.items).map { (dto, line) -> Triple(dto, line.quantity, Math.round(line.total * factor)) }
        }.groupBy { it.first.productId ?: -it.first.name.hashCode().toLong() }
            .map { (_, rows) ->
                val first = rows.first().first
                val qty = rows.sumOf { it.second }
                ProductRow(first.productId, first.name, qty, rows.sumOf { it.third }, (first.productId?.let { costs[it] } ?: 0) * qty)
            }.sortedByDescending { it.revenue }

        val costKnown = products.isNotEmpty() && products.all { it.productId != null && (costs[it.productId] ?: 0) > 0 }
        return SummaryReport(
            from = from, to = to, revenue = revenue, receipts = sales.size, cancelled = all.size - sales.size,
            averageReceipt = if (sales.isEmpty()) 0 else revenue / sales.size,
            grossMargin = if (costKnown) products.sumOf { it.revenue - it.cost } else null,
            byPayment = sales.groupBy { it.payment }.map { (k, l) -> Amount(k, l.sumOf { it.received }, l.size) }.sortedByDescending { it.amount },
            byVat = vat,
            byDay = sales.groupBy { DAY.format(Instant.ofEpochMilli(it.createdAt)) }.map { (k, l) -> Amount(k, l.sumOf { it.received }, l.size) }.sortedBy { it.key },
            byHour = sales.groupBy { Instant.ofEpochMilli(it.createdAt).atZone(ZONE).hour }
                .map { (h, l) -> Amount(h.toString().padStart(2, '0'), l.sumOf { it.received }, l.size) }.sortedBy { it.key },
            byBranch = sales.groupBy { it.branchId }.map { (b, l) -> Amount(branchNames[b] ?: "#$b", l.sumOf { it.received }, l.size) }.sortedByDescending { it.amount },
            byCashier = sales.groupBy { it.cashier.ifBlank { "—" } }.map { (k, l) -> Amount(k, l.sumOf { it.received }, l.size) }.sortedByDescending { it.amount },
            topProducts = products.take(50),
        )
    }

    /**
     * Cost of one unit of each product in haléře: its purchase price, or for a product with a
     * recipe the sum of its ingredients' purchase prices.
     */
    fun unitCosts(c: Connection, orgId: Long): Map<Long, Long> {
        val direct = c.query("SELECT id, cost_price FROM products WHERE org_id = ?", orgId) { it.getLong(1) to it.getLong(2) }.toMap()
        val recipes = c.query(
            "SELECT r.product_id, r.ingredient_id, r.qty_milli FROM recipe_items r JOIN products p ON p.id = r.product_id WHERE p.org_id = ?", orgId,
        ) { Triple(it.getLong(1), it.getLong(2), it.getLong(3)) }.groupBy { it.first }
        return direct.mapValues { (id, cost) ->
            if (cost > 0) cost else recipes[id]?.sumOf { (_, ing, qty) -> (direct[ing] ?: 0) * qty / 1000 } ?: 0
        }
    }

    // ---- Exports for accountants ----

    fun salesCsv(sales: List<SaleDto>, branchNames: Map<Long, String>): String = buildString {
        append('﻿') // BOM so Excel opens Czech characters correctly
        appendLine("Datum;Číslo;Provozovna;Pokladna;Platba;Obsluha;Celkem;Přijato;Základ 21 %;DPH 21 %;Základ 12 %;DPH 12 %;Osvobozeno;Storno;EET stav;POK")
        val fmt = DateTimeFormatter.ofPattern("d.M.yyyy HH:mm:ss").withZone(ZONE)
        for (s in sales.sortedBy { it.createdAt }) {
            val vat = Sales.cartOf(s).vatBreakdown().associateBy { it.rate.percent }
            val cols = listOf(
                fmt.format(Instant.ofEpochMilli(s.createdAt)), s.number, branchNames[s.branchId] ?: "", s.deviceId.toString(), s.payment, s.cashier,
                csvMoney(s.total), csvMoney(s.received),
                csvMoney(vat[21]?.base ?: 0), csvMoney(vat[21]?.vat ?: 0), csvMoney(vat[12]?.base ?: 0), csvMoney(vat[12]?.vat ?: 0), csvMoney(vat[0]?.gross ?: 0),
                if (s.cancelled) "ano" else "", s.eetStatus ?: "", s.eetPok ?: "",
            )
            appendLine(cols.joinToString(";") { csv(it) })
        }
    }

    fun itemsCsv(sales: List<SaleDto>): String = buildString {
        append('﻿')
        appendLine("Datum;Číslo účtenky;Produkt;Množství;Jedn. cena;Sleva %;Sazba DPH;Celkem;Storno")
        val fmt = DateTimeFormatter.ofPattern("d.M.yyyy HH:mm:ss").withZone(ZONE)
        for (s in sales.sortedBy { it.createdAt }) {
            Sales.cartOf(s).items.forEach { i ->
                appendLine(listOf(fmt.format(Instant.ofEpochMilli(s.createdAt)), s.number, i.name, i.quantity.toString(), csvMoney(i.unitPrice),
                    i.discountPercent.toString(), i.vatRate.percent.toString(), csvMoney(i.total), if (s.cancelled) "ano" else "").joinToString(";") { csv(it) })
            }
        }
    }

    /**
     * POHODA XML import (dataPack with "prodejka" documents, one per receipt). Import it in POHODA
     * via Soubor → Datová komunikace → XML import/export.
     */
    fun pohodaXml(org: OrgDto, sales: List<SaleDto>): String = buildString {
        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZONE)
        val ico = org.ico.filter(Char::isDigit)
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine(
            """<dat:dataPack xmlns:dat="http://www.stormware.cz/schema/version_2/data.xsd" xmlns:pro="http://www.stormware.cz/schema/version_2/prodejka.xsd" """ +
                """xmlns:typ="http://www.stormware.cz/schema/version_2/type.xsd" version="2.0" id="flos-${System.currentTimeMillis()}" ico="$ico" application="Flos" note="Prodejky z pokladny Flos">""",
        )
        for (s in sales.filterNot { it.cancelled }.sortedBy { it.createdAt }) {
            val cart = Sales.cartOf(s)
            appendLine("""  <dat:dataPackItem version="2.0" id="${xml(s.number)}">""")
            appendLine("""    <pro:prodejka version="2.0">""")
            appendLine("""      <pro:prodejkaHeader>""")
            appendLine("""        <pro:prodejkaType>saleVoucher</pro:prodejkaType>""")
            appendLine("""        <pro:number><typ:numberRequested>${xml(s.number.filter { it.isLetterOrDigit() }.takeLast(10))}</typ:numberRequested></pro:number>""")
            appendLine("""        <pro:date>${date.format(Instant.ofEpochMilli(s.createdAt))}</pro:date>""")
            appendLine("""        <pro:text>Účtenka ${xml(s.number)}</pro:text>""")
            appendLine("""        <pro:paymentType><typ:paymentType>${if (s.payment == "CASH") "cash" else if (s.payment == "CARD") "creditcard" else "draft"}</typ:paymentType></pro:paymentType>""")
            appendLine("""      </pro:prodejkaHeader>""")
            appendLine("""      <pro:prodejkaDetail>""")
            val factor = if (cart.subtotal == 0L) 1.0 else cart.total.toDouble() / cart.subtotal
            for (i in cart.items) {
                val unit = Math.round(i.total * factor) / i.quantity.toDouble() / 100.0
                appendLine("""        <pro:prodejkaItem>""")
                appendLine("""          <pro:text>${xml(i.name.take(90))}</pro:text>""")
                appendLine("""          <pro:quantity>${i.quantity}</pro:quantity>""")
                appendLine("""          <pro:payVAT>true</pro:payVAT>""")
                appendLine("""          <pro:rateVAT>${pohodaRate(i.vatRate.percent)}</pro:rateVAT>""")
                appendLine("""          <pro:homeCurrency><typ:unitPrice>${"%.2f".format(java.util.Locale.ROOT, unit)}</typ:unitPrice></pro:homeCurrency>""")
                appendLine("""        </pro:prodejkaItem>""")
            }
            appendLine("""      </pro:prodejkaDetail>""")
            appendLine("""      <pro:prodejkaSummary><pro:roundingDocument>none</pro:roundingDocument></pro:prodejkaSummary>""")
            appendLine("""    </pro:prodejka>""")
            appendLine("""  </dat:dataPackItem>""")
        }
        appendLine("</dat:dataPack>")
    }

    private fun pohodaRate(percent: Int) = when (percent) { 21 -> "high"; 12 -> "low"; else -> "none" }
    private fun csvMoney(h: Long) = Money.format(h, "").replace(" ", "")
    private fun csv(v: String) = if (v.any { it == ';' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    private fun xml(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
