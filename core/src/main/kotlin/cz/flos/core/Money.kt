package cz.flos.core

import kotlin.math.abs

/**
 * Amounts are stored as whole haléře (1 Kč = 100 haléřů) in a Long, so sums never
 * suffer from floating point rounding.
 */
object Money {
    fun format(haler: Long, currency: String = "Kč"): String {
        val sign = if (haler < 0) "-" else ""
        val a = abs(haler)
        val kc = a / 100
        val h = a % 100
        val grouped = kc.toString().reversed().chunked(3).joinToString(" ").reversed()
        return "$sign$grouped,${h.toString().padStart(2, '0')}" + if (currency.isEmpty()) "" else " $currency"
    }

    /** Plain decimal with a dot, e.g. 1234.50 — the format QR Platba expects. */
    fun toDecimal(haler: Long): String {
        val sign = if (haler < 0) "-" else ""
        val a = abs(haler)
        return "$sign${a / 100}.${(a % 100).toString().padStart(2, '0')}"
    }

    /** Parses "12", "12,5", "12.50", "1 234,50" into haléře. Returns null if not a number. */
    fun parse(text: String): Long? {
        val cleaned = text.trim().replace(" ", "").replace(" ", "").replace(',', '.')
        if (cleaned.isEmpty()) return null
        val negative = cleaned.startsWith("-")
        val body = cleaned.removePrefix("-")
        val parts = body.split('.')
        if (parts.size > 2 || parts.any { p -> p.any { !it.isDigit() } }) return null
        val whole = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
        val fracStr = parts.getOrNull(1) ?: ""
        if (fracStr.length > 2) return null
        val frac = fracStr.padEnd(2, '0').toLong()
        val value = whole * 100 + frac
        return if (negative) -value else value
    }

    /** Czech cash rounding to whole crowns (coins below 1 Kč are no longer in circulation). */
    fun roundToCrowns(haler: Long): Long {
        val r = Math.floorMod(haler, 100L)
        return if (r >= 50) haler - r + 100 else haler - r
    }
}
