package cz.flos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V2SellingTest {
    @Test fun parsesAddonsWithCategories() {
        val list = Addons.parse("[Pizza, Burgery] Přísady*: Sýr +15, Slanina +25\nOmáčka: Bez, Kečup +5\n\nnesmysl\n[] Led*: S ledem")
        assertEquals(3, list.size)
        assertEquals(listOf("Pizza", "Burgery"), list[0].categories)
        assertEquals("Přísady", list[0].group.name)
        assertTrue(list[0].group.multiple)
        assertEquals(OptionChoice("Slanina", 2500), list[0].group.choices[1])
        assertTrue(list[1].forAll)
        assertTrue(list[2].forAll)
        assertEquals(list, Addons.parse(Addons.format(list)))
    }

    @Test fun addonsForCategory() {
        val list = Addons.parse("[Pizza] Přísady*: Sýr +15\nOmáčka: Kečup +5\n[Nápoje] Led*: S ledem")
        assertEquals(listOf("Přísady", "Omáčka"), Addons.forCategory(list, "pizza").map { it.name })
        assertEquals(listOf("Omáčka", "Led"), Addons.forCategory(list, "Nápoje").map { it.name })
        assertEquals(listOf("Omáčka", "Led"), Addons.forCategory(list, "napoje").map { it.name })
        assertEquals(listOf("Omáčka"), Addons.forCategory(list, null).map { it.name })
        assertEquals(emptyList<OptionGroup>(), Addons.forCategory(emptyList(), "Pizza"))
    }

    @Test fun lineNotes() {
        assertEquals("Burger (Sýr) · pálivé, bez cibule", LineNote.join("Burger (Sýr)", " pálivé, bez cibule, "))
        assertEquals("Burger", LineNote.join("Burger", "  "))
        assertEquals("Burger (Sýr)" to "pálivé", LineNote.split("Burger (Sýr) · pálivé"))
        assertEquals("Káva" to "", LineNote.split("Káva"))
        assertEquals(listOf("Pálivé", "Bez cibule"), LineNote.quickNotes("Pálivé, Bez cibule\npálivé2;; Pálivé").take(2))
        assertEquals("pálivé, Bez cibule", LineNote.toggle("pálivé", "Bez cibule"))
        assertEquals("Bez cibule", LineNote.toggle("Pálivé, Bez cibule", "pálivé"))
    }

    @Test fun renameKeepsPriceAndSeparatesLines() {
        var c = Cart().add(1, "Burger", 21900, VatRate.REDUCED, 2.0)
        c = c.rename(0, LineNote.join("Burger", "pálivé"))
        assertEquals("Burger · pálivé", c.items[0].name)
        assertEquals(43800L, c.total)
        // The same product without a note goes on its own line.
        c = c.add(1, "Burger", 21900, VatRate.REDUCED)
        assertEquals(2, c.items.size)
        assertEquals(c, c.rename(5, "x"))
        assertEquals(c, c.rename(0, " "))
    }
}
