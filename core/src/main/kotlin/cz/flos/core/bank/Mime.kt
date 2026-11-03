package cz.flos.core.bank

import java.nio.charset.Charset
import java.util.Base64

/** Just enough MIME to read the text of a bank notification e-mail. */
object Mime {
    data class Message(val subject: String, val messageId: String?, val text: String)

    fun parse(raw: ByteArray): Message {
        val (headers, body) = split(raw)
        val text = partText(headers, body)
        return Message(decodeWords(headers["subject"].orEmpty()), headers["message-id"]?.trim(), text)
    }

    private fun split(raw: ByteArray): Pair<Map<String, String>, ByteArray> {
        val s = String(raw, Charsets.ISO_8859_1)
        val idx = listOf("\r\n\r\n", "\n\n").map { s.indexOf(it) to it.length }.filter { it.first >= 0 }.minByOrNull { it.first }
        val headerText = if (idx == null) s else s.substring(0, idx.first)
        val body = if (idx == null) ByteArray(0) else s.substring(idx.first + idx.second).toByteArray(Charsets.ISO_8859_1)
        val headers = LinkedHashMap<String, String>()
        var current: String? = null
        for (line in headerText.split(Regex("\r?\n"))) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && current != null) {
                headers[current] = headers[current] + " " + line.trim()
            } else {
                val colon = line.indexOf(':')
                if (colon > 0) {
                    current = line.substring(0, colon).trim().lowercase()
                    if (current !in headers) headers[current] = line.substring(colon + 1).trim()
                }
            }
        }
        return headers to body
    }

    private fun param(header: String?, name: String): String? =
        header?.let { Regex("""(?i)\b$name\s*=\s*(?:"([^"]*)"|([^;\s]+))""").find(it) }?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }

    private fun charsetOf(name: String?): Charset =
        runCatching { Charset.forName(name ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)

    private fun partText(headers: Map<String, String>, body: ByteArray): String {
        val type = headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() ?: "text/plain"
        if (type.startsWith("multipart/")) {
            val boundary = param(headers["content-type"], "boundary") ?: return ""
            val parts = splitMultipart(body, boundary).map { split(it) }
            val texts = parts.map { (h, b) -> (h["content-type"]?.substringBefore(';')?.trim()?.lowercase() ?: "text/plain") to partText(h, b) }
            // Prefer the plain text alternative, fall back to HTML converted to text.
            return texts.firstOrNull { it.first == "text/plain" && it.second.isNotBlank() }?.second
                ?: texts.firstOrNull { it.second.isNotBlank() }?.second ?: ""
        }
        if (!type.startsWith("text/")) return ""
        val decoded = when (headers["content-transfer-encoding"]?.trim()?.lowercase()) {
            "base64" -> runCatching { Base64.getMimeDecoder().decode(body) }.getOrDefault(ByteArray(0))
            "quoted-printable" -> quotedPrintable(body)
            else -> body
        }
        val text = String(decoded, charsetOf(param(headers["content-type"], "charset")))
        return if (type == "text/html") htmlToText(text) else text
    }

    private fun splitMultipart(body: ByteArray, boundary: String): List<ByteArray> {
        val s = String(body, Charsets.ISO_8859_1)
        val delimiter = "--$boundary"
        return s.split(delimiter).drop(1).takeWhile { !it.startsWith("--") }
            .map { it.removePrefix("\r\n").removePrefix("\n").toByteArray(Charsets.ISO_8859_1) }
    }

    fun quotedPrintable(input: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < input.size) {
            val b = input[i].toInt() and 0xff
            if (b == '='.code) {
                if (i + 1 < input.size && (input[i + 1] == '\r'.code.toByte() || input[i + 1] == '\n'.code.toByte())) {
                    // Soft line break.
                    i += if (input[i + 1] == '\r'.code.toByte() && i + 2 < input.size && input[i + 2] == '\n'.code.toByte()) 3 else 2
                    continue
                }
                val hex = if (i + 2 < input.size) String(byteArrayOf(input[i + 1], input[i + 2]), Charsets.US_ASCII) else ""
                val v = hex.toIntOrNull(16)
                if (v != null) { out.write(v); i += 3; continue }
            }
            out.write(b); i++
        }
        return out.toByteArray()
    }

    /** Decodes "=?UTF-8?B?...?=" and "=?windows-1250?Q?...?=" encoded words in headers. */
    fun decodeWords(s: String): String =
        Regex("""=\?([^?]+)\?([BbQq])\?([^?]*)\?=\s*""").replace(s) { m ->
            val cs = charsetOf(m.groupValues[1])
            val data = m.groupValues[3]
            val bytes = if (m.groupValues[2].equals("B", true)) runCatching { Base64.getDecoder().decode(data) }.getOrDefault(ByteArray(0))
            else quotedPrintable(data.replace('_', ' ').toByteArray(Charsets.US_ASCII))
            String(bytes, cs)
        }

    fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</tr>|</div>|</li>|</h\\d>"), "\n")
        .replace(Regex("(?i)</td>|</th>"), " ")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .lines().joinToString("\n") { it.trim().replace(Regex("[ \t]+"), " ") }
        .replace(Regex("\n{3,}"), "\n\n").trim()
}
