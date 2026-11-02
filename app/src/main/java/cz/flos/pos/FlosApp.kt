package cz.flos.pos

import android.app.Application
import cz.flos.pos.data.FlosDatabase
import cz.flos.pos.data.SettingsStore
import cz.flos.pos.eet.EetRetryWorker
import cz.flos.pos.eet.EetService
import cz.flos.pos.print.BluetoothPrinter
import cz.flos.pos.print.NetworkPrinter
import cz.flos.pos.print.PrinterRouter

class FlosApp : Application() {
    val database: FlosDatabase by lazy { FlosDatabase.create(this) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val printer: PrinterRouter by lazy { PrinterRouter(BluetoothPrinter(this), NetworkPrinter()) }
    val eet: EetService by lazy { EetService(this, database.eet()) { settings.state.value } }

    override fun onCreate() {
        super.onCreate()
        // Background work (EET retries) produces messages too, so set the language before any UI exists.
        cz.flos.pos.ui.I18n.language = settings.state.value.language
        EetRetryWorker.schedulePeriodic(this)
    }
}
