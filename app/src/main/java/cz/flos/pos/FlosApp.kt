package cz.flos.pos

import android.app.Application
import cz.flos.pos.data.FlosDatabase
import cz.flos.pos.data.SettingsStore
import cz.flos.pos.print.BluetoothPrinter

class FlosApp : Application() {
    val database: FlosDatabase by lazy { FlosDatabase.create(this) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val printer: BluetoothPrinter by lazy { BluetoothPrinter(this) }
}
