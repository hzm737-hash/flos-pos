package cz.flos.pos.print

import cz.flos.pos.ui.tr
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

data class PairedPrinter(val name: String, val address: String)

/**
 * Sends ESC/POS bytes to a paired Bluetooth thermal printer over the classic Serial Port Profile.
 * Pair the printer in Android's Bluetooth settings first, then pick it in Flos settings.
 */
class BluetoothPrinter(private val context: Context) {

    private val mutex = Mutex()
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Connecting to a paired printer; enough to list and use it. */
    private fun canConnect(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(Manifest.permission.BLUETOOTH_CONNECT)

    /** Both "Nearby devices" permissions (Android 12+), so nothing in the Bluetooth stack is refused while printing. */
    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || (canConnect() && granted(Manifest.permission.BLUETOOTH_SCAN))

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<PairedPrinter> {
        if (!canConnect()) return emptyList()
        return adapter?.bondedDevices.orEmpty()
            .map { PairedPrinter(it.name ?: it.address, it.address) }
            .sortedBy { it.name }
    }

    @SuppressLint("MissingPermission")
    suspend fun print(address: String, data: ByteArray) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (address.isBlank()) throw IOException(tr("Není vybrána tiskárna. Vyberte ji v Nastavení."))
            if (!canConnect()) throw IOException(tr("Aplikace nemá oprávnění k Bluetooth."))
            val bt = adapter ?: throw IOException(tr("Zařízení nemá Bluetooth."))
            if (!bt.isEnabled) throw IOException(tr("Bluetooth je vypnutý."))
            val device = bt.getRemoteDevice(address)
            // Flos never scans for devices, so there is normally no discovery to stop. Android 12+
            // needs the separate "scan" permission for cancelDiscovery(); without it, skip the call
            // instead of failing the print.
            val mayCancelDiscovery = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            if (mayCancelDiscovery) runCatching { if (bt.isDiscovering) bt.cancelDiscovery() }
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            try {
                try {
                    socket.connect()
                } catch (e: SecurityException) {
                    // Some Android versions stop discovery inside connect(), which needs the scan permission.
                    throw IOException(tr("Povolte aplikaci Flos „Zařízení v okolí“: Nastavení → Zkušební tisk, nebo v Androidu Aplikace → Flos → Oprávnění."), e)
                }
                val out = socket.outputStream
                // Some printers lose the first bytes sent right after the link opens.
                delay(200)
                // Small chunks with pauses: the printer prints slower than Bluetooth sends, and a cheap printer
                // whose buffer overflows drops bytes, so a QR code turns into rows of random characters.
                var offset = 0
                while (offset < data.size) {
                    val len = minOf(CHUNK, data.size - offset)
                    out.write(data, offset, len)
                    out.flush()
                    offset += len
                    delay(PAUSE_MS)
                }
                // Give the printer time to empty its buffer before the link closes.
                delay(400)
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val CHUNK = 128
        /** About 4 KB a second, well below what a 58 or 80 mm thermal printer prints. */
        private const val PAUSE_MS = 30L
    }
}
