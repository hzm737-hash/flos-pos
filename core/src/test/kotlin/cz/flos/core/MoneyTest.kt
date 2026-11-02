package cz.flos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test fun formats() {
        assertEquals("1 234,50 Kč", Money.format(123450))
        assertEquals("-0,05 Kč", Money.format(-5))
        assertEquals("480.50", Money.toDecimal(48050))
    }

    @Test fun parses() {
        assertEquals(1250L, Money.parse("12,5"))
        assertEquals(123450L, Money.parse("1 234,50"))
        assertEquals(1200L, Money.parse("12"))
        assertNull(Money.parse("12,345"))
        assertNull(Money.parse("abc"))
    }

    @Test fun cashRounding() {
        assertEquals(1200L, Money.roundToCrowns(1149 + 1))
        assertEquals(1100L, Money.roundToCrowns(1149))
        assertEquals(1100L, Money.roundToCrowns(1100))
    }
}
