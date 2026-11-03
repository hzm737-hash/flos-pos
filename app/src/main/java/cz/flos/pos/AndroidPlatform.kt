package cz.flos.pos

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
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
            if (uri != null) ctx.contentResolver.openInputStream(uri)?.use { onPicked(it.readBytes()) }
        }
        return { launcher.launch(arrayOf("application/x-pkcs12", "application/octet-stream", "*/*")) }
    }

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
