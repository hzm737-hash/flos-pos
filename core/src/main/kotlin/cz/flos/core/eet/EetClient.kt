package cz.flos.core.eet

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Sends sales to EET 2.0 over HTTPS (TLS 1.2+, server certificate checked by the platform)
 * and turns the answer into an [EetResult].
 *
 * @param timeoutMs response time limit. The law sets the "mezní doba odezvy" to at least
 *   2 seconds; if no answer arrives in time, the sale is stored and sent again later.
 */
class EetClient(
    private val environment: EetEnvironment,
    private val certificate: EetCertificate,
    private val timeoutMs: Int = 3000,
    private val transport: (url: String, body: ByteArray, timeoutMs: Int) -> Pair<Int, ByteArray> = ::httpPost,
) {
    data class Attempt(val result: EetResult, val uuid: String, val requestXml: String)

    fun send(sale: EetSale, firstAttempt: Boolean, verification: Boolean = false): Attempt {
        val problems = sale.validate()
        if (problems.isNotEmpty()) {
            return Attempt(EetResult.Error(null, problems.joinToString("; "), retryable = false), "", "")
        }
        if (!certificate.isValidAt()) {
            return Attempt(EetResult.Error(null, "Pokladní certifikát EET je neplatný nebo mu skončila platnost.", retryable = true), "", "")
        }
        // A new UUID and send time for every attempt; the Data part stays the same.
        val msg = EetMessage.build(sale, certificate, firstAttempt, verification)
        val bytes = msg.xml.toByteArray(Charsets.UTF_8)
        if (bytes.size > EetMessage.MAX_BYTES) {
            return Attempt(EetResult.Error(7, EetFormat.errorText(7), retryable = false), msg.uuid, msg.xml)
        }
        val result = try {
            val (status, response) = transport(environment.url, bytes, timeoutMs)
            parseResponse(status, response)
        } catch (e: SocketTimeoutException) {
            EetResult.Error(null, "Překročena mezní doba odezvy (${timeoutMs / 1000.0} s). Tržba bude odeslána znovu.", retryable = true)
        } catch (e: IOException) {
            EetResult.Error(null, "Nelze se spojit se serverem EET: ${e.message ?: e.javaClass.simpleName}", retryable = true)
        }
        return Attempt(result, msg.uuid, msg.xml)
    }

    companion object {
        fun parseResponse(httpStatus: Int, body: ByteArray): EetResult {
            val doc = try {
                DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = true
                    // Android's parser rejects this feature (and never loads DTDs anyway).
                    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                }.newDocumentBuilder().parse(ByteArrayInputStream(body))
            } catch (e: Exception) {
                return EetResult.Error(null, "Neplatná odpověď serveru (HTTP $httpStatus)", retryable = httpStatus >= 500 || httpStatus == 200)
            }
            val fault = doc.getElementsByTagNameNS(EetMessage.NS_SOAP, "Fault").item(0) as? Element
            if (fault != null) {
                val text = fault.getElementsByTagName("faultstring").item(0)?.textContent?.trim() ?: "SOAP Fault"
                return EetResult.Error(null, "Server EET vrátil chybu: $text", retryable = true)
            }
            val warnings = doc.getElementsByTagNameNS(EetMessage.NS_V4, "Varovani").let { list ->
                (0 until list.length).map { list.item(it) as Element }.map { el ->
                    val code = el.getAttribute("kod_varov").toIntOrNull() ?: 0
                    EetWarning(code, el.textContent.trim().ifEmpty { EetFormat.warningText(code) })
                }
            }
            val ok = doc.getElementsByTagNameNS(EetMessage.NS_V4, "Potvrzeni").item(0) as? Element
            if (ok != null) {
                val pok = ok.getAttribute("pok")
                if (!POK.matches(pok)) return EetResult.Error(null, "Odpověď neobsahuje platný potvrzovací kód", retryable = true, warnings)
                val received = (doc.getElementsByTagNameNS(EetMessage.NS_V4, "Hlavicka").item(0) as? Element)?.getAttribute("dat_prij")
                return EetResult.Confirmed(pok, ok.getAttribute("test") == "true", warnings, received?.ifEmpty { null })
            }
            val err = doc.getElementsByTagNameNS(EetMessage.NS_V4, "Chyba").item(0) as? Element
            if (err != null) {
                val code = err.getAttribute("kod").toIntOrNull()
                if (code == 0) return EetResult.VerifiedOk(warnings)
                val text = err.textContent.trim().ifEmpty { code?.let(EetFormat::errorText) ?: "Neznámá chyba" }
                return EetResult.Error(code, text, retryable = code == -1, warnings = warnings)
            }
            return EetResult.Error(null, "Neočekávaná odpověď serveru (HTTP $httpStatus)", retryable = true)
        }

        private val POK = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}-[0-9a-fA-F]{2}")

        /** HTTP/1.1 POST with the SOAP 1.1 headers required by the interface description. */
        fun httpPost(url: String, body: ByteArray, timeoutMs: Int): Pair<Int, ByteArray> {
            val conn = URL(url).openConnection() as HttpsURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.useCaches = false
                conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
                conn.setRequestProperty("SOAPAction", "\"${EetMessage.SOAP_ACTION}\"")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
                val status = conn.responseCode
                val stream = if (status >= HttpURLConnection.HTTP_BAD_REQUEST) conn.errorStream else conn.inputStream
                return status to (stream?.use { it.readBytes() } ?: ByteArray(0))
            } finally {
                conn.disconnect()
            }
        }
    }
}
