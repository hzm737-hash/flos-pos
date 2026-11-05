package cz.flos.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** One paid receipt as statistics need it. Refunds have a negative [received] and negative item quantities. */
data class StatSale(
    val at: Long,
    val received: Long,
    val tip: Long,
    val payment: PaymentMethod,
    val cashier: String,
    val cancelled: Boolean,
    val items: List<StatItem>,
    val refund: Boolean = false,
    /** Receipt number, for the list of the largest receipts. */
    val number: String = "",
)

data class StatItem(val name: String, val category: String, val quantity: Double, val total: Long)

/** [quantity] is pieces sold (can be fractional) or, for payment types and cashiers, the number of receipts. */
data class Ranked(val label: String, val quantity: Double, val amount: Long)

data class Statistics(
    /** Received money minus refunds, without tips; stornoed receipts left out. */
    val revenue: Long,
    val receipts: Int,
    val refunds: Long,
    val stornoCount: Int,
    val tips: Long,
    val itemsSold: Double,
    /** Revenue per day of the period, every day present (also days with no sales). */
    val byDay: List<Pair<LocalDate, Long>>,
    /** Revenue per hour of the day, 0–23. */
    val byHour: List<Long>,
    val byWeekday: Map<DayOfWeek, Long>,
    val topProducts: List<Ranked>,
    val byCategory: List<Ranked>,
    val byPayment: List<Ranked>,
    val byCashier: List<Ranked>,
    /** The previous period of the same length, for the comparison arrow; null when not given. */
    val previousRevenue: Long? = null,
) {
    val average: Long get() = if (receipts == 0) 0 else revenue / receipts
    val change: Double? get() = previousRevenue?.takeIf { it > 0 }?.let { (revenue - it) * 100.0 / it }

    companion object {
        fun of(
            sales: List<StatSale>, from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault(),
            previous: List<StatSale>? = null,
        ): Statistics {
            val valid = sales.filter { !it.cancelled }
            val normal = valid.filter { !it.refund }
            val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
            val dayOf = { s: StatSale -> Instant.ofEpochMilli(s.at).atZone(zone) }
            val perDay = valid.groupBy { dayOf(it).toLocalDate() }.mapValues { (_, l) -> l.sumOf { it.received } }
            val hours = LongArray(24)
            valid.forEach { hours[dayOf(it).hour] += it.received }
            val items = valid.flatMap { it.items }
            fun rank(groups: Map<String, List<StatItem>>) = groups.map { (k, l) -> Ranked(k, Qty.norm(l.sumOf { it.quantity }), l.sumOf { it.total }) }
                .filter { it.quantity != 0.0 || it.amount != 0L }.sortedByDescending { it.amount }
            return Statistics(
                revenue = valid.sumOf { it.received },
                receipts = normal.size,
                refunds = -valid.filter { it.refund }.sumOf { it.received },
                stornoCount = sales.count { it.cancelled },
                tips = valid.sumOf { it.tip },
                itemsSold = Qty.norm(items.sumOf { it.quantity }),
                byDay = days.map { it to (perDay[it] ?: 0L) },
                byHour = hours.toList(),
                byWeekday = DayOfWeek.entries.associateWith { d -> valid.filter { dayOf(it).dayOfWeek == d }.sumOf { it.received } },
                topProducts = rank(items.groupBy { it.name }),
                byCategory = rank(items.groupBy { it.category }),
                byPayment = valid.groupBy { it.payment }.map { (p, l) -> Ranked(p.label, l.size.toDouble(), l.sumOf { it.received }) }.sortedByDescending { it.amount },
                byCashier = valid.groupBy { it.cashier.ifBlank { "—" } }.map { (c, l) -> Ranked(c, l.size.toDouble(), l.sumOf { it.received }) }.sortedByDescending { it.amount },
                previousRevenue = previous?.filter { !it.cancelled }?.sumOf { it.received },
            )
        }
    }
}

/** Receipts from [fromCrowns] up to (not including) [toCrowns]; null = no upper limit. */
data class SizeBucket(val fromCrowns: Long, val toCrowns: Long?, val count: Long)

/** One receipt in the receipt statistics. */
data class ReceiptRow(val number: String, val at: Long, val amount: Long, val items: Double, val payment: PaymentMethod)

/**
 * Statistics about the receipts themselves rather than the money: how many, how big, when they are issued,
 * how many items they have, and the largest ones. Refunds and stornos are counted separately.
 */
data class ReceiptStatistics(
    val receipts: Int,
    val refunds: Int,
    val stornos: Int,
    val average: Long,
    val median: Long,
    val largest: List<ReceiptRow>,
    val smallest: Long,
    /** Items (pieces) per receipt on average, times 10 to keep one decimal place. */
    val itemsPerReceiptX10: Int,
    /** Receipts per day of the period, every day present. */
    val countByDay: List<Pair<LocalDate, Long>>,
    /** Receipts per hour of the day, 0–23. */
    val countByHour: List<Long>,
    val countByWeekday: Map<DayOfWeek, Long>,
    /** How many receipts fall into each size range. */
    val sizes: List<SizeBucket>,
    /** Payment type: quantity = number of receipts, amount = money. */
    val byPayment: List<Ranked>,
    val byCashier: List<Ranked>,
) {
    /** Hour with the most receipts, or null when there are none. */
    val busiestHour: Int? get() = countByHour.withIndex().filter { it.value > 0 }.maxByOrNull { it.value }?.index
    val busiestDay: Pair<LocalDate, Long>? get() = countByDay.filter { it.second > 0 }.maxByOrNull { it.second }

    companion object {
        /** Size buckets in haléře; the last one is open. */
        val SIZE_LIMITS = listOf(10_000L, 20_000L, 50_000L, 100_000L, 200_000L)

        fun of(sales: List<StatSale>, from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault()): ReceiptStatistics {
            val valid = sales.filter { !it.cancelled }
            val normal = valid.filter { !it.refund }
            val amounts = normal.map { it.received }.sorted()
            val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
            val at = { s: StatSale -> Instant.ofEpochMilli(s.at).atZone(zone) }
            val perDay = normal.groupingBy { at(it).toLocalDate() }.eachCount()
            val hours = LongArray(24)
            normal.forEach { hours[at(it).hour]++ }
            val pieces = normal.sumOf { s -> s.items.sumOf { it.quantity } }
            val median = when {
                amounts.isEmpty() -> 0L
                amounts.size % 2 == 1 -> amounts[amounts.size / 2]
                else -> (amounts[amounts.size / 2 - 1] + amounts[amounts.size / 2]) / 2
            }
            val bounds = (listOf(0L) + SIZE_LIMITS).zip(SIZE_LIMITS.map<Long, Long?> { it } + listOf(null))
            return ReceiptStatistics(
                receipts = normal.size,
                refunds = valid.count { it.refund },
                stornos = sales.count { it.cancelled },
                average = if (normal.isEmpty()) 0 else normal.sumOf { it.received } / normal.size,
                median = median,
                largest = normal.sortedByDescending { it.received }.take(10).map { s ->
                    ReceiptRow(s.number, s.at, s.received, Qty.norm(s.items.sumOf { it.quantity }), s.payment)
                },
                smallest = amounts.firstOrNull() ?: 0L,
                itemsPerReceiptX10 = if (normal.isEmpty()) 0 else ((pieces * 10.0) / normal.size).roundToInt(),
                countByDay = days.map { it to (perDay[it] ?: 0).toLong() },
                countByHour = hours.toList(),
                countByWeekday = DayOfWeek.entries.associateWith { d -> normal.count { at(it).dayOfWeek == d }.toLong() },
                sizes = bounds.map { (low, high) ->
                    SizeBucket(low / 100, high?.let { it / 100 }, normal.count { it.received >= low && (high == null || it.received < high) }.toLong())
                },
                byPayment = normal.groupBy { it.payment }.map { (p, l) -> Ranked(p.label, l.size.toDouble(), l.sumOf { it.received }) }.sortedByDescending { it.quantity },
                byCashier = normal.groupBy { it.cashier.ifBlank { "—" } }.map { (c, l) -> Ranked(c, l.size.toDouble(), l.sumOf { it.received }) }.sortedByDescending { it.quantity },
            )
        }
    }
}
