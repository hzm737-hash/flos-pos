package cz.flos.core.eet

import cz.flos.core.Money
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * EET 2.0 (evidence tržeb from 2027), interface v4.1, as described in the Financial
 * Administration's "EET_popis_rozhrani_v1.2" and EETXMLSchema.xsd (eet.gov.cz, pro vývojáře).
 */
enum class EetEnvironment(val url: String, val label: String) {
    PLAYGROUND("https://pg.trzbyeet.gov.cz/eet/services/EETServiceSOAP/v4", "Playground (test)"),
    PRODUCTION("https://trzbyeet.gov.cz/eet/services/EETServiceSOAP/v4", "Produkce"),
}

/** One recorded sale (the Data element). Refunds and storno use a negative [amount]. */
data class EetSale(
    /** EIČ of the taxpayer, e.g. "CZ12345678". Must match the certificate. */
    val eicPopl: String,
    /** ID of the evidenční jednotka (premises) assigned in DIS+. */
    val idJednotky: Int,
    /** ID of this till, unique within the evidenční jednotka. */
    val idPokl: String,
    /** Sequential number of the sale (usually the receipt number). */
    val poradCis: String,
    val datTrzby: OffsetDateTime,
    /** Total actually received incl. VAT, in haléře. */
    val amount: Long,
    val urcenoCerpZuct: Long? = null,
    val cerpZuct: Long? = null,
    val eicPoverujiciho: String? = null,
    val povereniVicePopl: Boolean? = null,
) {
    /** Returns a list of problems; empty when the data matches the XSD restrictions. */
    fun validate(): List<String> = buildList {
        if (!EIC.matches(eicPopl)) add("EIČ musí mít tvar CZ a 8–10 číslic")
        eicPoverujiciho?.let { if (!EIC.matches(it)) add("EIČ pověřujícího má chybný tvar") }
        if (idJednotky !in 1..999_999_999) add("Označení evidenční jednotky musí být 1–999999999")
        if (!ID20.matches(idPokl)) add("Označení pokladny: 1–20 znaků (písmena bez diakritiky, číslice, . , : ; / # - _ mezera)")
        if (!ID25.matches(poradCis)) add("Pořadové číslo: 1–25 povolených znaků")
        listOfNotNull(amount, urcenoCerpZuct, cerpZuct).forEach {
            if (kotlin.math.abs(it) >= 10_000_000_000L) add("Částka mimo povolený rozsah")
        }
    }

    /**
     * Problems the tax administration accepts but reports as warnings (interface v1.2, "propustné kontroly").
     * Shown to the user in settings so they can fix the data before going live.
     */
    fun warnings(): List<String> = buildList {
        val unit = idJednotky.toString()
        if (unit.length < 2 || unit.last() !in '1'..'4') add("Označení evidenční jednotky má neobvyklý tvar (nejméně 2 číslice, poslední 1–4). Zkontrolujte ho v DIS+.")
        if (eicPoverujiciho != null && eicPoverujiciho == eicPopl) add("EIČ pověřujícího je shodné s EIČ poplatníka")
    }

    companion object {
        private val EIC = Regex("CZ[0-9]{8,10}")
        private val ID20 = Regex("[0-9a-zA-Z.,:;/#\\-_ ]{1,20}")
        private val ID25 = Regex("[0-9a-zA-Z.,:;/#\\-_ ]{1,25}")
    }
}

/** What the tax administration answered. */
sealed interface EetResult {
    /** Accepted. [pok] is the 39-character confirmation code (POK). */
    data class Confirmed(val pok: String, val test: Boolean, val warnings: List<EetWarning>, val received: String?) : EetResult

    /** Rejected or failed. [retryable] tells whether sending again later can succeed. */
    data class Error(val code: Int?, val message: String, val retryable: Boolean, val warnings: List<EetWarning> = emptyList()) : EetResult

    /** Verification mode (overeni=true) passed: the message is valid but the sale was NOT recorded. */
    data class VerifiedOk(val warnings: List<EetWarning>) : EetResult
}

data class EetWarning(val code: Int, val text: String)

object EetFormat {
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    val PRAGUE: ZoneId = ZoneId.of("Europe/Prague")

    /** ISO 8601 with seconds and offset, e.g. 2027-01-09T04:25:28+01:00. */
    fun dateTime(t: OffsetDateTime): String = t.truncatedTo(ChronoUnit.SECONDS).format(DATE)

    /** Amount with exactly two decimals and a dot: "0.20", "-100.00". */
    fun amount(haler: Long): String = Money.toDecimal(haler)

    /** Error codes from the interface description, chapter on Chyba. */
    fun errorText(code: Int): String = when (code) {
        -1 -> "Dočasná technická chyba zpracování – odešlete prosím datovou zprávu později"
        0 -> "Datovou zprávu evidované tržby v ověřovacím módu se podařilo zpracovat"
        2 -> "Kódování XML není platné"
        3 -> "Datová zpráva evidované tržby nevyhověla kontrole XML schématu"
        4 -> "Neplatný podpis SOAP zprávy"
        6 -> "Neplatná struktura EIČ"
        7 -> "Datová zpráva je příliš velká"
        8 -> "Datová zpráva nebyla zpracována kvůli technické chybě nebo chybě dat"
        else -> "Chyba $code"
    }

    fun warningText(code: Int): String = when (code) {
        1 -> "EIČ poplatníka se neshoduje s EIČ v certifikátu"
        2 -> "Chybný formát EIČ pověřujícího poplatníka"
        4 -> "Datum a čas tržby je novější než datum a čas přijetí zprávy"
        5 -> "Datum a čas tržby je výrazně v minulosti"
        6 -> "Chybný formát označení evidenční jednotky"
        7 -> "EIČ pověřujícího je shodné s EIČ poplatníka"
        else -> "Varování $code"
    }

    /** Legal deadline for sending a sale after a connection failure (§ 20 ZoET, EET 2.0 seminar 23.6.2026). */
    const val OFFLINE_DEADLINE_HOURS = 48L
}
