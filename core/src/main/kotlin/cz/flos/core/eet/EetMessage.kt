package cz.flos.core.eet

import java.security.MessageDigest
import java.security.Signature
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/**
 * Builds the signed SOAP 1.1 request for the EET 2.0 operation OdeslaniTrzby.
 *
 * Only soap:Body is signed (WS-Security 1.1, X509v3 BinarySecurityToken, Exclusive XML
 * Canonicalization, SHA-256 digest, RSA-SHA256 signature), as the interface description requires.
 *
 * Android has no javax.xml.crypto, so the canonical form is produced directly: the Body and
 * SignedInfo are written already in exclusive-C14N form (sorted namespaces and attributes, no
 * whitespace, explicit end tags) and embedded unchanged in the envelope. The unit tests check
 * the result with the JDK's own XML-signature validator.
 */
object EetMessage {
    const val NS_SOAP = "http://schemas.xmlsoap.org/soap/envelope/"
    const val NS_V4 = "http://fs.gov.cz/eet/schema/v4"
    const val NS_WSSE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
    const val NS_WSU = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"
    const val NS_DS = "http://www.w3.org/2000/09/xmldsig#"
    const val NS_EXC_C14N = "http://www.w3.org/2001/10/xml-exc-c14n#"
    const val ALG_RSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256"
    const val ALG_SHA256 = "http://www.w3.org/2001/04/xmlenc#sha256"
    const val VT_X509 = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-x509-token-profile-1.0#X509v3"
    const val ET_BASE64 = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary"
    const val SOAP_ACTION = "http://fs.gov.cz/eet/OdeslaniTrzby"

    /** Maximum message size including the SOAP envelope. */
    const val MAX_BYTES = 12 * 1024

    data class Built(val xml: String, val uuid: String, val sentAt: OffsetDateTime)

    /**
     * @param firstAttempt true for the first sending, false for every repeated sending (prvni_zaslani).
     * @param verification true sends in verification mode (overeni): checked but not recorded.
     */
    fun build(
        sale: EetSale,
        cert: EetCertificate,
        firstAttempt: Boolean,
        verification: Boolean = false,
        sentAt: OffsetDateTime = OffsetDateTime.now(EetFormat.PRAGUE),
        uuid: String = UUID.randomUUID().toString(),
    ): Built {
        val id = UUID.randomUUID().toString().replace("-", "").uppercase()
        val bodyId = "id-$id"
        val tokenId = "X509-$id"

        val body = canonicalBody(sale, bodyId, uuid, sentAt, firstAttempt, verification)
        val digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)))
        val signedInfo = canonicalSignedInfo(bodyId, digest)
        val signatureValue = Signature.getInstance("SHA256withRSA").run {
            initSign(cert.privateKey)
            update(signedInfo.toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(sign())
        }
        val certB64 = Base64.getEncoder().encodeToString(cert.certificate.encoded)

        val xml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<soapenv:Envelope xmlns:soapenv="$NS_SOAP" xmlns:v4="$NS_V4">""")
            append("<soapenv:Header>")
            append("""<wsse:Security xmlns:wsse="$NS_WSSE" xmlns:wsu="$NS_WSU">""")
            append("""<wsse:BinarySecurityToken EncodingType="$ET_BASE64" ValueType="$VT_X509" wsu:Id="$tokenId">$certB64</wsse:BinarySecurityToken>""")
            append("""<ds:Signature xmlns:ds="$NS_DS" Id="SIG-$id">""")
            append(signedInfo)
            append("<ds:SignatureValue>$signatureValue</ds:SignatureValue>")
            append("""<ds:KeyInfo Id="KI-$id"><wsse:SecurityTokenReference wsu:Id="STR-$id">""")
            append("""<wsse:Reference URI="#$tokenId" ValueType="$VT_X509"/>""")
            append("</wsse:SecurityTokenReference></ds:KeyInfo>")
            append("</ds:Signature></wsse:Security></soapenv:Header>")
            append(body)
            append("</soapenv:Envelope>")
        }
        return Built(xml, uuid, sentAt)
    }

    /** soap:Body in exclusive-C14N form with InclusiveNamespaces PrefixList="v4". */
    internal fun canonicalBody(
        sale: EetSale, bodyId: String, uuid: String, sentAt: OffsetDateTime, firstAttempt: Boolean, verification: Boolean,
    ): String {
        val header = sortedMapOf(
            "dat_odesl" to EetFormat.dateTime(sentAt),
            "overeni" to verification.toString(),
            "prvni_zaslani" to firstAttempt.toString(),
            "uuid_zpravy" to uuid,
        )
        val data = sortedMapOf<String, String>().apply {
            put("celk_trzba", EetFormat.amount(sale.amount))
            sale.cerpZuct?.let { put("cerp_zuct", EetFormat.amount(it)) }
            put("dat_trzby", EetFormat.dateTime(sale.datTrzby))
            put("eic_popl", sale.eicPopl)
            sale.eicPoverujiciho?.let { put("eic_poverujiciho", it) }
            put("id_jednotky", sale.idJednotky.toString())
            put("id_pokl", sale.idPokl)
            put("porad_cis", sale.poradCis)
            sale.povereniVicePopl?.let { put("povereni_vice_popl", it.toString()) }
            sale.urcenoCerpZuct?.let { put("urceno_cerp_zuct", EetFormat.amount(it)) }
        }
        return """<soapenv:Body xmlns:soapenv="$NS_SOAP" xmlns:v4="$NS_V4" xmlns:wsu="$NS_WSU" wsu:Id="$bodyId">""" +
            "<v4:Trzba>" +
            "<v4:Hlavicka${attrs(header)}></v4:Hlavicka>" +
            "<v4:Data${attrs(data)}></v4:Data>" +
            "</v4:Trzba></soapenv:Body>"
    }

    /** ds:SignedInfo in exclusive-C14N form with InclusiveNamespaces PrefixList="soapenv v4". */
    internal fun canonicalSignedInfo(bodyId: String, digest: String): String =
        """<ds:SignedInfo xmlns:ds="$NS_DS" xmlns:soapenv="$NS_SOAP" xmlns:v4="$NS_V4">""" +
            """<ds:CanonicalizationMethod Algorithm="$NS_EXC_C14N"><ec:InclusiveNamespaces xmlns:ec="$NS_EXC_C14N" PrefixList="soapenv v4"></ec:InclusiveNamespaces></ds:CanonicalizationMethod>""" +
            """<ds:SignatureMethod Algorithm="$ALG_RSA_SHA256"></ds:SignatureMethod>""" +
            """<ds:Reference URI="#$bodyId">""" +
            """<ds:Transforms><ds:Transform Algorithm="$NS_EXC_C14N"><ec:InclusiveNamespaces xmlns:ec="$NS_EXC_C14N" PrefixList="v4"></ec:InclusiveNamespaces></ds:Transform></ds:Transforms>""" +
            """<ds:DigestMethod Algorithm="$ALG_SHA256"></ds:DigestMethod>""" +
            "<ds:DigestValue>$digest</ds:DigestValue>" +
            "</ds:Reference></ds:SignedInfo>"

    private fun attrs(m: Map<String, String>): String = m.entries.joinToString("") { (k, v) -> """ $k="${escapeAttr(v)}"""" }

    /** Attribute escaping as defined by Canonical XML. */
    private fun escapeAttr(v: String): String = buildString {
        for (c in v) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '"' -> append("&quot;")
            '\t' -> append("&#x9;")
            '\n' -> append("&#xA;")
            '\r' -> append("&#xD;")
            else -> append(c)
        }
    }
}
