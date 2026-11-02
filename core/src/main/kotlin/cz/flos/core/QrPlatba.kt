package cz.flos.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.text.Normalizer

/**
 * Builds a "QR Platba" payment string (Short Payment Descriptor, SPAYD 1.0) that Czech
 * banking apps scan to pre-fill a transfer.
 *
 * Example: SPD*1.0*ACC:CZ6508000000192000145399*AM:480.50*CC:CZK*X-VS:20250001*MSG:UCTENKA 2025-0001
 */
data class QrPlatba(
    val iban: String,
    val bic: String? = null,
    val amount: Long? = null,
    val currency: String = "CZK",
    val variableSymbol: String? = null,
    val message: String? = null,
    val recipientName: String? = null,
) {
    init {
        require(CzechBankAccount.isValidIban(iban)) { "Invalid IBAN: $iban" }
        variableSymbol?.let { require(it.length in 1..10 && it.all(Char::isDigit)) { "Variable symbol must be 1–10 digits" } }
        amount?.let { require(it in 1..9_999_999_99L) { "Amount out of range" } }
    }

    fun toSpayd(): String = buildList {
        add("SPD")
        add("1.0")
        val acc = CzechBankAccount.normalizeIban(iban)
        add("ACC:" + if (bic.isNullOrBlank()) acc else "$acc+${bic.trim().uppercase()}")
        amount?.let { add("AM:" + Money.toDecimal(it)) }
        add("CC:" + currency.uppercase())
        variableSymbol?.let { add("X-VS:$it") }
        recipientName?.let { clean(it, 35) }?.takeIf { it.isNotEmpty() }?.let { add("RN:$it") }
        message?.let { clean(it, 60) }?.takeIf { it.isNotEmpty() }?.let { add("MSG:$it") }
    }.joinToString("*")

    /** QR code matrix with one module per cell; scale it when drawing or printing. */
    fun toMatrix(): BitMatrix = qrMatrix(toSpayd())

    companion object {
        /** Removes diacritics and characters that are not allowed in SPAYD values, upper-cases, and truncates. */
        internal fun clean(value: String, max: Int): String =
            Text.stripDiacritics(value).uppercase()
                .replace("*", " ")
                .filter { it.code in 32..126 }
                .trim()
                .take(max)

        fun qrMatrix(content: String): BitMatrix = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 0,
            ),
        )
    }
}

object Text {
    fun stripDiacritics(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}
