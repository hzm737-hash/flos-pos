package cz.flos.core

/** Czech VAT rates valid from 1 January 2024. Prices in Flos are entered including VAT. */
enum class VatRate(val percent: Int, val label: String) {
    STANDARD(21, "21 %"),
    REDUCED(12, "12 %"),
    ZERO(0, "0 %");

    companion object {
        fun fromPercent(p: Int): VatRate = entries.firstOrNull { it.percent == p } ?: STANDARD
    }
}

data class VatLine(val rate: VatRate, val gross: Long, val base: Long, val vat: Long)

object Vat {
    /** Splits a VAT-inclusive amount into base and VAT, rounded to haléře. */
    fun split(gross: Long, rate: VatRate): VatLine {
        val base = Math.round(gross * 100.0 / (100 + rate.percent))
        return VatLine(rate, gross, base, gross - base)
    }
}
