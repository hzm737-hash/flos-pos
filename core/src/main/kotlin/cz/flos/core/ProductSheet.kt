package cz.flos.core

import kotlin.math.roundToLong

/** One product as a row of the import/export table. Prices in haléře. */
data class ProductRow(
    val name: String,
    val price: Long,
    val vatPercent: Int = 21,
    val category: String = "",
    val barcode: String = "",
    val active: Boolean = true,
    val options: String = "",
    val prices: String = "",
)

data class SheetResult(val rows: List<ProductRow>, val errors: List<String>)

/** What an import does: new products, changed ones (old → new), and how many stay the same. */
data class ImportPlan<K>(val added: List<ProductRow>, val changed: List<Pair<K, ProductRow>>, val unchanged: Int)

/**
 * Products in a spreadsheet the owner can edit in Excel. Columns are recognised by their
 * Czech or English heading in any order; only the name and the price are needed.
 */
object ProductSheet {
    val HEADER = listOf("Název", "Cena", "DPH %", "Kategorie", "EAN", "Aktivní", "Varianty", "Ceníky")

    private val ALIASES = mapOf(
        "nazev" to 0, "name" to 0, "produkt" to 0, "product" to 0, "polozka" to 0,
        "cena" to 1, "price" to 1, "cena s dph" to 1, "prodejni cena" to 1,
        "dph" to 2, "dph %" to 2, "vat" to 2, "vat %" to 2, "sazba dph" to 2,
        "kategorie" to 3, "category" to 3, "skupina" to 3,
        "ean" to 4, "barcode" to 4, "carovy kod" to 4, "kod" to 4, "code" to 4, "plu" to 4,
        "aktivni" to 5, "active" to 5, "v prodeji" to 5,
        "varianty" to 6, "options" to 6, "varianty a doplnky" to 6,
        "ceniky" to 7, "price lists" to 7, "ceny v cenicich" to 7,
    )

    fun export(rows: List<ProductRow>): List<List<Any?>> = listOf(HEADER) + rows.map { r ->
        listOf(r.name, r.price / 100.0, r.vatPercent, r.category, r.barcode, if (r.active) "ano" else "ne", r.options, r.prices)
    }

    /** "59,90", "59.9", "59,90 Kč", Excel's 59.900000000000006 → haléře. */
    fun price(text: String): Long? {
        val t = text.replace("Kč", "").replace("CZK", "").replace(" ", "").replace(" ", "").replace(',', '.').trim()
        if (t.isEmpty()) return null
        return t.toDoubleOrNull()?.takeIf { it >= 0 && it < 10_000_000 }?.let { (it * 100).roundToLong() }
    }

    private fun yes(text: String) = Pricing.key(text) !in setOf("ne", "no", "0", "false", "n")

    fun parse(table: List<List<String>>): SheetResult {
        if (table.isEmpty()) return SheetResult(emptyList(), listOf("Soubor je prázdný."))
        val head = table.first().map { ALIASES[Pricing.key(it).removeSuffix(":")] }
        val hasHeader = head.count { it != null } >= 2
        val cols = if (hasHeader) head else HEADER.indices.toList()
        if (hasHeader && (0 !in cols || 1 !in cols)) return SheetResult(emptyList(), listOf("Chybí sloupec Název nebo Cena."))
        fun col(row: List<String>, field: Int) = cols.indexOf(field).takeIf { it >= 0 }?.let { row.getOrNull(it) }?.trim().orEmpty()
        val rows = mutableListOf<ProductRow>()
        val errors = mutableListOf<String>()
        table.drop(if (hasHeader) 1 else 0).forEachIndexed { i, row ->
            val line = i + if (hasHeader) 2 else 1
            val name = col(row, 0)
            if (name.isEmpty()) { if (row.any { it.isNotBlank() }) errors.add("Řádek $line: chybí název."); return@forEachIndexed }
            val price = price(col(row, 1))
            if (price == null) { errors.add("Řádek $line ($name): neplatná cena „${col(row, 1)}“."); return@forEachIndexed }
            val vatText = col(row, 2).removeSuffix("%").trim().replace(',', '.')
            val vat = if (vatText.isEmpty()) 21 else vatText.toDoubleOrNull()?.let { if (it < 1 && it > 0) (it * 100).roundToLong().toInt() else it.roundToLong().toInt() }
            if (vat == null || VatRate.entries.none { it.percent == vat }) { errors.add("Řádek $line ($name): neznámá sazba DPH „${col(row, 2)}“."); return@forEachIndexed }
            val ean = col(row, 4).let { if (it.endsWith(".0")) it.dropLast(2) else it }
            rows.add(ProductRow(name, price, vat, col(row, 3), ean, col(row, 5).let { it.isEmpty() || yes(it) }, col(row, 6), col(row, 7)))
        }
        val dup = rows.groupBy { Pricing.key(it.name) to it.barcode }.filter { it.value.size > 1 }.keys
        dup.forEach { errors.add("Produkt „${it.first}“ je v souboru vícekrát, použije se poslední řádek.") }
        return SheetResult(rows.associateBy { Pricing.key(it.name) to it.barcode }.values.toList(), errors)
    }

    /**
     * Matches incoming rows with [existing] products (by EAN first, then by name without case and
     * accents). [key] is how the caller identifies an existing product, e.g. its id.
     */
    fun <K> plan(existing: List<Pair<K, ProductRow>>, incoming: List<ProductRow>): ImportPlan<K> {
        val byCode = existing.filter { it.second.barcode.isNotBlank() }.associateBy { it.second.barcode }
        val byName = existing.associateBy { Pricing.key(it.second.name) }
        val added = mutableListOf<ProductRow>()
        val changed = mutableListOf<Pair<K, ProductRow>>()
        var same = 0
        incoming.forEach { r ->
            val match = (if (r.barcode.isNotBlank()) byCode[r.barcode] else null) ?: byName[Pricing.key(r.name)]
            when {
                match == null -> added.add(r)
                match.second == r -> same++
                else -> changed.add(match.first to r)
            }
        }
        return ImportPlan(added, changed, same)
    }
}

/** How a bulk price change is rounded. */
enum class PriceRounding { NONE, CROWN, NINETY }

object BulkEdit {
    /** [price] changed by [percent] (e.g. +10 or -5) and rounded; never below zero. */
    fun adjust(price: Long, percent: Double, rounding: PriceRounding = PriceRounding.NONE): Long {
        val raw = (price * (1 + percent / 100.0)).roundToLong().coerceAtLeast(0)
        return when (rounding) {
            PriceRounding.NONE -> raw
            PriceRounding.CROWN -> Money.roundToCrowns(raw)
            // 64,20 → 63,90; 64,95 → 64,90: the nearest price ending in ,90.
            PriceRounding.NINETY -> (Math.round((raw - 90) / 100.0) * 100 + 90).coerceAtLeast(90)
        }
    }
}
