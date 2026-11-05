package cz.flos.core

/**
 * Labels printed by a shop scale: an EAN-13 that starts with an in-store prefix, then the
 * product's code (PLU) and the weight or the price. With prefix "28" and a five-digit PLU:
 *
 *     28 01234 01250 c   →  product 01234, 1,250 kg
 *
 * The owner sets which prefixes mean weight and which mean price (in crowns with two decimals,
 * so "01250" = 12,50 Kč); the last digit is the EAN check digit.
 */
data class ScaleSettings(
    /** Prefixes whose labels carry the weight in grams, e.g. "28" or "27, 28". Empty = off. */
    val weightPrefixes: String = "",
    /** Prefixes whose labels carry the price in haléře, e.g. "29". Empty = off. */
    val pricePrefixes: String = "",
    /** Length of the product code after the prefix (4–6; the value fills the rest). */
    val pluDigits: Int = 5,
) {
    val enabled: Boolean get() = weightPrefixes.isNotBlank() || pricePrefixes.isNotBlank()
}

sealed interface ScaleCode {
    val plu: String
    /** Weight label: [grams] of the product sold per kilogram. */
    data class Weight(override val plu: String, val grams: Int) : ScaleCode {
        val kilograms: Double get() = Qty.norm(grams / 1000.0)
    }
    /** Price label: the scale already computed [price] (haléře). */
    data class Price(override val plu: String, val price: Long) : ScaleCode
}

object ScaleBarcode {
    private fun prefixes(text: String) = text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() && it.all(Char::isDigit) }

    /** The EAN-13 check digit for the first 12 digits. */
    fun checkDigit(first12: String): Int {
        val sum = first12.mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 1 else 3 }.sum()
        return (10 - sum % 10) % 10
    }

    /** Reads a scale label, or null when [code] is not one (wrong length, prefix or check digit). */
    fun parse(code: String, s: ScaleSettings): ScaleCode? {
        val c = code.trim()
        if (!s.enabled || c.length != 13 || !c.all(Char::isDigit)) return null
        if (checkDigit(c.take(12)) != c[12] - '0') return null
        val plu = s.pluDigits.coerceIn(4, 6)
        fun split(prefix: String): Pair<String, Long>? {
            if (!c.startsWith(prefix)) return null
            val start = prefix.length
            val end = start + plu
            if (end >= 12) return null
            return c.substring(start, end) to c.substring(end, 12).toLong()
        }
        prefixes(s.weightPrefixes).firstNotNullOfOrNull { split(it) }?.let { (p, v) -> return ScaleCode.Weight(p, v.toInt()) }
        prefixes(s.pricePrefixes).firstNotNullOfOrNull { split(it) }?.let { (p, v) -> return ScaleCode.Price(p, v) }
        return null
    }

    /**
     * Quantity for a cart line: the weight in kilograms, or for a price label the price divided
     * by the unit price per kilogram (three decimals).
     */
    fun quantity(code: ScaleCode, unitPrice: Long): Double = when (code) {
        is ScaleCode.Weight -> code.kilograms
        is ScaleCode.Price -> if (unitPrice <= 0) 1.0 else Qty.norm(code.price.toDouble() / unitPrice)
    }

    /** Product codes the label may be stored under: "01234" and "1234". */
    fun pluCandidates(code: ScaleCode): List<String> = listOf(code.plu, code.plu.trimStart('0').ifEmpty { "0" }).distinct()
}
