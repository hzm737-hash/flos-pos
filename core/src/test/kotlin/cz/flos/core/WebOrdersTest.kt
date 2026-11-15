package cz.flos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WebOrdersTest {
    private val line = WebOrderLine(10, "Diavola", qty = 1, unitPrice = 220)
    private val order = WebOrder(
        WebOrders.DELIVERY, listOf(line), WebCustomer("Jan Novák", "777 123 456", "Ostrožná 12"),
        deliveryFee = 50, total = 270, agreedTerms = true,
    )

    @Test fun halfAndHalfCostsHalfOfEachPizza() {
        assertEquals(18000, WebOrders.unitPrice(22000, 14000, emptyList()))
        assertEquals(22000, WebOrders.unitPrice(22000, null, emptyList()))
    }

    @Test fun extrasOnAHalfCostHalfExceptTheAlwaysFullOnes() {
        val extras = listOf(
            WebExtra("Burrata", 80, half = 0, full = true),   // whole pizza: 80
            WebExtra("Rukola", 30, half = 2),                 // one half: 15
            WebExtra("Bufala", 70, half = 1, full = true),    // one half but always full: 70
            WebExtra("Houby", 30),                            // whole pizza: 30
        )
        assertEquals(18000 + 8000 + 1500 + 7000 + 3000, WebOrders.unitPrice(22000, 14000, extras))
    }

    @Test fun lineNameShowsHalvesExtrasAndNote() {
        val l = line.copy(half = 1, halfName = "Margherita", note = "dobře propečené",
            extras = listOf(WebExtra("Burrata", 80, full = true), WebExtra("Rukola", 30, half = 2)))
        assertEquals("½ Diavola + ½ Margherita + Burrata, + Rukola (½ Margherita) · dobře propečené", WebOrders.lineName(l))
    }

    @Test fun matchesByCodeThenNameThenNumberedName() {
        val products = listOf(
            WebOrders.Candidate(1, "Margherita", null),
            WebOrders.Candidate(2, "10. Diavola", null),
            WebOrders.Candidate(3, "Quattro formaggi", "8"),
            WebOrders.Candidate(4, "Rúcola & Olive", null),
        )
        fun m(n: Int, name: String) = WebOrders.match(n, name, products) { it }?.id
        assertEquals(1L, m(1, "MARGHERITA"))
        assertEquals(2L, m(10, "Diavola"))
        assertEquals(3L, m(8, "Quattro Formaggi"))
        assertEquals(4L, m(51, "Rucola & Olive"))
        assertNull(m(99, "Neznámá"))
        assertNull(m(11, "Diavola")) // a numbered name must have the right number
    }

    @Test fun validation() {
        assertNull(WebOrders.problem(order))
        assertNotNull(WebOrders.problem(order.copy(agreedTerms = false)))
        assertNotNull(WebOrders.problem(order.copy(type = "TABLE")))
        assertNotNull(WebOrders.problem(order.copy(customer = order.customer!!.copy(phone = "12"))))
        assertNotNull(WebOrders.problem(order.copy(customer = order.customer!!.copy(street = ""))))
        assertNull(WebOrders.problem(order.copy(type = WebOrders.PICKUP, customer = order.customer!!.copy(street = ""))))
        assertNotNull(WebOrders.problem(order.copy(lines = listOf(line.copy(qty = 0)))))
        assertNotNull(WebOrders.problem(order.copy(time = "25:00")))
        assertNull(WebOrders.problem(order.copy(time = "19:30")))
        // A topping on one half needs a half & half pizza.
        assertNotNull(WebOrders.problem(order.copy(lines = listOf(line.copy(extras = listOf(WebExtra("Rukola", 30, half = 1)))))))
        assertEquals("The order is empty.", WebOrders.problem(order.copy(lines = emptyList(), lang = "en")))
    }

    @Test fun preorderForALaterDayAndQrPayment() {
        val today = java.time.LocalDate.now(OrderHours.ZONE)
        val tomorrow = today.plusDays(1)
        assertNull(WebOrders.problem(order.copy(payment = "qr")))
        assertNotNull(WebOrders.problem(order.copy(payment = "bitcoin")))
        assertNull(WebOrders.problem(order.copy(date = tomorrow.toString(), time = "18:30")))
        assertNotNull(WebOrders.problem(order.copy(date = tomorrow.toString())))                 // a pre-order needs a time
        assertNotNull(WebOrders.problem(order.copy(date = today.minusDays(1).toString(), time = "18:30")))
        assertNotNull(WebOrders.problem(order.copy(date = today.plusDays(30).toString(), time = "18:30")))
        assertNotNull(WebOrders.problem(order.copy(date = "16.11.", time = "18:30")))
        assertNull(WebOrders.problem(order.copy(date = today.toString())))                       // today, as soon as possible
        assert(WebOrders.isPreorder(order.copy(date = tomorrow.toString(), time = "18:30"), today))
        assert(!WebOrders.isPreorder(order.copy(date = today.toString()), today))
        assertEquals("PŘEDOBJEDNÁVKA po 16.11. 18:30",
            WebOrders.wishText(order.copy(date = "2026-11-16", time = "18:30"), java.time.LocalDate.of(2026, 11, 14)))
        assertEquals("Platba: QR platbou při převzetí", WebOrders.summary(5, order.copy(payment = "qr"))[3])
        assert(WebOrders.confirmation(5, order.copy(payment = "qr", lang = "en"), "Mon 16.11. 18:30", "PF", "").contains("QR payment"))
    }

    @Test fun wishedTimeBecomesMinutesFromNow() {
        assertEquals(30, WebOrders.wishMinutes("19:30", 19 * 60))
        assertNull(WebOrders.wishMinutes("", 19 * 60))
        assertNull(WebOrders.wishMinutes("18:00", 19 * 60))   // already past
        assertNull(WebOrders.wishMinutes("25:00", 19 * 60))
    }

    @Test fun confirmationInTheCustomersLanguage() {
        val cs = WebOrders.confirmation(12, order, "19:45", "Pizza Fresca", "722 272 252")
        assertEquals("Dobrý den Jan Novák, potvrzujeme Vaši objednávku č. WEB 12. Doručíme ji přibližně v 19:45. Platba hotově při převzetí. Děkujeme! Pizza Fresca, tel. 722 272 252", cs)
        val en = WebOrders.confirmation(12, order.copy(type = WebOrders.PICKUP, payment = "card", lang = "en"), "19:45", "Pizza Fresca", "")
        assertEquals("Hello Jan Novák, we confirm your order no. WEB 12. It will be ready for pick-up at 19:45. Payment by card on receipt. Thank you! Pizza Fresca", en)
    }

    @Test fun cleaningRemovesControlCharactersAndTheNoteSeparator() {
        val dirty = order.copy(note = "zvonit\u0007 2×", lines = listOf(line.copy(note = "a · b")))
        val c = WebOrders.cleaned(dirty)
        assertEquals("zvonit 2×", c.note)
        assertEquals("a  b", c.lines[0].note)
    }

    @Test fun tableOrderNeedsTableAndKeyOnly() {
        val o = WebOrder(WebOrders.TABLE, listOf(WebOrderLine(3, "Diavola", qty = 1)), table = "5", tableKey = "abc")
        assertNull(WebOrders.problem(o))
        assertNotNull(WebOrders.problem(o.copy(table = "")))
        assertNotNull(WebOrders.problem(o.copy(tableKey = "")))
        assertEquals(listOf("WEB 9 · Stůl 5"), WebOrders.summary(9, o))
    }

    @Test fun websiteLinkKeepsTableAndKey() {
        val flos = "https://flos.gnocco.cz/menu/ab12?t=Terasa%201&k=XyZ"
        assertEquals("https://pizzafresca.cz/menu?t=Terasa%201&k=XyZ", TableOrders.websiteLink("https://pizzafresca.cz/menu", flos))
        assertEquals("https://pizzafresca.cz/?lang=cs&t=Terasa%201&k=XyZ", TableOrders.websiteLink(" https://pizzafresca.cz/?lang=cs#menu ", flos))
        assertEquals(flos, TableOrders.websiteLink("", flos))
        assertEquals(flos, TableOrders.websiteLink("javascript:alert(1)", flos))
    }

    @Test fun ticketNoteHasCustomerAddressTimePaymentAndNote() {
        val today = java.time.LocalDate.of(2026, 11, 15)
        val o = order.copy(customer = WebCustomer("Jan Novák", "777 123 456", "Ostrožná 12", "Novák 2. patro"), time = "18:30", payment = "card", note = "Bez cibule")
        val t = WebOrders.ticketNote("WEB 7 · Rozvoz · hotovo 18:30", o, today)
        assertEquals(
            listOf("WEB 7 · Rozvoz · hotovo 18:30", "Zákazník: Jan Novák", "Tel.: 777 123 456",
                "Adresa: Ostrožná 12 (zvonek: Novák 2. patro)", "Přeje si: 18:30", "Platba: kartou při převzetí", "Pozn.: Bez cibule"),
            t.split('\n'),
        )
        // Pick-up: no address.
        val p = WebOrders.ticketNote("WEB 8", o.copy(type = WebOrders.PICKUP, time = ""), today).split('\n')
        assertEquals(listOf("WEB 8", "Zákazník: Jan Novák", "Tel.: 777 123 456", "Přeje si: co nejdříve", "Platba: kartou při převzetí", "Pozn.: Bez cibule"), p)
    }

    @Test fun paidOrderKeepsNoCustomerDetails() {
        val t = WebOrders.ticketNote("WEB 7 · Rozvoz", order, java.time.LocalDate.of(2026, 11, 15))
        assertEquals("WEB 7 · Rozvoz\nPřeje si: co nejdříve\nPlatba: hotově při převzetí", WebOrders.withoutCustomer(t))
        // Other notes are left alone.
        assertEquals("Tel.: 123\nZákazník: X", WebOrders.withoutCustomer("Tel.: 123\nZákazník: X"))
        assertEquals("WEB 9 · Stůl 4", WebOrders.withoutCustomer("WEB 9 · Stůl 4"))
    }

    @Test fun lineBreaksInANotePrintAsSeparateLines() {
        assertEquals(listOf("WEB 7", "Zákazník: Jan", "Tel.: 777"), ReceiptLayout.wrap("WEB 7\nZákazník: Jan\nTel.: 777", 32))
        assertEquals(listOf("one two", "three"), ReceiptLayout.wrap("one two three", 8))
    }
}
