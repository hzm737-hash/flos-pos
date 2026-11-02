package cz.flos.core

import java.math.BigInteger

/**
 * A Czech domestic bank account, written as "prefix-number/bank", e.g. "19-2000145399/0800".
 * Converts to and from IBAN and checks the official modulo-11 checksum.
 */
data class CzechBankAccount(val prefix: String, val number: String, val bankCode: String) {

    val iban: String
        get() {
            val bban = bankCode + prefix.padStart(6, '0') + number.padStart(10, '0')
            return "CZ" + ibanCheckDigits("CZ", bban) + bban
        }

    override fun toString(): String =
        (if (prefix.trimStart('0').isNotEmpty()) "${prefix.trimStart('0')}-" else "") + number.trimStart('0') + "/" + bankCode

    companion object {
        private val ACCOUNT = Regex("""^(?:(\d{1,6})-)?(\d{2,10})/(\d{4})$""")
        private val PREFIX_WEIGHTS = intArrayOf(10, 5, 8, 4, 2, 1)
        private val NUMBER_WEIGHTS = intArrayOf(6, 3, 7, 9, 10, 5, 8, 4, 2, 1)

        /** Parses "prefix-number/bank". Returns null when the format or checksum is wrong. */
        fun parse(text: String): CzechBankAccount? {
            val m = ACCOUNT.matchEntire(text.replace(" ", "").trim()) ?: return null
            val prefix = m.groupValues[1]
            val number = m.groupValues[2]
            val bank = m.groupValues[3]
            if (!mod11(prefix.padStart(6, '0'), PREFIX_WEIGHTS)) return null
            if (!mod11(number.padStart(10, '0'), NUMBER_WEIGHTS)) return null
            if (number.trimStart('0').length < 2) return null
            return CzechBankAccount(prefix.padStart(6, '0'), number.padStart(10, '0'), bank)
        }

        /** Reads a Czech IBAN back into a domestic account number. */
        fun fromIban(iban: String): CzechBankAccount? {
            val clean = normalizeIban(iban)
            if (!clean.startsWith("CZ") || clean.length != 24 || !isValidIban(clean)) return null
            return CzechBankAccount(clean.substring(8, 14), clean.substring(14, 24), clean.substring(4, 8))
        }

        private fun mod11(digits: String, weights: IntArray): Boolean =
            digits.mapIndexed { i, c -> (c - '0') * weights[i] }.sum() % 11 == 0

        fun normalizeIban(iban: String): String = iban.replace(" ", "").uppercase()

        fun isValidIban(iban: String): Boolean {
            val s = normalizeIban(iban)
            if (s.length < 15 || s.length > 34 || !s.all { it.isLetterOrDigit() }) return false
            val rearranged = s.substring(4) + s.substring(0, 4)
            return toNumeric(rearranged).mod(BigInteger.valueOf(97)) == BigInteger.ONE
        }

        private fun ibanCheckDigits(country: String, bban: String): String {
            val r = toNumeric(bban + country + "00").mod(BigInteger.valueOf(97)).toInt()
            return (98 - r).toString().padStart(2, '0')
        }

        private fun toNumeric(s: String): BigInteger =
            BigInteger(s.map { if (it.isDigit()) it.toString() else (it - 'A' + 10).toString() }.joinToString(""))

        /** Accepts either an IBAN or a domestic account number and returns an IBAN, or null. */
        fun toIban(input: String): String? {
            val clean = normalizeIban(input)
            if (clean.length >= 15 && clean[0].isLetter() && clean[1].isLetter()) return if (isValidIban(clean)) clean else null
            return parse(input)?.iban
        }

        /** Formats an IBAN in groups of four for display. */
        fun formatIban(iban: String): String = normalizeIban(iban).chunked(4).joinToString(" ")
    }
}
