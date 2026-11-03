package cz.flos.core.bank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.Socket

class BankTest {

    @Test fun parsesFioStyleNotification() {
        val p = BankEmailParser.parse(
            "m1", "Fio banka - příjem na kontě",
            """
            Příjem na kontě: 2001234567
            Částka: 1 260,00
            VS: 2026000012
            Zpráva příjemci: Kavarna Flos uctenka 2026-000012
            Aktuální zůstatek: 10 500,00
            Protiúčet: 123456789/0300
            """.trimIndent(),
        )!!
        assertEquals(126000L, p.amount)
        assertEquals("2026000012", p.variableSymbol)
        assertEquals("123456789/0300", p.counterAccount)
        assertEquals("Kavarna Flos uctenka 2026-000012", p.message)
    }

    @Test fun parsesOtherWordings() {
        val a = BankEmailParser.parse("m2", "Příchozí platba", "Na Váš účet byla připsána platba. Částka transakce: +250,00 CZK, variabilní symbol 2026000044.")!!
        assertEquals(25000L, a.amount)
        assertEquals("2026000044", a.variableSymbol)
        val b = BankEmailParser.parse("m3", "Incoming payment", "Amount: 1,250.50 CZK\nVS: 7")!!
        assertEquals(125050L, b.amount)
        val c = BankEmailParser.parse("m4", "Kredit na účtu", "Připsaná částka 3.400,00 Kč, V.S.: 99")!!
        assertEquals(340000L, c.amount)
        assertEquals("99", c.variableSymbol)
    }

    @Test fun ignoresOutgoingPayments() {
        assertNull(BankEmailParser.parse("m5", "Odchozí platba", "Částka: -500,00 CZK\nVS: 1"))
        assertNull(BankEmailParser.parse("m6", "Platba kartou", "Částka: 129,00 CZK, obchodník ALBERT"))
        assertNull(BankEmailParser.parse("m7", "Newsletter", "Nové úrokové sazby"))
    }

    @Test fun matchesBySymbolAndRecordsTip() {
        val waiting = listOf(ExpectedPayment(1, "2026000012", 23300), ExpectedPayment(2, "2026000013", 25000))
        val r = PaymentMatcher.match(IncomingPayment("x", 25000, "0002026000012"), waiting)
        assertEquals(MatchResult.Paid(1, 25000, 1700), r)
    }

    @Test fun underpaidThenToppedUp() {
        val first = PaymentMatcher.match(IncomingPayment("a", 20000, "5"), listOf(ExpectedPayment(7, "5", 25000)))
        assertEquals(MatchResult.Underpaid(7, 20000, 5000), first)
        val second = PaymentMatcher.match(IncomingPayment("b", 6000, "5"), listOf(ExpectedPayment(7, "5", 25000, alreadyPaid = 20000)))
        assertEquals(MatchResult.Paid(7, 26000, 1000), second)
    }

    @Test fun exactAmountWithoutSymbolOnlyWhenUnambiguous() {
        val one = listOf(ExpectedPayment(1, "11", 15000), ExpectedPayment(2, "12", 9900))
        assertEquals(MatchResult.Paid(1, 15000, 0), PaymentMatcher.match(IncomingPayment("a", 15000), one))
        val two = one + ExpectedPayment(3, "13", 15000)
        assertEquals(MatchResult.NoMatch, PaymentMatcher.match(IncomingPayment("b", 15000), two))
        assertEquals(MatchResult.NoMatch, PaymentMatcher.match(IncomingPayment("c", 15000, currency = "EUR"), one))
    }

    @Test fun readsMultipartQuotedPrintableAndHtml() {
        val raw = """
            Subject: =?UTF-8?B?UMWZw61jaG96w60gcGxhdGJh?=
            Message-ID: <abc@bank.cz>
            Content-Type: multipart/alternative; boundary="XYZ"

            --XYZ
            Content-Type: text/html; charset=windows-1250
            Content-Transfer-Encoding: quoted-printable

            <p>=C8=E1stka: <b>1 000,00 CZK</b></p><p>VS: 42</p>
            --XYZ--
        """.trimIndent().replace("\n", "\r\n").toByteArray(Charsets.ISO_8859_1)
        val m = Mime.parse(raw)
        assertEquals("Příchozí platba", m.subject)
        assertTrue(m.text, m.text.contains("Částka: 1 000,00 CZK"))
        val p = BankEmailParser.parse("u1", m.subject, m.text)!!
        assertEquals(100000L, p.amount)
        assertEquals("42", p.variableSymbol)
    }

    @Test fun parsesFioJson() {
        val json = """
        {"accountStatement":{"info":{"currency":"CZK"},"transactionList":{"transaction":[
          {"column22":{"value":26001,"name":"ID pohybu","id":22},"column0":{"value":"2026-11-03+0100","name":"Datum","id":0},
           "column1":{"value":260.0,"name":"Objem","id":1},"column5":{"value":"2026000012","name":"VS","id":5},
           "column2":{"value":"123456789","name":"Protiúčet","id":2},"column3":{"value":"0300","name":"Kód banky","id":3},
           "column10":{"value":"Jan Novák","name":"Název protiúčtu","id":10},"column14":{"value":"CZK","name":"Měna","id":14},"column16":null},
          {"column22":{"value":26002,"name":"ID pohybu","id":22},"column1":{"value":-99.5,"name":"Objem","id":1},"column5":null}
        ]}}}
        """.trimIndent()
        val list = FioApi.parse(json)
        assertEquals(1, list.size)
        val p = list.single()
        assertEquals("fio:26001", p.sourceId)
        assertEquals(26000L, p.amount)
        assertEquals("123456789/0300", p.counterAccount)
        assertEquals("Jan Novák", p.counterName)
        assertEquals(emptyList<IncomingPayment>(), FioApi.parse("""{"accountStatement":{"info":{},"transactionList":{"transaction":[]}}}"""))
    }

    @Test fun imapClientFetchesNewMessages() {
        val mail = "Subject: Prichozi platba\r\n\r\nCastka: 250,00 CZK\r\nVS: 5\r\n".toByteArray()
        val script = buildString {
            append("* OK IMAP ready\r\n")
            append("F001 OK LOGIN completed\r\n")
            append("* 3 EXISTS\r\n* OK [UIDVALIDITY 777] UIDs valid\r\nF002 OK [READ-WRITE] SELECT completed\r\n")
            append("* SEARCH 11 12\r\nF003 OK SEARCH completed\r\n")
            append("* 2 FETCH (UID 11 BODY[] {${mail.size}}\r\n")
        }.toByteArray() + mail + ")\r\nF004 OK FETCH completed\r\n* 3 FETCH (UID 12 BODY[] {${mail.size}}\r\n".toByteArray() + mail +
            ")\r\nF005 OK FETCH completed\r\n* BYE\r\nF006 OK LOGOUT completed\r\n".toByteArray()
        val sent = ByteArrayOutputStream()
        val socket = object : Socket() {
            override fun isConnected() = true
            override fun getInputStream(): InputStream = ByteArrayInputStream(script)
            override fun getOutputStream(): OutputStream = sent
        }
        val client = ImapClient(ImapAccount("imap.example.cz", user = "pokladna@example.cz", password = "tajne\"heslo"), socketFactory = { socket })
        val mails = client.fetchNew(afterUid = 10)
        assertEquals(listOf(11L, 12L), mails.map { it.uid })
        assertEquals(777L, client.uidValidity)
        val commands = sent.toString(Charsets.UTF_8)
        assertTrue(commands, commands.contains("LOGIN \"pokladna@example.cz\" \"tajne\\\"heslo\""))
        assertTrue(commands, commands.contains("UID SEARCH UID 11:*"))
        assertTrue(commands, commands.contains("BODY.PEEK[]"))
        val p = BankEmailParser.parse("imap:777:11", Mime.parse(mails[0].raw).subject, Mime.parse(mails[0].raw).text)!!
        assertEquals(25000L, p.amount)
    }
}
