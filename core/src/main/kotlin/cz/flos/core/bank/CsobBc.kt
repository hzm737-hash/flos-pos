package cz.flos.core.bank

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URI
import java.security.KeyStore
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.xml.parsers.DocumentBuilderFactory

/**
 * ČSOB CEB Business Connector: the bank's own interface for downloading account statements and
 * payment advices (avíza) of a CEB contract. SOAP 1.1 over HTTPS with a client certificate that the
 * owner registers in CEB → Nastavení → Business Connector (see [CsobCertificate]).
 *
 * The bank creates an avízo (MT942) right after each movement on the account, so a QR payment shows
 * up within a minute or two. GetDownloadFileList returns the files that appeared since the previous
 * call's QueryTimestamp; the files themselves are fetched with HTTP GET from the URL in the answer.
 *
 * The bank allows 30 calls of the web service per 20 minutes for one contract and certificate,
 * so Flos asks at most once a minute.
 */
object CsobBc {
    const val PROD = "https://ceb-bc.csob.cz/cebbc/api"
    const val TEST = "https://testceb-bc.csob.cz/cebbc/api"
    const val MIN_INTERVAL_MS = 60_000L
    /** After error 1101 (too many calls) the bank's counter needs a quiet while. */
    const val BLOCKED_PAUSE_MS = 20 * 60_000L

    private const val NS = "http://ceb-bc.csob.cz/CEBBCWS/GetDownloadFileList_v2"
    private val TS = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    data class FileDetail(
        val url: String?,
        val filename: String,
        val type: String,
        val created: String,
        val size: Long,
        /** D = ready to download, R = still being prepared (ask again later), F = failed for good. */
        val status: String,
    )

    data class FileList(val queryTimestamp: String, val files: List<FileDetail>, val ticketId: String?)

    /** A SOAP fault from the bank, with its code (1002, 1011, 1012, 1101 …). */
    class BcException(val code: String, message: String) : IllegalStateException(message)

    /** The request body for GetDownloadFileList_v2. Types: AVIZO, VYPIS, KURZY, IMPPROT. */
    fun listRequest(contract: String, prevQueryTimestamp: String?, types: List<String>, appGuid: String?): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:v2="$NS"><soapenv:Body>""")
        append("<v2:GetDownloadFileListRequest_v2>")
        append("<v2:ContractNumber>").append(contract.filter(Char::isDigit)).append("</v2:ContractNumber>")
        if (prevQueryTimestamp != null) append("<v2:PrevQueryTimestamp>").append(xml(prevQueryTimestamp)).append("</v2:PrevQueryTimestamp>")
        if (types.isNotEmpty() || appGuid != null) {
            append("<v2:Filter>")
            if (types.isNotEmpty()) {
                append("<v2:FileTypes>")
                types.forEach { append("<v2:FileType>").append(xml(it)).append("</v2:FileType>") }
                append("</v2:FileTypes>")
            }
            if (appGuid != null) append("<v2:ClientAppGuid>").append(xml(appGuid)).append("</v2:ClientAppGuid>")
            append("</v2:Filter>")
        }
        append("</v2:GetDownloadFileListRequest_v2></soapenv:Body></soapenv:Envelope>")
    }

    /** Reads a GetDownloadFileList answer; throws [BcException] for a SOAP fault. */
    fun parseList(xml: ByteArray): FileList {
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Never fetch anything the answer refers to; Android's parser does not know every feature.
            runCatching { setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val root = doc.documentElement
        root.all("Fault").firstOrNull()?.let { fault ->
            val code = fault.all("Code").firstOrNull()?.textContent?.trim().orEmpty()
            val text = fault.all("Text").firstOrNull()?.textContent?.trim()
                ?: fault.all("faultstring").firstOrNull()?.textContent?.trim().orEmpty()
            throw BcException(code, faultMessage(code, text))
        }
        val resp = root.all("GetDownloadFileListResponse_v2").firstOrNull()
            ?: throw IllegalStateException("ČSOB Business Connector: neočekávaná odpověď")
        val files = resp.all("FileDetail").map { f ->
            FileDetail(
                url = f.child("Url")?.trim()?.takeIf { it.isNotEmpty() },
                filename = f.child("Filename")?.trim().orEmpty(),
                type = f.child("Type")?.trim().orEmpty(),
                created = f.child("CreationDateTime")?.trim().orEmpty(),
                size = f.child("Size")?.trim()?.toLongOrNull() ?: 0,
                status = f.child("Status")?.trim().orEmpty(),
            )
        }
        return FileList(resp.child("QueryTimestamp")?.trim().orEmpty(), files, resp.child("TicketId")?.trim())
    }

    fun faultMessage(code: String, text: String?): String = when (code) {
        "1002" -> "ČSOB: smlouva CEB nemá povolený Business Connector. Povolte ho v CEB → Nastavení → Business Connector."
        "1011" -> "ČSOB: certifikát není u smlouvy registrován, nebo číslo smlouvy nesouhlasí. Zkontrolujte číslo smlouvy CEB a certifikát v CEB → Nastavení → Business Connector."
        "1012" -> "ČSOB: certifikát je v CEB zablokován."
        "1101" -> "ČSOB: příliš mnoho dotazů, banka přístup na chvíli zablokovala. Zkusím to znovu za 20 minut."
        else -> "ČSOB Business Connector: chyba ${code.ifBlank { "?" }}${text?.takeIf { it.isNotBlank() }?.let { " – $it" } ?: ""}"
    }

    /** Timestamp to start from on the first call: a few days back, not the 45 days the bank would use. */
    fun startTimestamp(daysBack: Long = 3): String =
        OffsetDateTime.now(ZoneId.of("Europe/Prague")).minusDays(daysBack).withNano(0).format(TS)

    /** An HTTPS client that authenticates with the key and certificate in [keyStore]. */
    class Client(keyStore: KeyStore, keyPassword: CharArray, private val endpoint: String = PROD, private val timeoutMs: Int = 30_000) {
        private val socketFactory: SSLSocketFactory = SSLContext.getInstance("TLS").apply {
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, keyPassword)
            init(kmf.keyManagers, null, null)
        }.socketFactory

        fun list(contract: String, prevQueryTimestamp: String?, types: List<String> = listOf("AVIZO", "VYPIS"), appGuid: String? = null): FileList {
            val body = listRequest(contract, prevQueryTimestamp, types, appGuid).toByteArray(Charsets.UTF_8)
            val conn = open(endpoint)
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
                conn.setRequestProperty("SOAPAction", "\"GetDownloadFileList_v2\"")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                val answer = (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
                if (code == 503) throw IllegalStateException("ČSOB Business Connector je dočasně mimo provoz (údržba)")
                if (answer.isEmpty()) throw IllegalStateException("ČSOB Business Connector: HTTP $code")
                return runCatching { parseList(answer) }.getOrElse { e ->
                    if (e is BcException || code < 400) throw e
                    throw IllegalStateException("ČSOB Business Connector: HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }

        fun download(url: String): ByteArray {
            val conn = open(url)
            try {
                when (val code = conn.responseCode) {
                    200 -> return conn.inputStream.use { it.readBytes() }
                    400, 404 -> throw IllegalStateException("ČSOB: soubor už nelze stáhnout (platí 15 dní)")
                    401 -> throw IllegalStateException("ČSOB: banka odmítla certifikát při stahování souboru")
                    else -> throw IllegalStateException("ČSOB: stažení souboru selhalo (HTTP $code)")
                }
            } finally {
                conn.disconnect()
            }
        }

        private fun open(url: String): HttpsURLConnection {
            val conn = URI(url).toURL().openConnection() as? HttpsURLConnection
                ?: throw IllegalArgumentException("ČSOB: adresa musí být https")
            conn.sslSocketFactory = socketFactory
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            return conn
        }
    }

    /** Incoming payments in a downloaded file: an avízo in MT942, or a statement in a format Flos reads (GPC/ABO, CAMT.053, also zipped). */
    fun paymentsIn(file: FileDetail, bytes: ByteArray): List<IncomingPayment> {
        if (Mt942Parser.looksLike(bytes)) return Mt942Parser.parse(bytes)
        return runCatching { StatementParser.parse(bytes) }.getOrDefault(emptyList())
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun Element.all(local: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", local)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.child(local: String): String? {
        var n = firstChild
        while (n != null) {
            if (n is Element && (n.localName ?: n.nodeName.substringAfter(':')) == local) return n.textContent
            n = n.nextSibling
        }
        return null
    }
}
