package cz.flos.core

import com.google.zxing.common.BitMatrix

/**
 * European Payments Council QR code ("EPC QR", also called GiroCode or SEPA QR), guideline
 * EPC069-12. Banking apps across the SEPA area scan it to pre-fill a SEPA credit transfer.
 * It carries amounts in euro only.
 *
 * BCD / 002 / 1 (UTF-8) / SCT / BIC / name / IBAN / EUR12.34 / purpose / reference / text
 */
data class EpcQr(
    val name: String,
    val iban: String,
    /** Euro cents. */
    val amount: Long,
    /** Free text for the payee (max 140), e.g. "/VS/2026000012 Uctenka 2026-000012". */
    val text: String,
    /** Optional inside the EEA from version 002 on. */
    val bic: String? = null,
) {
    init {
        require(CzechBankAccount.isValidIban(iban)) { "Invalid IBAN: $iban" }
        require(amount in 1..99_999_999_999L) { "Amount out of range" }
    }

    fun toPayload(): String = listOf(
        "BCD",
        "002",
        "1",
        "SCT",
        bic?.trim()?.uppercase().orEmpty(),
        clean(name, 70),
        CzechBankAccount.normalizeIban(iban),
        "EUR" + Money.toDecimal(amount),
        "",
        "",
        clean(text, 140),
    ).joinToString("\n")

    fun toMatrix(): BitMatrix = QrPlatba.qrMatrix(toPayload())

    companion object {
        /** Keeps letters (with diacritics, the code is UTF-8) but drops line breaks that would shift the fields. */
        private fun clean(s: String, max: Int) = s.replace(Regex("[\\r\\n]+"), " ").trim().take(max)

        /** Euro amount for a crown amount at [czkPerEur], rounded up to whole cents so the payee is not short. */
        fun eurFor(czkHaler: Long, czkPerEur: Double): Long {
            require(czkPerEur > 0)
            return Math.ceil(czkHaler / czkPerEur - 1e-9).toLong()
        }

        /** BIC of the main Czech banks by bank code, so the owner does not have to look it up. */
        val CZECH_BIC = mapOf(
            "0100" to "KOMBCZPP", "0300" to "CEKOCZPA", "0600" to "AGBACZPP", "0710" to "CNBACZPP", "0800" to "GIBACZPX",
            "2010" to "FIOBCZPP", "2700" to "BACXCZPP", "3030" to "AIRACZPP", "5500" to "RZBCCZPP", "6210" to "BREXCZPP",
            "6100" to "EQBKCZPP", "2250" to "CTASCZ22",
        )

        fun bicForIban(iban: String): String? {
            val n = CzechBankAccount.normalizeIban(iban)
            return if (n.startsWith("CZ") && n.length == 24) CZECH_BIC[n.substring(4, 8)] else null
        }
    }
}
