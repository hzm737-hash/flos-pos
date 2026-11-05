package cz.flos.core

import cz.flos.core.bank.CzechBanks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

class Round5Test {
    @Test fun findsBankFromAccount() {
        assertEquals("ČSOB", CzechBanks.forAccount("123456789/0300")?.name)
        assertEquals("Fio banka", CzechBanks.forAccount("19-2000145399/2010")?.name)
        assertEquals(true, CzechBanks.forAccount("19-2000145399/2010")?.directApi)
        assertEquals("Česká spořitelna", CzechBanks.forAccount("CZ65 0800 0000 1920 0014 5399")?.name)
        assertEquals("Banka 1234", CzechBanks.forAccount("1/1234")?.name)
        assertNull(CzechBanks.forAccount("123456"))
    }

    private fun ean(first12: String) = first12 + ScaleBarcode.checkDigit(first12)

    @Test fun readsScaleLabels() {
        val s = ScaleSettings(weightPrefixes = "28", pricePrefixes = "29", pluDigits = 5)
        val w = ScaleBarcode.parse(ean("280123401250"), s) as ScaleCode.Weight
        assertEquals("01234", w.plu)
        assertEquals(1250, w.grams)
        assertEquals(1.25, ScaleBarcode.quantity(w, 29900), 1e-9)
        assertEquals(listOf("01234", "1234"), ScaleBarcode.pluCandidates(w))

        val p = ScaleBarcode.parse(ean("290000704990"), s) as ScaleCode.Price
        assertEquals("00007", p.plu)
        assertEquals(4990L, p.price)
        assertEquals(0.5, ScaleBarcode.quantity(p, 9980), 1e-9)

        val good = ean("280123401250")
        val wrong = good.dropLast(1) + ((good.last() - '0' + 1) % 10)
        assertNull(ScaleBarcode.parse(wrong, s))
        assertNull(ScaleBarcode.parse(ean("590123401250"), s))
        assertNull(ScaleBarcode.parse(good, ScaleSettings()))
        assertEquals(3, ScaleBarcode.checkDigit("859400000001"))
    }

    @Test fun serviceTypesAndPriceLists() {
        val types = Pricing.parseServiceTypes("Na místě\nS sebou: -5 %\nRozvoz: +10 %, ceník Rozvoz\n\n")
        assertEquals(3, types.size)
        assertEquals(ServiceType("Na místě"), types[0])
        assertEquals(-5.0, types[1].percent, 1e-9)
        assertEquals("Rozvoz", types[2].priceList)

        val prices = "Rozvoz: 89\nZaměstnanci: 45,50"
        assertEquals(mapOf("rozvoz" to 8900L, "zamestnanci" to 4550L), Pricing.parsePrices(prices))
        assertEquals(9790L, Pricing.unitPrice(7900, prices, types[2].priceList, types[2]))
        assertEquals(7505L, Pricing.unitPrice(7900, prices, null, types[1]))
        assertEquals(4550L, Pricing.unitPrice(7900, prices, "zaměstnanci", null))
        assertEquals(7900L, Pricing.unitPrice(7900, null, "Rozvoz", null))
        assertEquals(listOf("Rozvoz", "Zaměstnanci"), Pricing.priceListNames(listOf(prices, null), types))
    }

    @Test fun happyHour() {
        val rules = Pricing.parseRules(
            "Happy hour: 20 %, 15:00-17:00, Po-Pá, kategorie Nápoje\n" +
                "Akce pivo: 10 %, produkt Pivo 0,5 l\n" +
                "Prosinec: 5 %, 1.12.2026-31.12.2026, služba S sebou\nbez procent: kategorie X",
        )
        assertEquals(3, rules.size)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY), rules[0].days)
        val thu16 = LocalDateTime.of(2026, 11, 5, 16, 0) // Thursday
        val sat16 = LocalDateTime.of(2026, 11, 7, 16, 0)
        assertEquals(20, Pricing.discount(rules, "Pivo 0,5 l", "Nápoje", null, thu16)?.percent)
        assertEquals(10, Pricing.discount(rules, "Pivo 0,5 l", "Nápoje", null, sat16)?.percent)
        assertNull(Pricing.discount(rules, "Pizza", "Jídlo", null, thu16))
        assertNull(Pricing.discount(rules, "Káva", "napoje", null, LocalDateTime.of(2026, 11, 5, 17, 0)))
        assertEquals(5, Pricing.discount(rules, "Pizza", "Jídlo", "s sebou", LocalDateTime.of(2026, 12, 24, 12, 0))?.percent)
        assertNull(Pricing.discount(rules, "Pizza", "Jídlo", "S sebou", LocalDateTime.of(2027, 1, 1, 12, 0)))
        assertEquals(LocalDate.of(2026, 12, 31), rules[2].endDate)
    }

    @Test fun mixedPayment() {
        val parts = listOf(PaymentPart(PaymentMethod.MEAL_VOUCHER, 15000), PaymentPart(PaymentMethod.CASH, 4900))
        assertEquals(parts, PaymentPart.parse(PaymentPart.format(parts)))
        assertEquals(PaymentMethod.MEAL_VOUCHER, PaymentPart.main(parts))

        val cart = Cart().add(1, "Menu", 19900, VatRate.REDUCED)
        val receipt = Receipt("2026-000001", LocalDateTime.of(2026, 11, 5, 12, 0), "Eva", cart, PaymentMethod.MEAL_VOUCHER, cashGiven = 5000, parts = parts)
        assertEquals(19900L, receipt.cashDue)
        val text = ReceiptLayout(32).layout(BusinessInfo("Bistro"), receipt).filterIsInstance<ReceiptBlock.Line>().joinToString("\n") { it.text }
        assertTrue(text.contains("Platba kombinovaná"))
        assertTrue(text.contains("Stravenky"))
        assertTrue(text.contains("Vráceno"))

        val report = DailyReport.of(LocalDate.of(2026, 11, 5), listOf(
            SaleSummary(19900, PaymentMethod.MEAL_VOUCHER, cart.vatBreakdown(), parts = parts),
            SaleSummary(10000, PaymentMethod.CASH, cart.vatBreakdown()),
        ))
        assertEquals(14900L, report.byPayment[PaymentMethod.CASH])
        assertEquals(15000L, report.byPayment[PaymentMethod.MEAL_VOUCHER])
    }
}
