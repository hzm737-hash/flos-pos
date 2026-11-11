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

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<PairedPrinter> {
        if (!hasPermission()) return emptyList()
        return adapter?.bondedDevices.orEmpty()
            .map { PairedPrinter(it.name ?: it.address, it.address) }
            .sortedBy { it.name }
    }

    @SuppressLint("MissingPermission")
    suspend fun print(address: String, data: ByteArray) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (address.isBlank()) throw IOException(tr("Není vybrána tiskárna. Vyberte ji v Nastavení."))
            if (!hasPermission()) throw IOException(tr("Aplikace nemá oprávnění k Bluetooth."))
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
                socket.connect()
                val out = socket.outputStream
                // Small chunks with short pauses keep cheap printers from dropping data.
                var offset = 0
                while (offset < data.size) {
                    val len = minOf(CHUNK, data.size - offset)
                    out.write(data, offset, len)
                    out.flush()
                    offset += len
                    delay(15)
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
        private const val CHUNK = 512
    }
}
