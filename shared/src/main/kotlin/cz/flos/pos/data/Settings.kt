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
    /** Send the drawer-kick pulse through the receipt printer for cash payments. */
    val openDrawer: Boolean = false,

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

    // ---- Bank payment confirmation and tips ----
    val bankSource: BankSource = BankSource.OFF,
    val imapHost: String = "",
    val imapPort: Int = 993,
    val imapUser: String = "",
    val imapPassword: String = "",
    val imapFolder: String = "INBOX",
    val fioToken: String = "",
    /** Who keeps the tips: staff (outside EET, tax-free up to 7 % of monthly catering revenue) or the business (recorded in EET). */
    val tipsOwner: TipsOwner = TipsOwner.STAFF,
    /** Account for SEPA payments from abroad (a Fio EUR account is best); empty = the main account. */
    val eurAccount: String = "",
    /** Own CZK per EUR rate, e.g. "24,50"; empty = today's ČNB rate. */
    val eurRateManual: String = "",
    /** After how many days an unpaid transfer is shown as overdue. */
    val transferDueDays: Int = 3,
    /** Table names for the table map, separated by commas ("1, 2, 3, Terasa 1"); empty hides the map. */
    val tables: String = "1, 2, 3, 4, 5, 6, 7, 8",
) {
    val tableList: List<String> get() = tables.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** IBAN for the SEPA QR code: the EUR account when set, otherwise the main account. */
    val eurIban: String? get() = eurAccount.takeIf { it.isNotBlank() }?.let { CzechBankAccount.toIban(it) } ?: iban
    val eurRateOverride: Double? get() = eurRateManual.replace(',', '.').trim().toDoubleOrNull()?.takeIf { it > 0 }
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

enum class BankSource(val label: String) {
    OFF("Vypnuto"),
    EMAIL("E-mail od banky (jakákoli banka)"),
    FIO("Fio banka API"),
}

enum class TipsOwner(val label: String) {
    STAFF("Zaměstnancům"),
    BUSINESS("Provozovně"),
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
            openDrawer = bool("openDrawer", d.openDrawer),
            eetEnabled = bool("eetEnabled", d.eetEnabled),
            eetEnvironment = enum("eetEnvironment", d.eetEnvironment),
            eetVerification = bool("eetVerification", d.eetVerification),
            eetEic = str("eetEic", d.eetEic),
            eetIdJednotky = str("eetIdJednotky", d.eetIdJednotky),
            eetIdPokl = str("eetIdPokl", d.eetIdPokl),
            eetTimeoutMs = int("eetTimeoutMs", d.eetTimeoutMs),
            eetCertPassword = str("eetCertPassword", d.eetCertPassword),
            bankSource = enum("bankSource", d.bankSource),
            imapHost = str("imapHost", d.imapHost),
            imapPort = int("imapPort", d.imapPort),
            imapUser = str("imapUser", d.imapUser),
            imapPassword = str("imapPassword", d.imapPassword),
            imapFolder = str("imapFolder", d.imapFolder),
            fioToken = str("fioToken", d.fioToken),
            tipsOwner = enum("tipsOwner", d.tipsOwner),
            eurAccount = str("eurAccount", d.eurAccount),
            eurRateManual = str("eurRateManual", d.eurRateManual),
            transferDueDays = int("transferDueDays", d.transferDueDays),
            tables = str("tables", d.tables),
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
                "openDrawer" to s.openDrawer.toString(),
                "eetEnabled" to s.eetEnabled.toString(),
                "eetEnvironment" to s.eetEnvironment.name,
                "eetVerification" to s.eetVerification.toString(),
                "eetEic" to s.eetEic,
                "eetIdJednotky" to s.eetIdJednotky,
                "eetIdPokl" to s.eetIdPokl,
                "eetTimeoutMs" to s.eetTimeoutMs.toString(),
                "eetCertPassword" to s.eetCertPassword,
                "bankSource" to s.bankSource.name,
                "imapHost" to s.imapHost,
                "imapPort" to s.imapPort.toString(),
                "imapUser" to s.imapUser,
                "imapPassword" to s.imapPassword,
                "imapFolder" to s.imapFolder,
                "fioToken" to s.fioToken,
                "tipsOwner" to s.tipsOwner.name,
                "eurAccount" to s.eurAccount,
                "eurRateManual" to s.eurRateManual,
                "transferDueDays" to s.transferDueDays.toString(),
                "tables" to s.tables,
            ),
        )
        _state.value = s
    }

    /** Where reading the mailbox stopped: UIDVALIDITY and the last UID read. */
    fun imapPosition(account: String): Pair<Long, Long> =
        (prefs.get("imapValidity_$account")?.toLongOrNull() ?: 0L) to (prefs.get("imapUid_$account")?.toLongOrNull() ?: 0L)

    fun saveImapPosition(account: String, validity: Long, uid: Long) =
        prefs.put(mapOf("imapValidity_$account" to validity.toString(), "imapUid_$account" to uid.toString()))

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
