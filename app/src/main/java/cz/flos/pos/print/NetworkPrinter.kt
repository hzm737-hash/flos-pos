package cz.flos.pos.print

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Wi-Fi / LAN receipt printer. Sends raw ESC/POS bytes to TCP port 9100 (the "RAW" or
 * "JetDirect" port that almost every network thermal printer listens on).
 */
class NetworkPrinter {
    private val mutex = Mutex()

    suspend fun print(host: String, port: Int, data: ByteArray) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (host.isBlank()) throw IOException("Není zadána IP adresa tiskárny. Doplňte ji v Nastavení.")
            Socket().use { socket ->
                try {
                    socket.connect(InetSocketAddress(host.trim(), port), CONNECT_TIMEOUT_MS)
                } catch (e: IOException) {
                    throw IOException("Tiskárna $host:$port nereaguje. Je zapnutá a ve stejné Wi-Fi síti?", e)
                }
                socket.soTimeout = CONNECT_TIMEOUT_MS
                socket.getOutputStream().apply {
                    write(data)
                    flush()
                }
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 9100
        private const val CONNECT_TIMEOUT_MS = 5000
    }
}
