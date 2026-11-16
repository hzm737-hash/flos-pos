package cz.flos.pos.print

import cz.flos.pos.Platform
import cz.flos.pos.data.AppSettings

enum class PrinterType {
    /** Android: a paired Bluetooth printer. Windows: a COM port (Bluetooth or USB serial printer). */
    BLUETOOTH,
    /** Wi-Fi / LAN printer on TCP port 9100, the same on every platform. */
    NETWORK,
    /** Windows: a printer installed in Windows, used in RAW mode. */
    SYSTEM,
}

/** Sends a job to whichever printer type is selected in settings. */
class PrinterRouter(val platform: Platform, val network: NetworkPrinter) {
    suspend fun print(s: AppSettings, data: ByteArray) = when (s.printerType) {
        PrinterType.NETWORK -> network.print(s.printerHost, s.printerPort, data)
        else -> platform.printLocal(s.printerType, s.printerAddress, data, s.printerSpeed.factor)
    }

    fun isConfigured(s: AppSettings): Boolean = when (s.printerType) {
        PrinterType.NETWORK -> s.printerHost.isNotBlank()
        else -> s.printerAddress.isNotBlank()
    }
}
