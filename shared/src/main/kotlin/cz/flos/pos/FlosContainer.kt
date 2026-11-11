package cz.flos.pos

import cz.flos.pos.data.FlosDatabase
import cz.flos.pos.data.SettingsStore
import cz.flos.pos.eet.EetService
import cz.flos.pos.print.NetworkPrinter
import cz.flos.pos.print.PrinterRouter
import cz.flos.pos.ui.I18n

/** The long-lived objects of a running till, created once by the Android or Windows entry point. */
class FlosContainer(
    val platform: Platform,
    val database: FlosDatabase,
    store: KeyValueStore,
) {
    val settings = SettingsStore(store)
    val printer = PrinterRouter(platform, NetworkPrinter())
    val bank = cz.flos.pos.bank.BankSync(settings, database.bank(), database.sales())
    val eet = EetService(platform.dataDir, database.eet()) { settings.state.value }
    val backOffice = cz.flos.pos.sync.BackOfficeSync(settings, database, imageDir = java.io.File(platform.dataDir, "images")) {
        val s = settings.state.value
        mapOf(
            "eetCertificate" to eet.hasCertificate(), "eetCertPassword" to s.eetCertPassword.isNotEmpty(),
            "imapPassword" to s.imapPassword.isNotEmpty(), "fioToken" to s.fioToken.isNotEmpty(),
        )
    }

    init {
        // Background work (EET retries) produces messages too, so set the language before any UI exists.
        I18n.language = settings.state.value.language
    }
}
