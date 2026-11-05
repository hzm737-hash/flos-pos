package cz.flos.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CartTest {
    @Test fun addsAndMergesSameProduct() {
        val c = Cart().add(1, "Káva", 6500, VatRate.REDUCED).add(1, "Káva", 6500, VatRate.REDUCED)
        assertEquals(1, c.items.size)
        assertEquals(2.0, c.itemCount, 1e-9)
        assertEquals(13000L, c.total)
    }

    @Test fun vatBreakdownMatchesTotalWithDiscount() {
        val c = Cart()
            .add(1, "Káva", 6500, VatRate.REDUCED, 3.0)
            .add(2, "Dort", 8900, VatRate.REDUCED)
            .add(3, "Pivo", 5900, VatRate.STANDARD, 2.0)
            .setDiscount(10)
        val vat = c.vatBreakdown()
        assertEquals(c.total, vat.sumOf { it.gross })
        vat.forEach { assertEquals(it.gross, it.base + it.vat) }
    }

    @Test fun vatSplit() {
        val v = Vat.split(12100, VatRate.STANDARD)
        assertEquals(10000L, v.base)
        assertEquals(2100L, v.vat)
    }
}
