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

    @Test fun cleaningRemovesControlCharactersAndTheNoteSeparator() {
        val dirty = order.copy(note = "zvonit\u0007 2×", lines = listOf(line.copy(note = "a · b")))
        val c = WebOrders.cleaned(dirty)
        assertEquals("zvonit 2×", c.note)
        assertEquals("a  b", c.lines[0].note)
    }
}
