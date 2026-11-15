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

    // A test key and a self-signed certificate for it (openssl), standing in for one issued by CEB.
    private val testKey = "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCd0WymS+LcXOJrt7zzY9YAi+WSSTR3Pw1lBKh0Lo71XB+dC1GdKl0zloS+OufhcELOoy0wuXSgcSUuRkW3QeNL+5+noSBOkS5ingDGii2CtdZwobfWA5fCG60a0IkVtl3zv1yQMNG9bDXrQ0eMkqcn0fI4TavArambx8Djh5OJIjra2IlqvkmQOXSWWApnww1vIpjim2LWiQKseCHky9nAIZOAsf/NJh1cquXfanF2gouAhQXW40KIAUdXnxzuKdrR/LQ8r/GEXuOxRk2Tsi2NYEebhkhR5sBsMTeJgC5TRGy5h4zBGZOT9dfdZ4uAGvhWH+sftGWfxX3K8gx3UsTfAgMBAAECggEADEf72IUXRmbEB/XoclhWkvxQBS5FMvJpLG9MXJ4y6c0deK8kzsI5NoaEFfmnideem03/ekc0WDf12MAHNNefxI30Qqs7RGHv5iS9krW2DPHxmho7oB/vc1+L/QY1ZMcoUIuXvHDQIZmGKKS3sKzI7URdQRxqPThGdqT40bv/MzCjcUNlBwSnbcKHePBqeWIp35YiN5aBf55WjOC8M+dhWMj/VtLhXyKyBPpkONKOhsa7pVSXeNDVPzcByyJSuNm802XYroxLbcehkR/XmXQEfQqZZq2BYVswmuNvuc084jcqOGrv8p2L7ITLeXWH58hj2mMxBCad9/qTkaywSIGfoQKBgQDNf+DRnMPKUA1Hlv0pS1FSHMUXYO5ya/1z7Jn8Yf+yJ7oJEslkSv4GopuwG/3lSp7AIfiIFJh0cPq++Vr2doKXwCyiB7qy3+NAXgAC7hntOmy75Ank7rpjzgNNHKgW10E/8Juh+48m85bzeIP35l2I1ab7RwDBL9PbcUB9ePrJmwKBgQDEmd8beSnCZGqOzl6F3V2wywlJZrvxg6bBYvDD5c4ZiDlaptHotY3UFTHirZmOUCsm7H3LMOi4AFL7HABkjR09MJl0K3jcJ3EHeo69B2Va+aXroKbANPbazpD40cpNuT/KOH/gdCXnmoMIn3ev2F6vAtRS3b1f1us7Vd2iAhAYDQKBgQC+URtOJagyOxdBYPWPA+EJcfmaYDwVHiw56zrlQYFEZoRlmM+JIebHEFutqw7mBsilWhDJPoG+/U9MBLWYokLp9aGgYhYnSTA20ZejHw5F5SEElfoP2oQmoInwJYUKukoExHnud4KpaVk4nTljIicn7e7asnRhdkD375fxXFSu1wKBgQDDYtjkiOoDk6CDBH3UNEgkp+Q22q72O4zCrP3y8rR9k3DMsvXyfFzT5ayKR1VQpkP8jxQFmDnwhAH0JcQOKmBgq6gWWA3pXhlrAVZSbsNAQtOu9R5D719V3e0eHAEedXj8FGKiQ0xYfKq1Kybb0AFplTpGkLDgSsvFUHTPyZUNEQKBgGk7mJKsP98NJdX0scXgG5GEdHNsZgGoIMWknNhOOadH1zwwOLQ0h0Glrvlb3QQcgg6615OIz3oQ/UGQ2my0kVt+2/uWVOGSRUFhbbImLmIFF/GM+MfHXwIYlFJKnkKKFKNfwbnRAYApndjKFR7bmP8yttTTAHNZPYAxD01rF4Bi"
    private val testCert = "MIIDIzCCAgugAwIBAgIUfRVTflOt/tDH9OoCSnAghjgLj0cwDQYJKoZIhvcNAQELBQAwITELMAkGA1UEBhMCQ1oxEjAQBgNVBAMMCUZsb3MgVGVzdDAeFw0yNjExMTUxMzI1MzdaFw0zNjExMTIxMzI1MzdaMCExCzAJBgNVBAYTAkNaMRIwEAYDVQQDDAlGbG9zIFRlc3QwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCd0WymS+LcXOJrt7zzY9YAi+WSSTR3Pw1lBKh0Lo71XB+dC1GdKl0zloS+OufhcELOoy0wuXSgcSUuRkW3QeNL+5+noSBOkS5ingDGii2CtdZwobfWA5fCG60a0IkVtl3zv1yQMNG9bDXrQ0eMkqcn0fI4TavArambx8Djh5OJIjra2IlqvkmQOXSWWApnww1vIpjim2LWiQKseCHky9nAIZOAsf/NJh1cquXfanF2gouAhQXW40KIAUdXnxzuKdrR/LQ8r/GEXuOxRk2Tsi2NYEebhkhR5sBsMTeJgC5TRGy5h4zBGZOT9dfdZ4uAGvhWH+sftGWfxX3K8gx3UsTfAgMBAAGjUzBRMB0GA1UdDgQWBBQHKs17Fad2Aknz/pYVQ5bEFYdG/zAfBgNVHSMEGDAWgBQHKs17Fad2Aknz/pYVQ5bEFYdG/zAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQApgTGLVwb1R6JN3YxHs1xPYvH+lJt9nclLwNLfkS7qDYt0II6p3NrHH7rcpz5SKIXgPgGzmyCVvAZSZZoGN1jr9u1dzrs1x3oamw2KR9pk0PeZYnmM4Wfz23g1FHExsLCX/VMaK1ylnXgcL15y6j1E4NftPtkaXoryjTntsJC5oH8VmIKOemRWF8H0/ZYZsnUoH0k0ZckXb/iyHvBcbd30Is68dRrAlB5w8h7VMtpvx1jAH3vns36vB74KWfUOVri187m244TD8sjZRdq/W05bfiGPqCGL2T5YFBWrTrudImISYovHEsTW0lKB8EmBwfKr2TPtr/W2HgaMhYGXGyl3"

    @Test fun readsCertificateInEveryShapeCebMayGive() {
        val key = CsobCertificate.privateKey(testKey)
        val der = java.util.Base64.getDecoder().decode(testCert)
        val pem = CsobCertificate.pem("CERTIFICATE", der).replace("\n", "\r\n")
        val zip = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("cert_csob_pok_1/readme.txt")); z.write("hello".toByteArray()); z.closeEntry()
                z.putNextEntry(java.util.zip.ZipEntry("cert_csob_pok_1/cert.cer")); z.write(der); z.closeEntry()
            }
        }.toByteArray()
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + pem.toByteArray(Charsets.UTF_16LE)
        val bare = java.util.Base64.getMimeEncoder().encode(der)
        val shapes = mapOf("der" to der, "pem" to pem.toByteArray(), "zip" to zip, "utf16" to utf16, "bare base64" to bare,
            "x509 header" to pem.replace("BEGIN CERTIFICATE", "BEGIN X509 CERTIFICATE").replace("END CERTIFICATE", "END X509 CERTIFICATE").toByteArray())
        for ((name, bytes) in shapes) {
            val cert = try { CsobCertificate.matching(key, bytes) } catch (e: Exception) { throw AssertionError(name + ": " + e.message, e) }
            assertTrue(name, cert.subjectX500Principal.name.contains("Flos Test"))
        }
    }
}
