package cz.flos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Round5NotifyTest {
    @Test fun fillsTemplates() {
        val t = Notify.fill(Notify.RESERVATION, mapOf("jmeno" to "Nováková", "datum" to "6.11.2026", "cas" to "19:00", "hoste" to "4", "provozovna" to "Bistro"))
        assertEquals("Dobrý den Nováková, potvrzujeme rezervaci na 6.11.2026 v 19:00 pro 4 os. Těšíme se na Vás. Bistro, tel.", t)
        assertEquals("Ahoj!", Notify.fill("Ahoj {nikdo}!", emptyMap()))
    }

    @Test fun links() {
        assertEquals("420777123456", Notify.phoneDigits("777 123 456"))
        assertEquals("420777123456", Notify.phoneDigits("+420 777 123 456"))
        assertEquals("421905123456", Notify.phoneDigits("00421 905 123 456"))
        assertNull(Notify.phoneDigits("123"))
        assertEquals("https://wa.me/420777123456?text=Dobr%C3%BD%20den", Notify.whatsapp("777123456", "Dobrý den"))
        assertEquals("sms:+420777123456?body=Ahoj%20%26%20d%C3%ADky", Notify.sms("777 123 456", "Ahoj & díky"))
        assertEquals("mailto:eva@flos.cz?subject=Nab%C3%ADdka&body=Text", Notify.email(" eva@flos.cz ", "Nabídka", "Text"))
        assertNull(Notify.email("eva", "x", "y"))
    }

    @Test fun menuPage() {
        val m = MenuData("Bistro <b>", "Ulice 1", listOf("tel. 777"), "Po-Pá 8-20",
            listOf(MenuCategory("Nápoje", listOf(MenuItem("Espresso", 5900, "Velikost: Malá, Velká"))), MenuCategory("Prázdná", emptyList()), MenuCategory("Jídlo", listOf(MenuItem("Pizza", 18900)))))
        val html = MenuPage.html(m)
        assertTrue(html.contains("Bistro &lt;b&gt;"))
        assertFalse(html.contains("<b>"))
        assertTrue(html.contains("59,00 Kč"))
        assertTrue(html.contains("href=\"#c1\""))
        assertFalse(html.contains("Prázdná"))
        assertFalse(html.contains("<script"))
        assertEquals(1, MenuPage.poster("Bistro", "https://x.cz/menu/a").count { it is ReceiptBlock.Qr })
    }
}
