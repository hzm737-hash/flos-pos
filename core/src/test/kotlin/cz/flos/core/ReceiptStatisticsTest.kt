package cz.flos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

class ReceiptStatisticsTest {
    private val zone = ZoneId.of("Europe/Prague")
    private fun at(day: Int, hour: Int) = LocalDate.of(2027, 3, day).atTime(hour, 15).atZone(zone).toInstant().toEpochMilli()
    private fun sale(number: String, day: Int, hour: Int, amount: Long, pieces: Int, payment: PaymentMethod = PaymentMethod.CASH,
                     cancelled: Boolean = false, refund: Boolean = false) =
        StatSale(at(day, hour), amount, 0, payment, "Eva", cancelled, listOf(StatItem("X", "Y", pieces.toDouble(), amount)), refund, number)

    @Test fun countsSizesHoursAndLargest() {
        val sales = listOf(
            sale("1", 1, 9, 5_000, 1),
            sale("2", 1, 9, 15_000, 2, PaymentMethod.CARD),
            sale("3", 2, 12, 60_000, 4, PaymentMethod.CARD),
            sale("4", 2, 12, 250_000, 9),
            sale("5", 2, 13, 90_000, 3, cancelled = true),
            sale("6", 2, 14, -5_000, -1, refund = true),
        )
        val r = ReceiptStatistics.of(sales, LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 3), zone)
        assertEquals(4, r.receipts)
        assertEquals(1, r.refunds)
        assertEquals(1, r.stornos)
        assertEquals(82_500L, r.average)
        assertEquals(37_500L, r.median)
        assertEquals(5_000L, r.smallest)
        assertEquals(40, r.itemsPerReceiptX10)
        assertEquals(listOf("4", "3", "2", "1"), r.largest.map { it.number })
        assertEquals(listOf(2L, 2L, 0L), r.countByDay.map { it.second })
        assertEquals(2L, r.countByHour[9])
        assertEquals(2L, r.countByHour[12])
        assertEquals(9, r.busiestHour)
        assertEquals(2L, r.countByWeekday[DayOfWeek.MONDAY])
        assertEquals(listOf(1L, 1L, 0L, 1L, 0L, 1L), r.sizes.map { it.count })
        assertEquals(2000L, r.sizes.last().fromCrowns)
        assertNull(r.sizes.last().toCrowns)
        assertEquals(2.0, r.byPayment.first { it.label == PaymentMethod.CARD.label }.quantity, 1e-9)
    }

    @Test fun emptyPeriod() {
        val r = ReceiptStatistics.of(emptyList(), LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 1), zone)
        assertEquals(0, r.receipts)
        assertEquals(0L, r.median)
        assertNull(r.busiestHour)
        assertNull(r.busiestDay)
    }
}
