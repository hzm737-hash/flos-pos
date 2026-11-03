package cz.flos.core

import cz.flos.core.bank.ExpectedPayment
import cz.flos.core.bank.FioApi
import cz.flos.core.bank.IncomingPayment
import cz.flos.core.bank.MatchResult
import cz.flos.core.bank.PaymentMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ForeignPaymentTest {
    private val iban = "CZ6508000000192000145399"

    @Test fun epcPayloadFollowsTheGuideline() {
        val qr = EpcQr("Kavárna Flos", "CZ65 0800 0000 1920 0014 5399", 1050, "/VS/2026000012 Uctenka 2026-000012", bic = "gibaczpx")
        assertEquals(
            listOf("BCD", "002", "1", "SCT", "GIBACZPX", "Kavárna Flos", iban, "EUR10.50", "", "", "/VS/2026000012 Uctenka 2026-000012").joinToString("\n"),
            qr.toPayload(),
        )
        assertTrue(qr.toMatrix().width > 20)
        assertEquals("FIOBCZPP", EpcQr.bicForIban("CZ5820100000002900000000"))
    }

    @Test fun euroAmountIsRoundedUp() {
        // 255 Kč at 24.33 Kč/EUR = 10.4809… EUR → 10.49 EUR, so the business is never short.
        assertEquals(1049L, EpcQr.eurFor(25500, 24.33))
        assertEquals(1000L, EpcQr.eurFor(24330, 24.33))
    }

    @Test fun parsesCnbRates() {
        val r = CnbRates.parse("03.11.2026 #213\nzemě|měna|množství|kód|kurz\nEMU|euro|1|EUR|24,330\nJaponsko|jen|100|JPY|15,512\n")
        assertEquals(LocalDate.of(2026, 11, 3), r.date)
        assertEquals(24.33, r.of("EUR")!!, 1e-9)
        assertEquals(0.15512, r.of("jpy")!!, 1e-9)
    }

    @Test fun receiptShowsSepaQr() {
        val cart = Cart().add(1, "Pizza", 25500, VatRate.REDUCED)
        val r = Receipt("2026-000012", java.time.LocalDateTime.of(2026, 11, 3, 20, 0), "", cart, PaymentMethod.QR_TRANSFER,
            epc = EpcQr("Flos", iban, 1049, "/VS/2026000012"), eurRate = 24.33)
        val text = ReceiptLayout.toPlainText(ReceiptLayout(32).layout(BusinessInfo("Flos"), r), 32)
        assertTrue(text, text.contains("10,49 EUR"))
        assertTrue(text, text.contains("24,330"))
    }

    @Test fun dayEndShowsTransfers() {
        val r = DailyReport.of(LocalDate.of(2026, 11, 3), emptyList())
            .copy(transfers = TransferSummary(confirmedCount = 2, confirmed = 50000, waitingCount = 1, waiting = 25500, lateCount = 1, late = 12000,
                openCount = 3, open = 40000, overdueCount = 1, overdue = 14500))
        val text = ReceiptLayout.toPlainText(r.layout(BusinessInfo("Flos"), 48), 48)
        assertTrue(text, text.contains("Čeká (1)"))
        assertTrue(text, text.contains("Doplaceno za dřívější dny (1)"))
        assertTrue(text, text.contains("Z toho po splatnosti (1)"))
    }

    @Test fun foreignPaymentMatchedByVsInMessageAndEuroAmount() {
        val waiting = listOf(ExpectedPayment(5, "2026000012", 1049, currency = "EUR", czkPerEur = 24.33))
        // Converted by Fio into CZK, original amount in "Upřesnění", VS only in the message.
        val p = IncomingPayment("fio:1", 25100, message = "/VS/2026000012 Pizza", originalAmount = 1200, originalCurrency = "EUR")
        assertEquals(MatchResult.Paid(5, 1200, 151), PaymentMatcher.match(p, waiting))
        // A foreign bank fee leaves 10.30 EUR: within the 3 % tolerance, paid without a tip.
        val fee = IncomingPayment("fio:2", 25060, message = "VS 2026000012", originalAmount = 1030, originalCurrency = "EUR")
        assertEquals(MatchResult.Paid(5, 1030, 0), PaymentMatcher.match(fee, waiting))
        // Only CZK known: converted back at the receipt's rate.
        val czkOnly = IncomingPayment("fio:3", 20000, variableSymbol = "2026000012")
        assertEquals(MatchResult.Underpaid(5, 822, 227), PaymentMatcher.match(czkOnly, waiting))
    }

    @Test fun fioOriginalAmount() {
        val json = """{"accountStatement":{"info":{"currency":"CZK"},"transactionList":{"transaction":[
          {"column22":{"value":9,"name":"ID","id":22},"column1":{"value":251.0,"name":"Objem","id":1},"column14":{"value":"CZK","name":"Měna","id":14},
           "column18":{"value":"12.00 EUR","name":"Upřesnění","id":18},"column16":{"value":"/VS/2026000012","name":"Zpráva","id":16}}]}}}"""
        val p = FioApi.parse(json).single()
        assertEquals(1200L, p.originalAmount)
        assertEquals("EUR", p.originalCurrency)
        assertEquals("2026000012", PaymentMatcher.variableSymbolOf(p))
    }
}
