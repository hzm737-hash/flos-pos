package cz.flos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class WebReservationsTest {
    // Thursday 19 November 2026, 12:00 in Prague.
    private val now = ZonedDateTime.of(2026, 11, 19, 12, 0, 0, 0, OrderHours.ZONE)
    private val rules = ReservationRules(maxGuests = 8, daysAhead = 30, from = "11:00", to = "21:00", noticeMinutes = 60, closedDays = listOf(1))
    private val r = WebReservation("Jan Novák", "777 123 456", "jan@example.cz", 4, "2026-11-20", "19:30", "u okna", agreedTerms = true)

    @Test fun aGoodReservationPasses() {
        assertNull(WebReservations.problem(r, rules, now))
        assertEquals(19, WebReservations.start(r)!!.hour)
        assertNotNull(WebReservations.startMillis(r))
        assertEquals("WEB R7 · jan@example.cz · u okna", WebReservations.tillNote(7, r))
        assertEquals("pá 20.11. 19:30", WebReservations.whenText(r))
    }

    @Test fun rulesAreChecked() {
        assertNotNull(WebReservations.problem(r.copy(agreedTerms = false), rules, now))
        assertNotNull(WebReservations.problem(r.copy(phone = "12"), rules, now))
        assertNotNull(WebReservations.problem(r.copy(email = "nope"), rules, now))
        assertNull(WebReservations.problem(r.copy(email = ""), rules, now))
        assertNotNull(WebReservations.problem(r.copy(guests = 0), rules, now))
        assertTrue(WebReservations.problem(r.copy(guests = 9), rules, now)!!.contains("8"))
        // Too soon, in the past, too far ahead, outside the hours, on a closed day.
        assertNotNull(WebReservations.problem(r.copy(date = "2026-11-19", time = "12:30"), rules, now))
        assertNull(WebReservations.problem(r.copy(date = "2026-11-19", time = "13:00"), rules, now))
        assertNotNull(WebReservations.problem(r.copy(date = "2026-11-18"), rules, now))
        assertNotNull(WebReservations.problem(r.copy(date = "2026-12-20"), rules, now))
        assertNotNull(WebReservations.problem(r.copy(time = "21:30"), rules, now))
        assertNotNull(WebReservations.problem(r.copy(time = "10:59"), rules, now))
        assertNotNull(WebReservations.problem(r.copy(date = "2026-11-23"), rules, now)) // Monday
        assertNotNull(WebReservations.problem(r.copy(date = "20.11.2026"), rules, now))
        // In English for an English customer.
        assertEquals("Please agree to the privacy policy.", WebReservations.problem(r.copy(agreedTerms = false, lang = "en"), rules, now))
    }

    @Test fun cleaningAndRules() {
        val c = WebReservations.cleaned(r.copy(name = "  Jan\u0007 ", note = "a\nb", lang = "de"))
        assertEquals("Jan", c.name)
        assertEquals("cs", c.lang)
        val bad = WebReservations.cleanRules(ReservationRules(maxGuests = 999, daysAhead = -3, from = "x", closedDays = listOf(0, 7, 7, 3)))
        assertEquals(WebReservations.GUESTS_LIMIT, bad.maxGuests)
        assertEquals(0, bad.daysAhead)
        assertEquals("11:00", bad.from)
        assertEquals(listOf(3, 7), bad.closedDays)
    }

    @Test fun messages() {
        val ok = WebReservations.confirmation(12, r, "5", "Pizza Fresca", "+420 777 000 000")
        assertTrue(ok.contains("R12") && ok.contains("Stůl 5") && ok.contains("4 os."))
        val no = WebReservations.rejection(12, r.copy(lang = "en"), "We are full.", "Pizza Fresca", "")
        assertTrue(no.startsWith("Hello Jan Novák") && no.contains("We are full."))
    }
}
