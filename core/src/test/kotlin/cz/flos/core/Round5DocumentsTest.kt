package cz.flos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class Round5DocumentsTest {
    @Test fun cartTextRoundTrip() {
        val cart = Cart().add(12, "Pizza\tvelká", 15900, VatRate.REDUCED, 2.0).add(3, "Víno", 9900, VatRate.STANDARD, 0.5, 10).setDiscount(5)
        val back = CartText.decode(CartText.encode(cart))
        assertEquals(cart.total, back.total)
        assertEquals(5, back.discountPercent)
        assertEquals("Pizza velká", back.items[0].name)
        assertEquals(0.5, back.items[1].quantity, 1e-9)
        assertEquals(10, back.items[1].discountPercent)
        assertTrue(CartText.decode("rubbish\n").isEmpty())
    }

    @Test fun documentNumbers() {
        assertEquals("CN-2026-0001", DocumentNumbers.next(DocumentKind.QUOTE, 2026, emptyList()))
        assertEquals("ZF-2026-0008", DocumentNumbers.next(DocumentKind.PROFORMA, 2026, listOf("ZF-2026-0007", "ZF-2025-0099", "CN-2026-0050")))
        assertEquals("20260007", DocumentNumbers.variableSymbol("ZF-2026-0007"))
    }

    @Test fun printsQuoteAndProforma() {
        val cart = Cart().add(1, "Catering", 250000, VatRate.REDUCED)
        val until = LocalDate.of(2026, 11, 30)
        fun text(kind: DocumentKind, qr: QrPlatba? = null) = ReceiptLayout(32).layout(BusinessInfo("Bistro"),
            Receipt("CN-2026-0001", LocalDateTime.of(2026, 11, 5, 12, 0), "Eva", cart, PaymentMethod.CARD, qr = qr, kind = kind, validUntil = until))
            .filterIsInstance<ReceiptBlock.Line>().joinToString("\n") { it.text }
        val quote = text(DocumentKind.QUOTE)
        assertTrue(quote.contains("CENOVÁ NABÍDKA"))
        assertTrue(quote.contains("Ceny platí do: 30.11.2026"))
        assertTrue(quote.contains("Není daňový doklad"))
        assertFalse(quote.contains("Účtenka bude vystavena"))
        val pro = text(DocumentKind.PROFORMA, QrPlatba(iban = "CZ6508000000192000145399", amount = 250000, variableSymbol = "20260001", message = "x"))
        assertTrue(pro.contains("ZÁLOHOVÁ FAKTURA"))
        assertTrue(pro.contains("VS: 20260001"))
        assertTrue(text(DocumentKind.SALES_ORDER).contains("Termín: 30.11.2026"))
    }

    @Test fun bookingClashes() {
        val at = LocalDateTime.of(2026, 11, 6, 19, 0)
        val a = Booking(1, "5", at, 120)
        val b = Booking(2, "5", at.plusMinutes(90), 60)
        val c = Booking(3, "5", at.plusMinutes(120), 60)
        val d = Booking(4, "6", at, 60)
        val gone = Booking(5, "5", at, 60, active = false)
        assertEquals(listOf(b), Bookings.conflicts(a, listOf(a, b, c, d, gone)))
        assertTrue(Bookings.conflicts(Booking(9, null, at, 60), listOf(a)).isEmpty())
        assertEquals(a, Bookings.upcoming("5", listOf(c, a), at.minusMinutes(60)))
        assertNull(Bookings.upcoming("5", listOf(a), at.minusHours(5)))
        assertEquals(c, Bookings.upcoming("5", listOf(a, c), at.plusMinutes(125)))
        assertEquals(mapOf(LocalDate.of(2026, 11, 6) to 6), Bookings.guestsByDay(listOf(a to 4, d to 2, gone to 8)))
    }

    @Test fun waiters() {
        val names = Waiters.names(listOf("Eva", "Petr"), "petr, Jana\n", listOf("Karel"))
        assertEquals(listOf("Eva", "Petr", "Jana", "Karel"), names)
        val away = Waiters.away(Waiters.setAway("", "Jana", true))
        assertEquals(setOf("Jana"), away)
        assertEquals("", Waiters.setAway("Jana", "Jana", false))
        val bills = mapOf("Eva" to 3, "Petr" to 1, "Jana" to 0)
        assertEquals("Petr", Waiters.suggest("Eva", names, away, { bills[it] ?: 5 }))
    }
}
