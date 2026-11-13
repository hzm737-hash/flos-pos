package cz.flos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintTextTest {
    private fun printed(text: String, cs: PrinterCharset): String {
        val bytes = EscPosEncoder(PaperWidth.MM80, cs).encode(listOf(ReceiptBlock.Line(text)), cut = false)
        val name = cs.charsetName ?: "US-ASCII"
        return String(bytes, charset(name))
    }

    @Test
    fun halfAndHalfPrintsReadably() {
        val name = "½ Prosciutto di pollo + ½ Diavola + Mozzarella" + LineNote.SEP + "Palivost 5/5, Plus cibulla"
        for (cs in PrinterCharset.values()) {
            val out = printed(name, cs)
            assertFalse("$cs: $out", '?' in out)
            assertTrue("$cs: $out", "1/2 Prosciutto di pollo + 1/2 Diavola + Mozzarella - Palivost 5/5" in out)
        }
    }

    @Test
    fun czechLettersStayOnCodePages() {
        assertTrue("Příplatek" in printed("Příplatek", PrinterCharset.CP852))
        assertTrue("Příplatek" in printed("Příplatek", PrinterCharset.CP1250))
        assertTrue("Priplatek" in printed("Příplatek", PrinterCharset.ASCII))
    }

    @Test
    fun symbolsAndEmoji() {
        assertEquals("\"Pizza\" - 3/4 x 2 ... ", PrintText.printable("„Pizza“ – ¾ × 2 … 🍕"))
        assertEquals("1 x 240,00  12 %", PrintText.printable("1 x 240,00  12 %"))
        assertEquals("Strasse 180st", String(PrintText.ascii("Straße 180°"), Charsets.US_ASCII))
    }
}
