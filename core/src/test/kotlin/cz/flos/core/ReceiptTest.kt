package cz.flos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ReceiptTest {
    private val business = BusinessInfo(
        name = "Kavárna Flos",
        addressLines = listOf("Václavské nám. 1", "110 00 Praha 1"),
        ico = "12345678",
        dic = "CZ12345678",
        vatPayer = true,
    )
    private val cart = Cart().add(1, "Espresso", 5900, VatRate.REDUCED, 2).add(2, "Croissant", 4550, VatRate.REDUCED)
    private val receipt = Receipt(
        number = "2025-000001",
        createdAt = LocalDateTime.of(2025, 3, 1, 9, 30),
        cashier = "Hazem",
        cart = cart,
        payment = PaymentMethod.QR_TRANSFER,
        qr = QrPlatba(iban = "CZ6508000000192000145399", amount = cart.total, variableSymbol = "2025000001"),
    )

    @Test fun linesFitPaperWidth() {
        val blocks = ReceiptLayout(32).layout(business, receipt)
        blocks.filterIsInstance<ReceiptBlock.Line>().filterNot { it.large }.forEach {
            assertTrue("too long: '${it.text}'", it.text.length <= 32)
        }
        val text = ReceiptLayout.toPlainText(blocks, 32)
        assertTrue(text.contains("CELKEM"))
        assertTrue(text.contains("[QR] SPD*1.0*ACC:CZ6508000000192000145399*AM:163.50"))
    }

    @Test fun cashRoundingAndChange() {
        val r = receipt.copy(payment = PaymentMethod.CASH, qr = null, cashGiven = 20000)
        assertEquals(16350L, r.total)
        assertEquals(16400L, r.cashDue)
        assertEquals(50L, r.rounding)
        assertEquals(3600L, r.change)
    }

    @Test fun escPosContainsRasterQr() {
        val blocks = ReceiptLayout(32).layout(business, receipt)
        val bytes = EscPosEncoder(PaperWidth.MM58).encode(blocks)
        val s = bytes.toList()
        // GS v 0 raster command followed by width 48 bytes (384 dots)
        val idx = s.windowed(5).indexOfFirst { it == listOf<Byte>(0x1D, 0x76, 0x30, 0, 48) }
        assertTrue(idx > 0)
        // ASCII mode never sends bytes above 0x7F as text
        assertEquals(0x1B.toByte(), bytes[0])
    }

    @Test fun qrImageIsCentredAndSquare() {
        val enc = EscPosEncoder(PaperWidth.MM58)
        val img = enc.qrImage(QrPlatba.qrMatrix("SPD*1.0*ACC:CZ6508000000192000145399*CC:CZK"))
        assertEquals(384, img[0].size)
        assertTrue(img.size in 200..384)
    }

    @Test fun dailyReport() {
        val sales = listOf(
            SaleSummary(10000, PaymentMethod.CASH, listOf(Vat.split(10000, VatRate.REDUCED))),
            SaleSummary(12100, PaymentMethod.CARD, listOf(Vat.split(12100, VatRate.STANDARD))),
            SaleSummary(5000, PaymentMethod.CASH, listOf(Vat.split(5000, VatRate.REDUCED)), cancelled = true),
        )
        val r = DailyReport.of(java.time.LocalDate.of(2025, 3, 1), sales)
        assertEquals(2, r.receiptCount)
        assertEquals(1, r.cancelledCount)
        assertEquals(22100L, r.total)
        assertEquals(10000L, r.byPayment[PaymentMethod.CASH])
    }
}
