package cz.flos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class OrderHoursTest {
    private fun at(s: String) = ZonedDateTime.parse("$s+01:00[Europe/Prague]")
    private fun ms(s: String) = at(s).toInstant().toEpochMilli()
    private val week = listOf("11:00-20:30", "11:00-20:30", "11:00-14:00, 17:00-20:30", "11:00-20:30", "11:00-20:30", "18:00-02:00", "")

    @Test fun readsTimes() {
        assertEquals(listOf(660 until 1230), OrderHours.parseDay("11:00-20:30"))
        assertEquals(listOf(660 until 1230), OrderHours.parseDay("11.00 – 20.30"))
        assertEquals(listOf(660 until 1230), OrderHours.parseDay("11-20:30"))
        assertEquals(listOf(1080 until 1560), OrderHours.parseDay("18:00-02:00"))
        assertEquals(emptyList<IntRange>(), OrderHours.parseDay(""))
        assertNull(OrderHours.parseDay("od rána"))
        assertNull(OrderHours.parseDay("25:00-26:00"))
        assertNull(OrderHours.problem(week))
        assertNotNull(OrderHours.problem(week.dropLast(1) + "zavřeno"))
    }

    @Test fun openAndClosed() {
        // 2026-11-16 is a Monday.
        assertTrue(OrderHours.isOpen(week, at("2026-11-16T20:29")))
        assertFalse(OrderHours.isOpen(week, at("2026-11-16T20:30")))
        assertFalse(OrderHours.isOpen(week, at("2026-11-18T15:00"))) // Wednesday lunch break
        assertTrue(OrderHours.isOpen(week, at("2026-11-21T23:00"))) // Saturday evening
        assertTrue(OrderHours.isOpen(week, at("2026-11-22T01:30"))) // Saturday's hours run past midnight into Sunday
        assertFalse(OrderHours.isOpen(week, at("2026-11-22T12:00"))) // Sunday closed
    }

    @Test fun messagesSayWhenItOpens() {
        val c = OrderControl(qrHoursOn = true, qrHours = week)
        val late = OrderHours.qrStatus(c, ms("2026-11-16T20:45"))
        assertFalse(late.open)
        assertEquals("hours", late.reason)
        assertEquals("Teď objednávky nepřijímáme. Objednat můžete zítra od 11:00.", late.message)
        assertEquals("We are not taking orders right now. You can order tomorrow from 11:00.", late.messageEn)
        assertEquals(ms("2026-11-17T11:00"), late.opensAt)
        assertEquals("Teď objednávky nepřijímáme. Objednat můžete dnes od 17:00.", OrderHours.qrStatus(c, ms("2026-11-18T15:00")).message)
        assertEquals("Teď objednávky nepřijímáme. Objednat můžete zítra od 11:00.", OrderHours.qrStatus(c, ms("2026-11-22T12:00")).message)
        val mondays = c.copy(qrHours = listOf("11:00-20:30") + List(6) { "" })
        assertEquals("Teď objednávky nepřijímáme. Objednat můžete v pondělí od 11:00.", OrderHours.qrStatus(mondays, ms("2026-11-18T12:00")).message)
        assertEquals("You can order on Monday from 11:00.", OrderHours.qrStatus(mondays, ms("2026-11-18T12:00")).messageEn.substringAfter("now. "))
        assertNull(OrderHours.qrStatus(c.copy(qrHours = List(7) { "" }), ms("2026-11-18T12:00")).opensAt)
        // A table with an open bill may still order after hours.
        assertTrue(OrderHours.qrStatus(c, ms("2026-11-16T20:45"), tableHasOpenBill = true).open)
        assertFalse(OrderHours.qrStatus(c.copy(openTablesAfterHours = false), ms("2026-11-16T20:45"), tableHasOpenBill = true).open)
        // Hours off: always open, as before.
        assertTrue(OrderHours.qrStatus(OrderControl(qrHours = week), ms("2026-11-16T23:45")).open)
        // Web hours are separate.
        assertTrue(OrderHours.webStatus(c, ms("2026-11-16T20:45")).open)
    }

    @Test fun stoppedOnTheTill() {
        val c = OrderControl(webPaused = true)
        val s = OrderHours.webStatus(c, ms("2026-11-16T12:00"))
        assertFalse(s.open)
        assertEquals("paused", s.reason)
        assertEquals(OrderHours.DEFAULT_PAUSE, s.message)
        assertEquals("Dnes už nestíháme.", OrderHours.webStatus(c.copy(pauseMessage = " Dnes už nestíháme. "), ms("2026-11-16T12:00")).message)
        // Stopping overrides an open bill too, and only the chosen kind of orders.
        assertTrue(OrderHours.qrStatus(c, ms("2026-11-16T12:00")).open)
        assertFalse(OrderHours.qrStatus(OrderControl(qrPaused = true), ms("2026-11-16T12:00"), tableHasOpenBill = true).open)
    }
}
