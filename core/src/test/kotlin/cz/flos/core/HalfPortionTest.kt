package cz.flos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HalfPortionTest {
    private fun eq(expected: Double, actual: Double) = assertEquals(expected, actual, 1e-9)

    @Test fun formatsAndParsesQuantities() {
        assertEquals("1", Qty.format(1.0))
        assertEquals("0,5", Qty.format(0.5))
        assertEquals("1,25", Qty.format(1.25))
        assertEquals("-0,5", Qty.format(-0.5))
        assertEquals("0,333", Qty.format(1.0 / 3))
        eq(0.5, Qty.parse("0,5")!!)
        eq(0.5, Qty.parse("0.5")!!)
        eq(0.5, Qty.parse("1/2")!!)
        eq(0.5, Qty.parse("½")!!)
        eq(1.5, Qty.parse("1½")!!)
        eq(1.5, Qty.parse("1 1/2")!!)
        eq(3.0, Qty.parse(" 3 ")!!)
        assertNull(Qty.parse(""))
        assertNull(Qty.parse("0"))
        assertNull(Qty.parse("-1"))
        assertNull(Qty.parse("abc"))
        assertNull(Qty.parse("1/0"))
    }

    @Test fun halfPortionPricesAndVat() {
        // Half a soup at 89 Kč and one and a half beers at 55 Kč.
        val c = Cart()
            .add(1, "Polévka", 8900, VatRate.REDUCED, 0.5)
            .add(2, "Pivo", 5500, VatRate.STANDARD, 1.5)
        assertEquals(4450L, c.items[0].total)
        assertEquals(8250L, c.items[1].total)
        assertEquals(12700L, c.total)
        eq(2.0, c.itemCount)
        assertEquals(c.total, c.vatBreakdown().sumOf { it.gross })
    }

    @Test fun oddHalfRoundsToWholeHaler() {
        // 0,5 × 12,35 Kč = 6,175 Kč → 6,18 Kč
        val c = Cart().add(1, "Rohlík", 1235, VatRate.REDUCED, 0.5)
        assertEquals(618L, c.total)
    }

    @Test fun halvesAddUpWithoutDrift() {
        var c = Cart()
        repeat(10) { c = c.add(1, "Káva", 6000, VatRate.REDUCED, 0.1) }
        assertEquals(1, c.items.size)
        eq(1.0, c.items[0].quantity)
        assertEquals(6000L, c.total)
    }

    @Test fun setQuantityToFractionAndRemoveAtZero() {
        val c = Cart().add(1, "Pizza", 20000, VatRate.REDUCED)
        val half = c.setQuantity(0, 0.5)
        assertEquals(10000L, half.total)
        assertTrue(half.setQuantity(0, 0.0).isEmpty())
        eq(1.5, Qty.stepUp(0.5))
        eq(0.0, Qty.stepDown(0.5))
        eq(2.5, Qty.stepUp(2.0, max = 2.5))
    }

    @Test fun receiptShowsDecimalComma() {
        val business = BusinessInfo(name = "Bistro", addressLines = listOf("Praha"), ico = "12345678", dic = "CZ12345678", vatPayer = true)
        val cart = Cart().add(1, "Polévka", 8900, VatRate.REDUCED, 0.5)
        val r = Receipt(number = "1", createdAt = java.time.LocalDateTime.of(2026, 11, 5, 12, 0), cashier = "", cart = cart, payment = PaymentMethod.CASH)
        val text = ReceiptLayout.toPlainText(ReceiptLayout(32).layout(business, r), 32)
        assertTrue(text, text.contains("0,5 x 89,00"))
        assertTrue(text, text.contains("44,50"))
    }
}
