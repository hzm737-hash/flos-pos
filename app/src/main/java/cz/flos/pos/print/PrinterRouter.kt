package cz.flos.pos.print

import cz.flos.pos.data.AppSettings

enum class PrinterType(val label: String) {
    BLUETOOTH("Bluetooth"),
    NETWORK("Wi-Fi / síť"),
}

/** Sends a job to whichever printer type is selected in settings. */
class PrinterRouter(val bluetooth: BluetoothPrinter, val network: NetworkPrinter) {
    suspend fun print(s: AppSettings, data: ByteArray) = when (s.printerType) {
        PrinterType.BLUETOOTH -> bluetooth.print(s.printerAddress, data)
        PrinterType.NETWORK -> network.print(s.printerHost, s.printerPort, data)
    }

    fun isConfigured(s: AppSettings): Boolean = when (s.printerType) {
        PrinterType.BLUETOOTH -> s.printerAddress.isNotBlank()
        PrinterType.NETWORK -> s.printerHost.isNotBlank()
    }
}
