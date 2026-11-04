package cz.flos.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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
)

data class StatItem(val name: String, val category: String, val quantity: Int, val total: Long)

data class Ranked(val label: String, val quantity: Int, val amount: Long)

data class Statistics(
    /** Received money minus refunds, without tips; stornoed receipts left out. */
    val revenue: Long,
    val receipts: Int,
    val refunds: Long,
    val stornoCount: Int,
    val tips: Long,
    val itemsSold: Int,
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
            fun rank(groups: Map<String, List<StatItem>>) = groups.map { (k, l) -> Ranked(k, l.sumOf { it.quantity }, l.sumOf { it.total }) }
                .filter { it.quantity != 0 || it.amount != 0L }.sortedByDescending { it.amount }
            return Statistics(
                revenue = valid.sumOf { it.received },
                receipts = normal.size,
                refunds = -valid.filter { it.refund }.sumOf { it.received },
                stornoCount = sales.count { it.cancelled },
                tips = valid.sumOf { it.tip },
                itemsSold = items.sumOf { it.quantity },
                byDay = days.map { it to (perDay[it] ?: 0L) },
                byHour = hours.toList(),
                byWeekday = DayOfWeek.entries.associateWith { d -> valid.filter { dayOf(it).dayOfWeek == d }.sumOf { it.received } },
                topProducts = rank(items.groupBy { it.name }),
                byCategory = rank(items.groupBy { it.category }),
                byPayment = valid.groupBy { it.payment }.map { (p, l) -> Ranked(p.label, l.size, l.sumOf { it.received }) }.sortedByDescending { it.amount },
                byCashier = valid.groupBy { it.cashier.ifBlank { "—" } }.map { (c, l) -> Ranked(c, l.size, l.sumOf { it.received }) }.sortedByDescending { it.amount },
                previousRevenue = previous?.filter { !it.cancelled }?.sumOf { it.received },
            )
        }
    }
}
