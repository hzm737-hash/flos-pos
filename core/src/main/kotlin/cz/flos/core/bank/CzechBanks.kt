package cz.flos.core.bank

/**
 * Czech banks by their four-digit bank code (the part after "/" in "19-2000145399/0800"),
 * with how each one can tell Flos that a payment arrived. Every bank works through e-mail
 * notifications or statements; only some offer a direct API the shop can switch on itself.
 *
 * The texts are guidance for the shop owner; menu names at the banks change from time to time.
 */
data class CzechBank(
    val code: String,
    val name: String,
    /** Where in internet banking the owner switches on e-mail notifications of incoming payments. */
    val emailHint: String,
    /** Where the statement file (GPC/ABO or CAMT.053) can be downloaded or sent by e-mail. */
    val statementHint: String,
    /** True when Flos can read payments straight from the bank's API with a token. */
    val directApi: Boolean = false,
)

object CzechBanks {
    private const val GENERIC_EMAIL = "Internetové bankovnictví → Nastavení → Notifikace / Upozornění: zapněte e-mail při každé příchozí platbě."
    private const val GENERIC_STATEMENT = "Internetové bankovnictví → Výpisy: zvolte formát GPC/ABO nebo XML (CAMT.053) a zasílání e-mailem."

    val all: List<CzechBank> = listOf(
        CzechBank("0100", "Komerční banka", "MojeBanka → Nastavení → Notifikace: SMS/e-mail o připsání platby.", "MojeBanka → Výpisy → formát GPC (ABO) nebo CAMT.053."),
        CzechBank("0300", "ČSOB", "ČSOB Internetbanking → Nastavení → Notifikace: e-mail o příchozí platbě.", "ČSOB CEB / Internetbanking → Výpisy → formát GPC nebo XML CAMT.053."),
        CzechBank("0600", "MONETA Money Bank", "Smart Banka / Internet Banka → Nastavení → Notifikace.", "Internet Banka → Výpisy → formát GPC nebo CAMT.053."),
        CzechBank("0710", "Česká národní banka", GENERIC_EMAIL, GENERIC_STATEMENT),
        CzechBank("0800", "Česká spořitelna", "George → Nastavení → Notifikace: e-mail při připsání platby.", "George → Výpisy → formát GPC (ABO) nebo CAMT.053 (George Business)."),
        CzechBank("2010", "Fio banka", "Internetbanking → Nastavení → Zasílání informací: e-mail o pohybu na účtu.", "Internetbanking → Pohyby → export GPC nebo CAMT.053.", directApi = true),
        CzechBank("2060", "Citfin", GENERIC_EMAIL, GENERIC_STATEMENT),
        CzechBank("2250", "Banka CREDITAS", GENERIC_EMAIL, GENERIC_STATEMENT),
        CzechBank("2700", "UniCredit Bank", "Smart Banking → Nastavení → Notifikace.", "Business Online → Výpisy → formát GPC nebo CAMT.053."),
        CzechBank("3030", "Air Bank", "Internetové bankovnictví → Nastavení → Upozornění: e-mail o příchozí platbě.", "Internetové bankovnictví → Výpisy → export GPC nebo CAMT.053."),
        CzechBank("3050", "BNP Paribas Personal Finance", GENERIC_EMAIL, GENERIC_STATEMENT),
        CzechBank("3060", "PKO BP", GENERIC_EMAIL, GENERIC_STATEMENT),
        CzechBank("3500", "ING Bank", GENERIC_EMAIL, GENERIC_STATEMENT),
        CzechBank("4000", "Max banka", GENERIC_EMAIL, GENERIC_STATEMENT),
        CzechBank("5500", "Raiffeisenbank", "Internetové bankovnictví → Nastavení → Notifikace: e-mail o kreditní transakci.", "Internetové bankovnictví → Výpisy → formát GPC (ABO) nebo CAMT.053."),
        CzechBank("5800", "J&T Banka", GENERIC_EMAIL, GENERIC_STATEMENT),
        CzechBank("6000", "PPF banka", GENERIC_EMAIL, GENERIC_STATEMENT),
        CzechBank("6100", "Raiffeisenbank (Equa)", GENERIC_EMAIL, GENERIC_STATEMENT),
        CzechBank("6210", "mBank", "mBank → Nastavení → Notifikace: e-mail o příchozí platbě.", "mBank → Výpisy → formát GPC nebo CAMT.053."),
        CzechBank("6363", "Partners Banka", GENERIC_EMAIL, GENERIC_STATEMENT),
        CzechBank("6700", "Všeobecná úverová banka", GENERIC_EMAIL, GENERIC_STATEMENT),
        CzechBank("6800", "Sberbank (v likvidaci)", GENERIC_EMAIL, GENERIC_STATEMENT),
        CzechBank("7910", "Deutsche Bank", GENERIC_EMAIL, GENERIC_STATEMENT),
        CzechBank("7950", "Raiffeisen stavební spořitelna", GENERIC_EMAIL, GENERIC_STATEMENT),
        CzechBank("8030", "Volksbank Raiffeisenbank Nordoberpfalz", GENERIC_EMAIL, GENERIC_STATEMENT),
        CzechBank("8040", "Oberbank", GENERIC_EMAIL, GENERIC_STATEMENT),
        CzechBank("8250", "Bank of China", GENERIC_EMAIL, GENERIC_STATEMENT),
        CzechBank("8255", "Bank of Communications", GENERIC_EMAIL, GENERIC_STATEMENT),
        CzechBank("8265", "Industrial and Commercial Bank of China", GENERIC_EMAIL, GENERIC_STATEMENT),
    )

    private val byCode = all.associateBy { it.code }

    /** The bank of a domestic account ("123456789/0300") or a Czech IBAN ("CZ65 0800 0000 …"). */
    fun forAccount(account: String): CzechBank? {
        val text = account.replace(" ", "").uppercase()
        val code = when {
            text.startsWith("CZ") && text.length >= 8 -> text.substring(4, 8)
            text.contains('/') -> text.substringAfterLast('/').take(4)
            else -> return null
        }
        return byCode[code] ?: if (code.length == 4 && code.all(Char::isDigit)) CzechBank(code, "Banka $code", GENERIC_EMAIL, GENERIC_STATEMENT) else null
    }
}
