package cz.flos.pos

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cz.flos.pos.eet.EetRetryWorker
import cz.flos.pos.print.BluetoothPrinter
import cz.flos.pos.print.PrinterType
import cz.flos.pos.ui.tr
import java.io.File

class AndroidPlatform(private val context: Context) : Platform {
    private val bluetooth = BluetoothPrinter(context)

    override val dataDir: File get() = context.filesDir
    override val printerTypes = listOf(PrinterType.BLUETOOTH, PrinterType.NETWORK)

    override fun printerTypeLabel(type: PrinterType) = when (type) {
        PrinterType.BLUETOOTH -> tr("Bluetooth")
        PrinterType.NETWORK -> tr("Wi-Fi / síť")
        PrinterType.SYSTEM -> tr("Systémová tiskárna")
    }

    override fun printerHelp(type: PrinterType) =
        tr("Tiskárnu nejdřív spárujte v nastavení Bluetooth v Androidu, potom ji zde vyberte.")

    override fun canUsePrinters() = bluetooth.hasPermission()

    override fun localPrinters(type: PrinterType): List<PrinterDevice> =
        bluetooth.pairedDevices().map { PrinterDevice(it.name, it.address) }

    override suspend fun printLocal(type: PrinterType, address: String, data: ByteArray) = bluetooth.print(address, data)

    override fun scheduleEetRetry() = EetRetryWorker.scheduleSoon(context)

    @Composable
    override fun rememberFilePicker(onPicked: (ByteArray) -> Unit): () -> Unit {
        val ctx = LocalContext.current
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            // A file the system cannot open (removed, no permission) is ignored instead of crashing.
            if (uri != null) runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()?.let(onPicked)
        }
        return { runCatching { launcher.launch(arrayOf("application/x-pkcs12", "application/octet-stream", "*/*")) } }
    }

    @Composable
    override fun rememberFileSaver(mime: String, onDone: (Boolean) -> Unit): (String, ByteArray) -> Unit {
        val ctx = LocalContext.current
        val pending = remember { arrayOfNulls<ByteArray>(1) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
            val bytes = pending[0]
            pending[0] = null
            if (uri == null || bytes == null) { onDone(false); return@rememberLauncherForActivityResult }
            val ok = runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null }.getOrDefault(false)
            onDone(ok)
        }
        return { name, bytes -> pending[0] = bytes; if (runCatching { launcher.launch(name) }.isFailure) { pending[0] = null; onDone(false) } }
    }

    override fun openUrl(url: String): Boolean = runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    @Composable
    override fun rememberPrinterPermission(onGranted: () -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) onGranted()
        }
        return {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) else onGranted()
        }
    }
}
