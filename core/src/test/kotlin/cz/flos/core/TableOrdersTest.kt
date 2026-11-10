package cz.flos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TableOrdersTest {
    private val secret = ByteArray(32) { it.toByte() }
    private val options = "Velikost: Malá, Velká +40\nPřísady*: Sýr +15, Šunka +25,50, Bez cibule"

    @Test fun keyIsSignedPerTable() {
        val k = TableOrders.key(secret, "5")
        assertEquals(22, k.length)
        assertTrue(TableOrders.keyMatches(secret, "5", k))
        assertTrue(TableOrders.keyMatches(secret, " 5 ", k))
        assertFalse(TableOrders.keyMatches(secret, "6", k))
        assertFalse(TableOrders.keyMatches(ByteArray(32), "5", k))
    }

    @Test fun optionsSurviveTheMenuRoundTrip() {
        // The till publishes its groups with format(); the server and the guest's page read them with parse().
        val groups = ProductOptions.parse(options)
        assertEquals(groups, ProductOptions.parse(ProductOptions.format(groups)))
    }

    @Test fun choicesAreChecked() {
        val groups = ProductOptions.parse(options)
        assertTrue(TableOrders.choicesValid(groups, listOf(listOf(1), listOf(0, 1))))
        assertTrue(TableOrders.choicesValid(groups, listOf(listOf(0))))
        assertFalse(TableOrders.choicesValid(groups, emptyList()))            // the size must be chosen
        assertFalse(TableOrders.choicesValid(groups, listOf(listOf(0, 1))))   // only one size
        assertFalse(TableOrders.choicesValid(groups, listOf(listOf(2))))      // no such size
        assertFalse(TableOrders.choicesValid(groups, listOf(listOf(0), listOf(1, 1))))
        assertFalse(TableOrders.choicesValid(groups, listOf(listOf(0), listOf(), listOf())))
        val (name, price) = ProductOptions.apply("Pizza", 15000, groups, TableOrders.chosen(listOf(listOf(1), listOf(1, 0))))
        assertEquals("Pizza (Velká, Sýr, Šunka)", name)
        assertEquals(15000 + 4000 + 1500 + 2550, price)
    }

    @Test fun ordersAreValidatedAgainstTheMenu() {
        val menu = MenuData("Pizza", categories = listOf(MenuCategory("Pizza", listOf(MenuItem("Margherita", 15000, id = 7, options = options)))), ordering = true)
        val ok = GuestOrder("5", "k", listOf(GuestOrderLine(7, 2, listOf(listOf(0)))))
        assertNull(TableOrders.problem(menu, ok))
        assertNotNull(TableOrders.problem(menu.copy(ordering = false), ok))
        assertNotNull(TableOrders.problem(menu, ok.copy(lines = listOf(GuestOrderLine(8, 1)))))
        assertNotNull(TableOrders.problem(menu, ok.copy(lines = listOf(GuestOrderLine(7, 0, listOf(listOf(0)))))))
        assertNotNull(TableOrders.problem(menu, ok.copy(lines = emptyList())))
        assertEquals("Bez cibule", TableOrders.cleanNote("  Bez\n cibule ".replace("\n ", " ")))
        assertEquals("ab", TableOrders.cleanNote("a\u0007b"))
    }
}
