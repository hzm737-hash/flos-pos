package cz.flos.core.eet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.SocketTimeoutException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.xml.crypto.dsig.XMLSignatureFactory
import javax.xml.crypto.dsig.dom.DOMValidateContext
import javax.xml.crypto.KeySelector
import javax.xml.parsers.DocumentBuilderFactory

class EetTest {
    private val cert = EetCertificate.fromPkcs12(javaClass.getResourceAsStream("/test-eet.p12")!!.readBytes(), "eettest")

    private val sale = EetSale(
        eicPopl = "CZ00000019",
        idJednotky = 202,
        idPokl = "POKL-1",
        poradCis = "2027-000123",
        datTrzby = OffsetDateTime.of(2027, 2, 3, 10, 15, 30, 0, ZoneOffset.ofHours(1)),
        amount = 32277_00,
    )

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))

    @Test fun certificateEicFromSubject() {
        assertEquals("CZ00000019", cert.eic)
        assertTrue(cert.isValidAt())
    }

    @Test fun signatureValidatesWithJdkXmlDsig() {
        val msg = EetMessage.build(sale, cert, firstAttempt = true)
        val doc = parse(msg.xml)
        val body = doc.getElementsByTagNameNS(EetMessage.NS_SOAP, "Body").item(0) as Element
        body.setIdAttributeNS(EetMessage.NS_WSU, "Id", true)
        val sigEl = doc.getElementsByTagNameNS(EetMessage.NS_DS, "Signature").item(0)
        val ctx = DOMValidateContext(KeySelector.singletonKeySelector(cert.certificate.publicKey), sigEl)
        val signature = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(ctx)
        val refOk = signature.signedInfo.references.all { (it as javax.xml.crypto.dsig.Reference).validate(ctx) }
        assertTrue("digest of soap:Body must match", refOk)
        assertTrue("signature must be valid", signature.validate(ctx))
    }

    @Test fun tamperedBodyFailsValidation() {
        val msg = EetMessage.build(sale, cert, firstAttempt = true)
        val doc = parse(msg.xml.replace("celk_trzba=\"32277.00\"", "celk_trzba=\"1.00\""))
        val body = doc.getElementsByTagNameNS(EetMessage.NS_SOAP, "Body").item(0) as Element
        body.setIdAttributeNS(EetMessage.NS_WSU, "Id", true)
        val sigEl = doc.getElementsByTagNameNS(EetMessage.NS_DS, "Signature").item(0)
        val ctx = DOMValidateContext(KeySelector.singletonKeySelector(cert.certificate.publicKey), sigEl)
        assertFalse(XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(ctx).validate(ctx))
    }

    @Test fun messageContentMatchesInterface() {
        val msg = EetMessage.build(sale, cert, firstAttempt = false, verification = true)
        val doc = parse(msg.xml)
        val h = doc.getElementsByTagNameNS(EetMessage.NS_V4, "Hlavicka").item(0) as Element
        val d = doc.getElementsByTagNameNS(EetMessage.NS_V4, "Data").item(0) as Element
        assertEquals("false", h.getAttribute("prvni_zaslani"))
        assertEquals("true", h.getAttribute("overeni"))
        assertEquals(msg.uuid, h.getAttribute("uuid_zpravy"))
        assertTrue(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(Z|[+\-]\d\d:\d\d)""").matches(h.getAttribute("dat_odesl")))
        assertEquals("32277.00", d.getAttribute("celk_trzba"))
        assertEquals("2027-02-03T10:15:30+01:00", d.getAttribute("dat_trzby"))
        assertEquals("202", d.getAttribute("id_jednotky"))
        assertEquals("", d.getAttribute("cerp_zuct"))
        assertTrue(msg.xml.toByteArray().size < EetMessage.MAX_BYTES)
        assertTrue(msg.xml.all { it.code in 32..126 })
    }

    @Test fun amountsFollowSchemaPattern() {
        val p = Regex("""((0|-?[1-9]\d{0,7})\.\d\d|-0\.(0[1-9]|[1-9]\d))""")
        listOf(0L, 20L, -5L, -10000L, 9_999_999_999L).forEach { assertTrue(EetFormat.amount(it), p.matches(EetFormat.amount(it))) }
    }

    @Test fun validationCatchesBadData() {
        assertTrue(sale.copy(eicPopl = "12345678").validate().isNotEmpty())
        assertTrue(sale.copy(idPokl = "Pokladna č. 1").validate().isNotEmpty())
        assertTrue(sale.validate().isEmpty())
    }

    private fun resp(inner: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"><soapenv:Body>
        <eet:Odpoved xmlns:eet="http://fs.gov.cz/eet/schema/v4">$inner</eet:Odpoved>
        </soapenv:Body></soapenv:Envelope>""".toByteArray()

    @Test fun parsesConfirmation() {
        val r = EetClient.parseResponse(200, resp(
            """<eet:Hlavicka uuid_zpravy="b16b955b-5913-4edc-b3cc-51e058c70684" dat_prij="2027-02-03T10:15:31+01:00"/>
               <eet:Potvrzeni pok="a1b2c3d4-0000-4abc-8def-0123456789ab-ff" test="true"/>
               <eet:Varovani kod_varov="5">Datum a čas tržby je výrazně v minulosti</eet:Varovani>"""))
        r as EetResult.Confirmed
        assertEquals("a1b2c3d4-0000-4abc-8def-0123456789ab-ff", r.pok)
        assertTrue(r.test)
        assertEquals(5, r.warnings.single().code)
    }

    @Test fun parsesErrors() {
        val temp = EetClient.parseResponse(200, resp("""<eet:Hlavicka/><eet:Chyba kod="-1">Docasna chyba</eet:Chyba>""")) as EetResult.Error
        assertTrue(temp.retryable)
        val bad = EetClient.parseResponse(200, resp("""<eet:Hlavicka/><eet:Chyba kod="4"/>""")) as EetResult.Error
        assertFalse(bad.retryable)
        assertEquals(EetFormat.errorText(4), bad.message)
        assertTrue(EetClient.parseResponse(200, resp("""<eet:Hlavicka/><eet:Chyba kod="0"/>""")) is EetResult.VerifiedOk)
        val fault = EetClient.parseResponse(500, """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><faultcode>s:Server</faultcode><faultstring>down</faultstring></s:Fault></s:Body></s:Envelope>""".toByteArray())
        assertTrue((fault as EetResult.Error).retryable)
    }

    @Test fun timeoutIsRetryableAndEachAttemptGetsNewUuid() {
        val client = EetClient(EetEnvironment.PLAYGROUND, cert, 2000) { _, _, _ -> throw SocketTimeoutException() }
        val a = client.send(sale, firstAttempt = true)
        val b = client.send(sale, firstAttempt = false)
        assertTrue((a.result as EetResult.Error).retryable)
        assertTrue(a.uuid != b.uuid)
        assertTrue(b.requestXml.contains("prvni_zaslani=\"false\""))
    }

    @Test fun sendsSoapToEndpoint() {
        var seenUrl = ""
        val client = EetClient(EetEnvironment.PRODUCTION, cert) { url, body, _ ->
            seenUrl = url
            assertTrue(String(body).contains("<v4:Trzba>"))
            200 to resp("""<eet:Hlavicka/><eet:Potvrzeni pok="a1b2c3d4-0000-4abc-8def-0123456789ab-01"/>""")
        }
        assertTrue(client.send(sale, true).result is EetResult.Confirmed)
        assertEquals("https://trzbyeet.gov.cz/eet/services/EETServiceSOAP/v4", seenUrl)
    }

    @Test fun unitIdWarningFollowsInterfaceRules() {
        val base = EetSale("CZ00000019", 141, "POKL1", "1", java.time.OffsetDateTime.now(), 10000)
        org.junit.Assert.assertTrue(base.warnings().isEmpty())
        org.junit.Assert.assertEquals(1, base.copy(idJednotky = 5).warnings().size)
        org.junit.Assert.assertEquals(1, base.copy(idJednotky = 147).warnings().size)
    }
}
