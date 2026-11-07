package cz.flos.pos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import cz.flos.pos.print.PrinterType
import java.io.File

/** A printer the operating system knows about: a paired Bluetooth printer, a COM port or a Windows printer. */
data class PrinterDevice(val name: String, val address: String)

/** A voluntary donation amount offered under "Podpořit Flos" (a Google Play in-app product on Android). */
data class DonationOption(val id: String, val title: String, val price: String)

/**
 * Everything that differs between Android and Windows. The shared till code only talks to
 * this interface; app/ (Android) and desktop/ (Windows) each provide one implementation.
 */
interface Platform {
    /** Folder for the database, certificate and other private files. */
    val dataDir: File

    /** Printer connections offered in Settings, in display order. */
    val printerTypes: List<PrinterType>

    fun printerTypeLabel(type: PrinterType): String

    /** Short help shown under the printer list in Settings. */
    fun printerHelp(type: PrinterType): String

    /** True when the app may list and use local printers (Android asks for Bluetooth permission). */
    fun canUsePrinters(): Boolean

    /** Printers of a local [type] (BLUETOOTH or SYSTEM) that can be chosen in Settings. */
    fun localPrinters(type: PrinterType): List<PrinterDevice>

    /** Sends raw ESC/POS bytes to a local printer chosen from [localPrinters]. */
    suspend fun printLocal(type: PrinterType, address: String, data: ByteArray)

    /** Asks for an early retry of EET sending after a failure. */
    fun scheduleEetRetry()

    /** Returns a function that opens a file chooser; [onPicked] gets the file contents. */
    @Composable
    fun rememberFilePicker(onPicked: (ByteArray) -> Unit): () -> Unit

    /**
     * Returns a function that asks where to save a file (suggested name, contents) and writes it;
     * [onDone] gets true when the file was saved.
     */
    @Composable
    fun rememberFileSaver(mime: String, onDone: (Boolean) -> Unit): (String, ByteArray) -> Unit

    /** Opens a link in the matching app (WhatsApp, SMS, e-mail, browser); false when nothing can open it. */
    fun openUrl(url: String): Boolean

    /** Returns a function that asks for the permission needed by [canUsePrinters], then calls [onGranted]. */
    @Composable
    fun rememberPrinterPermission(onGranted: () -> Unit): () -> Unit

    /** Donation amounts the user can choose; empty when donations are not available here. */
    suspend fun donationOptions(): List<DonationOption> = emptyList()

    /** Starts paying a donation; [onDone] gets true once the payment went through. */
    fun donate(option: DonationOption, onDone: (Boolean) -> Unit) = onDone(false)
}

val LocalPlatform = staticCompositionLocalOf<Platform> { error("No platform") }

/** Simple string key–value storage (SharedPreferences on Android, the registry on Windows). */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(values: Map<String, String>)
}
