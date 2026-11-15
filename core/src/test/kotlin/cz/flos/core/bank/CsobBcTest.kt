package cz.flos.core.bank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CsobBcTest {

    private val avizo = """
        CEKOCZPPAXXX 00000
        942 01

        :20:PIZZA TEST S.R.O.
        :25:0300/123456789
        :34F:CZKD0,
        :61:2611150000C385,00FMSC //98364654
        :86:111?00NOVAK JAN?20ZAUCT.PLATBA?21000019-0000000019/0300
        ?22VS:0000012345?23SS:?24KS:
        ?25Pizza stul 4?26.
        ?27.?28.
        ?29VS:0000012345?30SS:
        :61:2611150000D120,00NMSC //111222
        :86:111?00DODAVATEL?20ZAUCT.PLATBA?21000000-0000222333/0800
        ?22VS:77?23SS:?24KS:
        ?25.?26.
        :61:2611150000C410,50NMSC //565645645           0000
        /OCMT/EUR16,50 25,121212
        :86:030?00Kurs:24,880000?20JOHN SMITH?21SEPA PREVOD?22pizza
        ?23.?24.?25.?26.?27POPL.ZAHR:EUR0?30DEUTDEFF?31DE89370400440532013000
        :61:2611150000RC50,00FMSC //999
        :86:111?00STORNO?20ZAUCT.PLATBA?21000000-0000000001/0100
        ?22VS:1
    """.trimIndent().replace("\n", "\r\n")

    @Test fun readsCreditsFromMt942() {
        val bytes = avizo.toByteArray(charset("windows-1250"))
        assertTrue(Mt942Parser.looksLike(bytes))
        val list = Mt942Parser.parse(bytes)
        assertEquals(2, list.size)
        val dom = list[0]
        assertEquals("csob:98364654", dom.sourceId)
        assertEquals(38_500L, dom.amount)
        assertEquals("12345", dom.variableSymbol)
        assertEquals("NOVAK JAN", dom.counterName)
        assertEquals("19-19/0300", dom.counterAccount)
        assertEquals("Pizza stul 4", dom.message)
        assertEquals("CZK", dom.currency)
        val sepa = list[1]
        assertEquals(41_050L, sepa.amount)
        assertNull(sepa.variableSymbol)
        assertEquals("JOHN SMITH", sepa.counterName)
        assertEquals("DE89370400440532013000", sepa.counterAccount)
        assertEquals(1_650L, sepa.originalAmount)
        assertEquals("EUR", sepa.originalCurrency)
    }

    @Test fun readsUtf8Advice() {
        val list = CsobBc.paymentsIn(CsobBc.FileDetail(null, "AV_1.STA", "AVIZO", "", 0, "D"), avizo.toByteArray(Charsets.UTF_8))
        assertEquals(listOf(38_500L, 41_050L), list.map { it.amount })
    }

    @Test fun buildsListRequest() {
        val x = CsobBc.listRequest("12 345 678", "2026-11-15T10:00:00+01:00", listOf("AVIZO"), "123e4567-e89b-42d3-a456-426614174000")
        assertTrue(x.contains("<v2:ContractNumber>12345678</v2:ContractNumber>"))
        assertTrue(x.contains("<v2:PrevQueryTimestamp>2026-11-15T10:00:00+01:00</v2:PrevQueryTimestamp>"))
        assertTrue(x.contains("<v2:Filter><v2:FileTypes><v2:FileType>AVIZO</v2:FileType></v2:FileTypes><v2:ClientAppGuid>"))
    }

    @Test fun parsesFileList() {
        val r = CsobBc.parseList("""
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"><soapenv:Body>
            <ns2:GetDownloadFileListResponse_v2 xmlns:ns2="http://ceb-bc.csob.cz/CEBBCWS/GetDownloadFileList_v2">
              <ns2:QueryTimestamp>2026-11-15T12:00:01.123+01:00</ns2:QueryTimestamp>
              <ns2:FileList>
                <ns2:FileDetail><ns2:Url>https://ceb-bc.csob.cz/ExtFileHubDown/v2/download?id=abc&amp;x=1</ns2:Url>
                  <ns2:Filename>AV_123456789_20261115_0001.STA</ns2:Filename><ns2:Type>AVIZO</ns2:Type>
                  <ns2:CreationDateTime>2026-11-15T11:59:00+01:00</ns2:CreationDateTime><ns2:Size>512</ns2:Size><ns2:Status>D</ns2:Status></ns2:FileDetail>
                <ns2:FileDetail><ns2:Filename>AV_123456789_20261115_0002.STA</ns2:Filename><ns2:Type>AVIZO</ns2:Type>
                  <ns2:CreationDateTime>2026-11-15T12:00:00+01:00</ns2:CreationDateTime><ns2:Size>0</ns2:Size><ns2:Status>R</ns2:Status></ns2:FileDetail>
              </ns2:FileList>
              <ns2:TicketId>T1</ns2:TicketId>
            </ns2:GetDownloadFileListResponse_v2></soapenv:Body></soapenv:Envelope>
        """.trimIndent().toByteArray())
        assertEquals("2026-11-15T12:00:01.123+01:00", r.queryTimestamp)
        assertEquals(2, r.files.size)
        assertEquals("https://ceb-bc.csob.cz/ExtFileHubDown/v2/download?id=abc&x=1", r.files[0].url)
        assertEquals("R", r.files[1].status)
        assertNull(r.files[1].url)
    }

    @Test fun readsFault() {
        try {
            CsobBc.parseList("""
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>err</faultstring>
                <detail><e:CEBBCError xmlns:e="http://ceb-bc.csob.cz/CEBBCWS/CEBBCError_v2"><e:Code>1101</e:Code><e:Text>blocked</e:Text></e:CEBBCError></detail>
                </s:Fault></s:Body></s:Envelope>
            """.trimIndent().toByteArray())
            fail()
        } catch (e: CsobBc.BcException) {
            assertEquals("1101", e.code)
        }
    }

    @Test fun makesCertificateRequest() {
        val r = CsobCertificate.newRequest("Flos Pokladna 1")
        assertTrue(r.csrPem.startsWith("-----BEGIN CERTIFICATE REQUEST-----"))
        CsobCertificate.privateKey(r.privateKeyPkcs8)
        try {
            CsobCertificate.matching(CsobCertificate.privateKey(r.privateKeyPkcs8), "nonsense".toByteArray())
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("certifikát"))
        }
    }
}
