package cz.flos.core.bank

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Mailbox that receives the bank's payment notifications. */
data class ImapAccount(
    val host: String,
    val port: Int = 993,
    val user: String,
    val password: String,
    val folder: String = "INBOX",
)

/** A message fetched from the mailbox, still as raw RFC 822 bytes. */
data class FetchedMail(val uid: Long, val raw: ByteArray)

/**
 * A minimal IMAP4rev1 client over TLS: login, select a folder and fetch messages newer than a
 * known UID. It never deletes or marks messages (BODY.PEEK), so the mailbox stays as it was.
 */
class ImapClient(
    private val account: ImapAccount,
    private val timeoutMs: Int = 20_000,
    private val socketFactory: () -> Socket = { verifiedTlsSocket(account.host, account.port, timeoutMs) },
) {
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private var tag = 0

    /** UIDVALIDITY of the selected folder; when it changes, stored UIDs no longer apply. */
    var uidValidity: Long = 0
        private set

    class ImapException(message: String) : Exception(message)

    /**
     * Fetches messages with UID greater than [afterUid]. On the first run ([afterUid] = 0) only
     * messages from the last [firstRunDays] days are read so an old mailbox is not downloaded.
     */
    fun fetchNew(afterUid: Long, firstRunDays: Int = 3, max: Int = 50): List<FetchedMail> {
        val socket = socketFactory()
        socket.use { s ->
            if (!s.isConnected) s.connect(InetSocketAddress(account.host, account.port), timeoutMs)
            s.soTimeout = timeoutMs
            input = BufferedInputStream(s.getInputStream())
            output = s.getOutputStream()
            val greeting = readLine()
            if (!greeting.startsWith("* OK") && !greeting.startsWith("* PREAUTH")) throw ImapException("Server neodpovídá jako IMAP: $greeting")
            command("LOGIN ${quote(account.user)} ${quote(account.password)}")
            val select = command("SELECT ${quote(account.folder)}")
            uidValidity = select.firstNotNullOfOrNull { Regex("""UIDVALIDITY (\d+)""").find(it)?.groupValues?.get(1)?.toLong() } ?: 0
            val search = if (afterUid > 0) "UID SEARCH UID ${afterUid + 1}:*" else "UID SEARCH SINCE ${imapDate(firstRunDays)}"
            val uids = command(search).filter { it.startsWith("* SEARCH") }
                .flatMap { it.removePrefix("* SEARCH").trim().split(' ') }
                .mapNotNull { it.toLongOrNull() }.filter { it > afterUid }.sorted().takeLast(max)
            val mails = uids.mapNotNull { uid -> fetch(uid)?.let { FetchedMail(uid, it) } }
            runCatching { command("LOGOUT") }
            return mails
        }
    }

    private fun fetch(uid: Long): ByteArray? {
        val t = nextTag()
        send("$t UID FETCH $uid (BODY.PEEK[])")
        var body: ByteArray? = null
        while (true) {
            val line = readLine()
            val literal = Regex("""\{(\d+)\}$""").find(line)
            if (line.startsWith("* ") && literal != null) {
                body = readBytes(literal.groupValues[1].toInt())
                readLine() // closing ")" of the FETCH response
            } else if (line.startsWith("$t ")) {
                if (!line.startsWith("$t OK")) throw ImapException(line.removePrefix("$t ").trim())
                return body
            }
        }
    }

    private fun command(cmd: String): List<String> {
        val t = nextTag()
        send("$t $cmd")
        val lines = mutableListOf<String>()
        while (true) {
            val line = readLine()
            val literal = Regex("""\{(\d+)\}$""").find(line)
            if (literal != null) readBytes(literal.groupValues[1].toInt())
            if (line.startsWith("$t ")) {
                if (!line.startsWith("$t OK")) {
                    val safe = if (cmd.startsWith("LOGIN")) "LOGIN" else cmd
                    throw ImapException("$safe: " + line.removePrefix("$t ").trim())
                }
                return lines
            }
            lines += line
        }
    }

    private fun nextTag() = "F" + (++tag).toString().padStart(3, '0')

    private fun send(line: String) {
        output.write((line + "\r\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun readLine(): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw ImapException("Spojení s poštovním serverem bylo ukončeno")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return String(buf.toByteArray(), Charsets.UTF_8)
    }

    private fun readBytes(n: Int): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(out, read, n - read)
            if (r < 0) throw ImapException("Spojení s poštovním serverem bylo ukončeno")
            read += r
        }
        return out
    }

    companion object {
        fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        fun imapDate(daysBack: Int): String {
            val d = java.time.LocalDate.now().minusDays(daysBack.toLong())
            return "${d.dayOfMonth}-${MONTHS[d.monthValue - 1]}-${d.year}"
        }
    }
}

/**
 * Opens a TLS connection and checks that the server's certificate really belongs to [host].
 * A plain SSL socket only checks that the certificate is signed by a trusted authority, not
 * whose it is, so on a public Wi-Fi someone could pose as the mail server and read the password.
 */
internal fun verifiedTlsSocket(host: String, port: Int, timeoutMs: Int): Socket {
    val plain = Socket()
    try {
        plain.connect(InetSocketAddress(host, port), timeoutMs)
        plain.soTimeout = timeoutMs
        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        tls.startHandshake()
        // Android's TLS stack may not apply the setting above on every version, so check again there.
        if (System.getProperty("java.vendor").orEmpty().contains("Android", ignoreCase = true) &&
            !HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.session)
        ) {
            tls.close()
            throw javax.net.ssl.SSLPeerUnverifiedException("Certifikát serveru nepatří k $host")
        }
        return tls
    } catch (e: Exception) {
        runCatching { plain.close() }
        throw e
    }
}

