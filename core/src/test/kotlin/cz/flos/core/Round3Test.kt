package cz.flos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class Round3Test {
    @Test fun parsesOptions() {
        val g = ProductOptions.parse("Velikost: Malá, Střední +20, Velká +40\nPřílohy*: Sýr +15, Slanina +12,50, Bez cibule\nnesmysl\nPrázdná:")
        assertEquals(2, g.size)
        assertEquals("Velikost", g[0].name)
        assertEquals(false, g[0].multiple)
        assertEquals(listOf(OptionChoice("Malá", 0), OptionChoice("Střední", 2000), OptionChoice("Velká", 4000)), g[0].choices)
        assertEquals(true, g[1].multiple)
        assertEquals(OptionChoice("Slanina", 1250), g[1].choices[1])
        assertEquals(OptionChoice("Bez cibule", 0), g[1].choices[2])

        val (name, price) = ProductOptions.apply("Pizza", 18900, g, mapOf(0 to setOf(2), 1 to setOf(0, 1)))
        assertEquals("Pizza (Velká, Sýr, Slanina)", name)
        assertEquals(18900L + 4000 + 1500 + 1250, price)
        assertEquals(mapOf(0 to setOf(0)), ProductOptions.defaults(g))
        assertEquals(g, ProductOptions.parse(ProductOptions.format(g)))
    }

    @Test fun variantsStayOnSeparateLines() {
        val c = Cart().add(1, "Pizza (Malá)", 18900, VatRate.REDUCED).add(1, "Pizza (Velká)", 18900, VatRate.REDUCED)
            .add(1, "Pizza (Malá)", 18900, VatRate.REDUCED)
        assertEquals(2, c.items.size)
        assertEquals(2.0, c.items[0].quantity, 1e-9)
    }

    @Test fun statistics() {
        val zone = ZoneId.of("Europe/Prague")
        fun at(d: Int, h: Int) = LocalDateTime.of(2026, 11, d, h, 0).atZone(zone).toInstant().toEpochMilli()
        val sales = listOf(
            StatSale(at(2, 9), 10000, 0, PaymentMethod.CASH, "Eva", false, listOf(StatItem("Káva", "Nápoje", 2.0, 10000))),
            StatSale(at(2, 12), 30000, 2000, PaymentMethod.CARD, "Jan", false, listOf(StatItem("Pizza", "Jídlo", 1.0, 20000), StatItem("Káva", "Nápoje", 2.0, 10000))),
            StatSale(at(3, 12), 50000, 0, PaymentMethod.CARD, "Jan", true, listOf(StatItem("Pizza", "Jídlo", 2.0, 50000))),
            StatSale(at(4, 12), -5000, 0, PaymentMethod.CASH, "Eva", false, listOf(StatItem("Káva", "Nápoje", -1.0, -5000)), refund = true),
        )
        val s = Statistics.of(sales, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 4), zone, previous = listOf(sales[0]))
        assertEquals(35000L, s.revenue)
        assertEquals(2, s.receipts)
        assertEquals(5000L, s.refunds)
        assertEquals(1, s.stornoCount)
        assertEquals(2000L, s.tips)
        assertEquals(4, s.byDay.size)
        assertEquals(40000L, s.byDay[1].second)
        assertEquals(0L, s.byDay[0].second)
        assertEquals(25000L, s.byHour[12])
        assertEquals(Ranked("Pizza", 1.0, 20000), s.topProducts[0])
        assertEquals(Ranked("Káva", 3.0, 15000), s.topProducts[1])
        assertEquals("Jan", s.byCashier[0].label)
        assertEquals(17500L, s.average)
        assertEquals(250.0, s.change!!, 0.01)
    }

    @Test fun refundReceipt() {
        val cart = Cart(listOf(CartItem(1, "Káva", 5000, VatRate.REDUCED, -2.0)))
        val r = Receipt("2026-000010", LocalDateTime.of(2026, 11, 4, 10, 0), "", cart, PaymentMethod.CARD, refundOf = "2026-000007")
        val text = ReceiptLayout.toPlainText(ReceiptLayout(32).layout(BusinessInfo("Test"), r), 32)
        assertTrue(text, text.contains("VRATKA k účtence 2026-000007"))
        assertTrue(text, text.contains("-100,00"))
    }
}
