package cz.flos.core

import com.google.zxing.BarcodeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Round5DataTest {
    private val rows = listOf(
        ProductRow("Espresso", 5900, 12, "Nápoje", "8594000000013"),
        ProductRow("Pizza; velká \"Speciál\"", 21900, 12, "Jídlo", "", true, "Velikost: Malá, Velká +40", "Rozvoz: 239"),
        ProductRow("Pivo 0,5 l", 5950, 21, "Nápoje", "", false),
    )

    @Test fun xlsxRoundTrip() {
        val bytes = Xlsx.write("Produkty", ProductSheet.export(rows))
        val table = Spreadsheet.read(bytes)
        assertEquals(ProductSheet.HEADER, table[0])
        val back = ProductSheet.parse(table)
        assertTrue(back.errors.toString(), back.errors.isEmpty())
        assertEquals(rows, back.rows)
        assertEquals("AA", Xlsx.column(26))
    }

    @Test fun csvRoundTrip() {
        val back = ProductSheet.parse(Spreadsheet.read(Csv.write(ProductSheet.export(rows))))
        assertEquals(rows, back.rows)
        val cz = "Název;Cena\r\nKáva;\"45,50 Kč\"\r\n".toByteArray(charset("windows-1250"))
        assertEquals(listOf(ProductRow("Káva", 4550)), ProductSheet.parse(Spreadsheet.read(cz)).rows)
    }

    @Test fun importChecksRows() {
        val table = listOf(listOf("Product", "Price", "VAT", "Code"), listOf("A", "10", "0.12", "123.0"), listOf("", "5"), listOf("B", "abc"), listOf("C", "1", "7"))
        val r = ProductSheet.parse(table)
        assertEquals(listOf(ProductRow("A", 1000, 12, barcode = "123")), r.rows)
        assertEquals(3, r.errors.size)
        assertEquals(listOf("Chybí sloupec Název nebo Cena."), ProductSheet.parse(listOf(listOf("Název", "Kategorie"), listOf("x", "y"))).errors)
    }

    @Test fun importPlan() {
        val existing = listOf(1L to ProductRow("Espresso", 5500, 12, "Nápoje", "8594000000013"), 2L to ProductRow("Pivo 0,5 l", 5950, 21, "Nápoje", "", false), 3L to ProductRow("Čaj", 4500))
        val plan = ProductSheet.plan(existing, rows + ProductRow("caj", 4500))
        assertEquals(listOf(rows[1]), plan.added)
        assertEquals(listOf(1L to rows[0], 3L to ProductRow("caj", 4500)), plan.changed)
        assertEquals(1, plan.unchanged)
    }

    @Test fun bulkPrices() {
        assertEquals(6490L, BulkEdit.adjust(5900, 10.0))
        assertEquals(6500L, BulkEdit.adjust(5900, 10.0, PriceRounding.CROWN))
        assertEquals(6490L, BulkEdit.adjust(5900, 10.0, PriceRounding.NINETY))
        assertEquals(6390L, BulkEdit.adjust(6420, 0.0, PriceRounding.NINETY))
        assertEquals(0L, BulkEdit.adjust(1000, -150.0))
    }

    @Test fun labels() {
        assertEquals(BarcodeFormat.EAN_13, Labels.format("8594000000013"))
        assertEquals(BarcodeFormat.CODE_128, Labels.format("8594000000014"))
        assertEquals(BarcodeFormat.EAN_8, Labels.format("96385074"))
        val blocks = Labels.layout(PriceLabel("Sýr Eidam", 18900, "8594000000013", "cena za 1 kg"), copies = 2)
        assertEquals(2, blocks.count { it is ReceiptBlock.Barcode })
        val bytes = EscPosEncoder(PaperWidth.MM58).encode(blocks)
        assertTrue(bytes.size > 1000)
        assertTrue(ReceiptLayout.toPlainText(blocks, 32).contains("189,00 Kč"))
    }
}
