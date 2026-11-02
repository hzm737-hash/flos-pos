package cz.flos.pos.data

import android.content.Context
import androidx.core.content.edit
import cz.flos.core.BusinessInfo
import cz.flos.core.CzechBankAccount
import cz.flos.core.PaperWidth
import cz.flos.core.PrinterCharset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
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
) {
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

/** Settings live in SharedPreferences and are exposed as a StateFlow for the UI. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("flos_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            businessName = prefs.getString("businessName", d.businessName)!!,
            address1 = prefs.getString("address1", d.address1)!!,
            address2 = prefs.getString("address2", d.address2)!!,
            ico = prefs.getString("ico", d.ico)!!,
            dic = prefs.getString("dic", d.dic)!!,
            vatPayer = prefs.getBoolean("vatPayer", d.vatPayer),
            footer = prefs.getString("footer", d.footer)!!,
            bankAccount = prefs.getString("bankAccount", d.bankAccount)!!,
            cashier = prefs.getString("cashier", d.cashier)!!,
            paperWidth = runCatching { PaperWidth.valueOf(prefs.getString("paperWidth", d.paperWidth.name)!!) }.getOrDefault(d.paperWidth),
            charset = runCatching { PrinterCharset.valueOf(prefs.getString("charset", d.charset.name)!!) }.getOrDefault(d.charset),
            printerAddress = prefs.getString("printerAddress", d.printerAddress)!!,
            printerName = prefs.getString("printerName", d.printerName)!!,
            autoPrint = prefs.getBoolean("autoPrint", d.autoPrint),
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_state.value)
        prefs.edit {
            putString("businessName", s.businessName)
            putString("address1", s.address1)
            putString("address2", s.address2)
            putString("ico", s.ico)
            putString("dic", s.dic)
            putBoolean("vatPayer", s.vatPayer)
            putString("footer", s.footer)
            putString("bankAccount", s.bankAccount)
            putString("cashier", s.cashier)
            putString("paperWidth", s.paperWidth.name)
            putString("charset", s.charset.name)
            putString("printerAddress", s.printerAddress)
            putString("printerName", s.printerName)
            putBoolean("autoPrint", s.autoPrint)
        }
        _state.value = s
    }

    /** The number the next receipt will get, without using it up. */
    fun peekReceiptNumber(year: Int): String =
        "$year-" + (prefs.getInt("receiptSeq_$year", 0) + 1).toString().padStart(6, '0')

    /** Next receipt number in the form "2025-000001"; the counter restarts every year. */
    @Synchronized
    fun nextReceiptNumber(year: Int): String {
        val key = "receiptSeq_$year"
        val next = prefs.getInt(key, 0) + 1
        prefs.edit(commit = true) { putInt(key, next) }
        return "$year-" + next.toString().padStart(6, '0')
    }
}
