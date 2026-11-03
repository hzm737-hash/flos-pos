package cz.flos.pos.data

import cz.flos.core.BusinessInfo
import cz.flos.core.CzechBankAccount
import cz.flos.core.PaperWidth
import cz.flos.core.PrinterCharset
import cz.flos.core.eet.EetEnvironment
import cz.flos.pos.KeyValueStore
import cz.flos.pos.print.NetworkPrinter
import cz.flos.pos.print.PrinterType
import cz.flos.pos.ui.Language
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    /** Language of the till screens. Receipts always print in Czech. */
    val language: Language = Language.CS,
    val businessName: String = "Moje provozovna",
    val address1: String = "",
    val address2: String = "",
    val ico: String = "",
    val dic: String = "",
    val vatPayer: Boolean = false,
    val footer: String = "Děkujeme za nákup!",
    /** As the user typed it: a domestic account ("19-2000145399/0800") or an IBAN. */
    val bankAccount: String = "",
    val cashier: String = "",
    val paperWidth: PaperWidth = PaperWidth.MM58,
    val charset: PrinterCharset = PrinterCharset.ASCII,
    val printerAddress: String = "",
    val printerName: String = "",
    val autoPrint: Boolean = true,
    val printerType: PrinterType = PrinterType.BLUETOOTH,
    /** IP address or host name of a Wi-Fi / LAN printer. */
    val printerHost: String = "",
    val printerPort: Int = NetworkPrinter.DEFAULT_PORT,

    // ---- EET 2.0 ----
    val eetEnabled: Boolean = false,
    val eetEnvironment: EetEnvironment = EetEnvironment.PLAYGROUND,
    /** Verification mode (overeni): messages are checked but sales are not recorded. */
    val eetVerification: Boolean = false,
    /** EIČ; when empty the DIČ is used. */
    val eetEic: String = "",
    val eetIdJednotky: String = "",
    val eetIdPokl: String = "POKL1",
    /** Response time limit; the law requires at least 2 seconds. */
    val eetTimeoutMs: Int = 3000,
    val eetCertPassword: String = "",
) {
    val effectiveEic: String get() = eetEic.ifBlank { dic }.replace(" ", "").uppercase()

    val iban: String? get() = bankAccount.takeIf { it.isNotBlank() }?.let { CzechBankAccount.toIban(it) }

    val business: BusinessInfo
        get() = BusinessInfo(
            name = businessName,
            addressLines = listOf(address1, address2),
            ico = ico,
            dic = dic,
            vatPayer = vatPayer,
            footer = footer,
        )
}

/** Settings live in a [KeyValueStore] and are exposed as a StateFlow for the UI. */
class SettingsStore(private val prefs: KeyValueStore) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun str(key: String, def: String) = prefs.get(key) ?: def
    private fun int(key: String, def: Int) = prefs.get(key)?.toIntOrNull() ?: def
    private fun bool(key: String, def: Boolean) = prefs.get(key)?.toBooleanStrictOrNull() ?: def
    private inline fun <reified E : Enum<E>> enum(key: String, def: E): E =
        prefs.get(key)?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: def

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            language = Language.entries.firstOrNull { it.code == prefs.get("language") } ?: d.language,
            businessName = str("businessName", d.businessName),
            address1 = str("address1", d.address1),
            address2 = str("address2", d.address2),
            ico = str("ico", d.ico),
            dic = str("dic", d.dic),
            vatPayer = bool("vatPayer", d.vatPayer),
            footer = str("footer", d.footer),
            bankAccount = str("bankAccount", d.bankAccount),
            cashier = str("cashier", d.cashier),
            paperWidth = enum("paperWidth", d.paperWidth),
            charset = enum("charset", d.charset),
            printerAddress = str("printerAddress", d.printerAddress),
            printerName = str("printerName", d.printerName),
            autoPrint = bool("autoPrint", d.autoPrint),
            printerType = enum("printerType", d.printerType),
            printerHost = str("printerHost", d.printerHost),
            printerPort = int("printerPort", d.printerPort),
            eetEnabled = bool("eetEnabled", d.eetEnabled),
            eetEnvironment = enum("eetEnvironment", d.eetEnvironment),
            eetVerification = bool("eetVerification", d.eetVerification),
            eetEic = str("eetEic", d.eetEic),
            eetIdJednotky = str("eetIdJednotky", d.eetIdJednotky),
            eetIdPokl = str("eetIdPokl", d.eetIdPokl),
            eetTimeoutMs = int("eetTimeoutMs", d.eetTimeoutMs),
            eetCertPassword = str("eetCertPassword", d.eetCertPassword),
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_state.value)
        prefs.put(
            mapOf(
                "language" to s.language.code,
                "businessName" to s.businessName,
                "address1" to s.address1,
                "address2" to s.address2,
                "ico" to s.ico,
                "dic" to s.dic,
                "vatPayer" to s.vatPayer.toString(),
                "footer" to s.footer,
                "bankAccount" to s.bankAccount,
                "cashier" to s.cashier,
                "paperWidth" to s.paperWidth.name,
                "charset" to s.charset.name,
                "printerAddress" to s.printerAddress,
                "printerName" to s.printerName,
                "autoPrint" to s.autoPrint.toString(),
                "printerType" to s.printerType.name,
                "printerHost" to s.printerHost,
                "printerPort" to s.printerPort.toString(),
                "eetEnabled" to s.eetEnabled.toString(),
                "eetEnvironment" to s.eetEnvironment.name,
                "eetVerification" to s.eetVerification.toString(),
                "eetEic" to s.eetEic,
                "eetIdJednotky" to s.eetIdJednotky,
                "eetIdPokl" to s.eetIdPokl,
                "eetTimeoutMs" to s.eetTimeoutMs.toString(),
                "eetCertPassword" to s.eetCertPassword,
            ),
        )
        _state.value = s
    }

    private fun nextIn(key: String): Int = int(key, 0) + 1

    /** The number the next receipt will get, without using it up. */
    fun peekReceiptNumber(year: Int): String = "$year-" + nextIn("receiptSeq_$year").toString().padStart(6, '0')

    /** Next receipt number in the form "2025-000001"; the counter restarts every year. */
    @Synchronized
    fun nextReceiptNumber(year: Int): String {
        val key = "receiptSeq_$year"
        val next = nextIn(key)
        prefs.put(mapOf(key to next.toString()))
        return "$year-" + next.toString().padStart(6, '0')
    }

    /** Next number for an open order (delivery, table); separate from receipt numbers. */
    @Synchronized
    fun nextOrderNumber(): Int {
        val next = nextIn("orderSeq")
        prefs.put(mapOf("orderSeq" to next.toString()))
        return next
    }
}
