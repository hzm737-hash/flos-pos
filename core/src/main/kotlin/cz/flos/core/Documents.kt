package cz.flos.core

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Cart lines stored as plain text, for documents that are not sales (quotes, pro forma
 * invoices, customer orders). One line per item, fields separated by tabs:
 *
 *     %discount	5
 *     12	2	7900	12	0	Pizza Margherita
 *
 * (product id, quantity, unit price in haléře, VAT percent, line discount, name).
 */
object CartText {
    fun encode(cart: Cart): String = buildString {
        if (cart.discountPercent > 0) append("%discount\t").append(cart.discountPercent).append('\n')
        cart.items.forEach { i ->
            append(i.productId).append('\t').append(Qty.norm(i.quantity)).append('\t').append(i.unitPrice).append('\t')
                .append(i.vatRate.percent).append('\t').append(i.discountPercent).append('\t')
                .append(i.name.replace('\t', ' ').replace('\n', ' ')).append('\n')
        }
    }

    fun decode(text: String?): Cart {
        var discount = 0
        val items = text.orEmpty().lines().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size == 2 && f[0] == "%discount") { discount = f[1].toIntOrNull()?.coerceIn(0, 100) ?: 0; return@mapNotNull null }
            if (f.size < 6) return@mapNotNull null
            val qty = f[1].toDoubleOrNull() ?: return@mapNotNull null
            val price = f[2].toLongOrNull() ?: return@mapNotNull null
            CartItem(f[0].toLongOrNull() ?: 0, f.drop(5).joinToString("\t"), price, VatRate.fromPercent(f[3].toIntOrNull() ?: 21),
                Qty.norm(qty), f[4].toIntOrNull()?.coerceIn(0, 100) ?: 0)
        }
        return Cart(items, discount)
    }
}

/** Document numbers per kind and year: "CN-2026-0001" for quotes, "ZF-…" pro forma, "OZ-…" customer orders. */
object DocumentNumbers {
    fun prefix(kind: DocumentKind): String = when (kind) {
        DocumentKind.QUOTE -> "CN"
        DocumentKind.PROFORMA -> "ZF"
        DocumentKind.SALES_ORDER -> "OZ"
        else -> "D"
    }

    /** The next number after the [existing] numbers of the same kind. */
    fun next(kind: DocumentKind, year: Int, existing: List<String>): String {
        val start = "${prefix(kind)}-$year-"
        val last = existing.filter { it.startsWith(start) }.mapNotNull { it.removePrefix(start).toIntOrNull() }.maxOrNull() ?: 0
        return start + (last + 1).toString().padStart(4, '0')
    }

    /** Variable symbol for a pro forma payment: the digits of the number, "ZF-2026-0007" → "20260007". */
    fun variableSymbol(number: String): String = number.filter(Char::isDigit).takeLast(10)
}

/** A table reservation as far as clashes are concerned. */
data class Booking(val id: Long, val table: String?, val at: LocalDateTime, val minutes: Int, val active: Boolean = true) {
    val end: LocalDateTime get() = at.plusMinutes(minutes.toLong().coerceAtLeast(1))
    fun overlaps(o: Booking): Boolean = at.isBefore(o.end) && o.at.isBefore(end)
}

object Bookings {
    /** Active reservations of the same table that overlap [b]. */
    fun conflicts(b: Booking, all: List<Booking>): List<Booking> =
        if (b.table.isNullOrBlank()) emptyList()
        else all.filter { it.id != b.id && it.active && it.table == b.table && it.overlaps(b) }

    /**
     * The next active reservation of [table] that starts within [aheadMinutes] or is running now,
     * for the badge on the table map.
     */
    fun upcoming(table: String, all: List<Booking>, now: LocalDateTime, aheadMinutes: Long = 180): Booking? =
        all.filter { it.active && it.table == table && it.end.isAfter(now) && !it.at.isAfter(now.plusMinutes(aheadMinutes)) }
            .minByOrNull { it.at }

    /** Days that have reservations, with how many guests come. */
    fun guestsByDay(all: List<Pair<Booking, Int>>): Map<LocalDate, Int> =
        all.filter { it.first.active }.groupBy { it.first.at.toLocalDate() }.mapValues { (_, l) -> l.sumOf { it.second } }.toSortedMap()
}

/**
 * Waiters: who serves which open bills, and who is away. A waiter who leaves hands their
 * bills to someone who is available.
 */
object Waiters {
    /** Everyone known: staff from the back office, names typed in Settings, and whoever has open bills. */
    fun names(staff: List<String>, typed: String?, onBills: List<String>): List<String> =
        (staff + typed.orEmpty().split(',', ';', '\n') + onBills).map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    fun away(text: String?): Set<String> = text.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun setAway(text: String?, name: String, isAway: Boolean): String =
        (if (isAway) away(text) + name else away(text) - name).sorted().joinToString("\n")

    /** The available waiter with the fewest open bills, to take over from [leaving]. */
    fun suggest(leaving: String, names: List<String>, away: Set<String>, billsOf: (String) -> Int): String? =
        names.filter { it != leaving && it !in away }.minByOrNull { billsOf(it) }
}
