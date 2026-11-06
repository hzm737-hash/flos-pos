package cz.flos.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.fazecast.jSerialComm.SerialPort
import cz.flos.pos.Platform
import cz.flos.pos.PrinterDevice
import cz.flos.pos.print.PrinterType
import cz.flos.pos.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.IOException
import javax.print.DocFlavor
import javax.print.PrintServiceLookup
import javax.print.SimpleDoc
import javax.print.attribute.HashPrintRequestAttributeSet

/**
 * Windows side of Flos.
 *
 * Printers:
 * - BLUETOOTH: a COM port. A Bluetooth printer paired in Windows appears as "Standard Serial over
 *   Bluetooth link (COMx)"; USB printers with a serial driver show up as COM ports as well.
 * - SYSTEM: a printer installed in Windows (USB thermal printers usually are); bytes are sent in RAW mode.
 * - NETWORK: TCP port 9100, shared with Android.
 */
class DesktopPlatform(override val dataDir: File) : Platform {
    private val mutex = Mutex()
    internal val retrySignal = Channel<Unit>(Channel.CONFLATED)

    override val printerTypes = listOf(PrinterType.SYSTEM, PrinterType.BLUETOOTH, PrinterType.NETWORK)

    override fun printerTypeLabel(type: PrinterType) = when (type) {
        PrinterType.SYSTEM -> tr("Tiskárna ve Windows")
        PrinterType.BLUETOOTH -> tr("Bluetooth / COM port")
        PrinterType.NETWORK -> tr("Wi-Fi / síť")
    }

    override fun printerHelp(type: PrinterType) = when (type) {
        PrinterType.SYSTEM -> tr("Tiskárnu nejdřív nainstalujte ve Windows (Nastavení → Bluetooth a zařízení → Tiskárny). Flos jí posílá data přímo (RAW).")
        else -> tr("Bluetooth tiskárnu spárujte ve Windows. Objeví se jako port COM (Standard Serial over Bluetooth link).")
    }

    override fun canUsePrinters() = true

    override fun localPrinters(type: PrinterType): List<PrinterDevice> = when (type) {
        PrinterType.SYSTEM -> PrintServiceLookup.lookupPrintServices(null, null).map { PrinterDevice(it.name, it.name) }
        PrinterType.BLUETOOTH -> SerialPort.getCommPorts().map { PrinterDevice(it.descriptivePortName, it.systemPortName) }
        PrinterType.NETWORK -> emptyList()
    }.sortedBy { it.name }

    override suspend fun printLocal(type: PrinterType, address: String, data: ByteArray) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (address.isBlank()) throw IOException(tr("Není vybrána tiskárna. Vyberte ji v Nastavení."))
            when (type) {
                PrinterType.SYSTEM -> printRaw(address, data)
                PrinterType.BLUETOOTH -> printSerial(address, data)
                PrinterType.NETWORK -> error("network printers are handled by NetworkPrinter")
            }
        }
    }

    private fun printRaw(name: String, data: ByteArray) {
        val service = PrintServiceLookup.lookupPrintServices(null, null).firstOrNull { it.name == name }
            ?: throw IOException(tr("Tiskárna %s není ve Windows nainstalovaná.", name))
        service.createPrintJob().print(SimpleDoc(data, DocFlavor.BYTE_ARRAY.AUTOSENSE, null), HashPrintRequestAttributeSet())
    }

    private fun printSerial(portName: String, data: ByteArray) {
        val port = SerialPort.getCommPort(portName)
        port.baudRate = 9600
        port.setComPortTimeouts(SerialPort.TIMEOUT_WRITE_BLOCKING, 0, 10_000)
        if (!port.openPort(2_000)) throw IOException(tr("Port %s nelze otevřít. Je tiskárna zapnutá a spárovaná?", portName))
        try {
            var offset = 0
            while (offset < data.size) {
                val len = minOf(512, data.size - offset)
                val written = port.writeBytes(data, len, offset)
                if (written <= 0) throw IOException(tr("Tisk na port %s se nezdařil.", portName))
                offset += written
                Thread.sleep(15)
            }
            Thread.sleep(400)
        } finally {
            port.closePort()
        }
    }

    override fun scheduleEetRetry() {
        retrySignal.trySend(Unit)
    }

    @Composable
    override fun rememberFilePicker(onPicked: (ByteArray) -> Unit): () -> Unit = remember(onPicked) {
        {
            val dialog = FileDialog(null as Frame?, tr("Vyberte soubor"), FileDialog.LOAD)
            dialog.isVisible = true
            val file = dialog.file?.let { File(dialog.directory, it) }
            if (file != null) runCatching { file.readBytes() }.getOrNull()?.let(onPicked)
        }
    }

    @Composable
    override fun rememberFileSaver(mime: String, onDone: (Boolean) -> Unit): (String, ByteArray) -> Unit = remember(mime, onDone) {
        { name, bytes ->
            val dialog = FileDialog(null as Frame?, tr("Uložit soubor"), FileDialog.SAVE)
            dialog.file = name
            dialog.isVisible = true
            val file = dialog.file?.let { File(dialog.directory, it) }
            onDone(file != null && runCatching { file.writeBytes(bytes) }.isSuccess)
        }
    }

    override fun openUrl(url: String): Boolean = runCatching {
        val d = java.awt.Desktop.getDesktop()
        val uri = java.net.URI(url)
        when {
            url.startsWith("mailto:") && d.isSupported(java.awt.Desktop.Action.MAIL) -> d.mail(uri)
            url.startsWith("http") && d.isSupported(java.awt.Desktop.Action.BROWSE) -> d.browse(uri)
            else -> error("Nepodporováno")
        }
    }.isSuccess

    @Composable
    override fun rememberPrinterPermission(onGranted: () -> Unit): () -> Unit = onGranted
}
