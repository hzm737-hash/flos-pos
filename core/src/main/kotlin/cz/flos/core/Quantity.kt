package cz.flos.core

import kotlin.math.abs
import kotlin.math.round

/**
 * Item quantities. A quantity can be fractional, such as a half portion (0,5) or 1,5 l, and
 * is kept to three decimal places (grams, millilitres), so sums never drift.
 */
object Qty {
    /** Smallest quantity a line can have. */
    const val MIN = 0.001

    /** Rounds to three decimal places. */
    fun norm(q: Double): Double = round(q * 1000.0) / 1000.0

    /** True when [q] is a whole number (1, 2, …). */
    fun isWhole(q: Double): Boolean = abs(q - round(q)) < 0.0005

    /** Czech style: "2", "0,5", "1,25". */
    fun format(q: Double): String {
        val n = norm(q)
        if (isWhole(n)) return round(n).toLong().toString()
        return "%.3f".format(java.util.Locale.ROOT, n).trimEnd('0').trimEnd('.').replace('.', ',')
    }

    /** Reads "0,5", "0.5", "1/2", "½", "1 1/2"; null when it is not a positive number. */
    fun parse(text: String): Double? {
        val s = text.replace("½", " 1/2").replace("¼", " 1/4").replace("¾", " 3/4").replace('\u00A0', ' ').trim()
        if (s.isEmpty()) return null
        val parts = s.split(Regex("\\s+"))
        var sum = 0.0
        for (p in parts) {
            val v = if ('/' in p) {
                val (a, b) = p.split('/', limit = 2).map { it.replace(',', '.').toDoubleOrNull() ?: return null }
                if (b == 0.0) return null
                a / b
            } else {
                p.replace(',', '.').toDoubleOrNull() ?: return null
            }
            if (v < 0 || v.isNaN() || v.isInfinite()) return null
            sum += v
        }
        val n = norm(sum)
        return n.takeIf { it >= MIN }
    }

    /** One step up or down for the +/− buttons: whole pieces, but never past [max] or below zero. */
    fun stepUp(q: Double, max: Double = Double.MAX_VALUE): Double = norm(minOf(q + 1.0, max))
    fun stepDown(q: Double): Double = norm(maxOf(q - 1.0, 0.0))
}
