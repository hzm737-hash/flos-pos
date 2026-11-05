package cz.flos.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.Code128Writer
import com.google.zxing.oned.EAN13Writer
import com.google.zxing.oned.EAN8Writer

/** A shelf or product price label. [unitNote] is e.g. "cena za 1 kg". */
data class PriceLabel(val name: String, val price: Long, val barcode: String? = null, val unitNote: String = "", val shop: String = "")

/** Price labels printed on the receipt printer: name, big price, and the barcode. */
object Labels {
    /** EAN-13 / EAN-8 when the code is a valid EAN, otherwise CODE 128. */
    fun format(code: String): BarcodeFormat = when {
        code.length == 13 && code.all(Char::isDigit) && ScaleBarcode.checkDigit(code.take(12)) == code[12] - '0' -> BarcodeFormat.EAN_13
        code.length == 8 && code.all(Char::isDigit) && ean8Check(code.take(7)) == code[7] - '0' -> BarcodeFormat.EAN_8
        else -> BarcodeFormat.CODE_128
    }

    private fun ean8Check(first7: String): Int {
        val sum = first7.mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 3 else 1 }.sum()
        return (10 - sum % 10) % 10
    }

    /** The bars of [code], one module per column (height 1). */
    fun bars(code: String): BitMatrix = when (format(code)) {
        BarcodeFormat.EAN_13 -> EAN13Writer().encode(code, BarcodeFormat.EAN_13, 0, 1)
        BarcodeFormat.EAN_8 -> EAN8Writer().encode(code, BarcodeFormat.EAN_8, 0, 1)
        else -> Code128Writer().encode(Text.stripDiacritics(code), BarcodeFormat.CODE_128, 0, 1)
    }

    fun layout(label: PriceLabel, copies: Int = 1): List<ReceiptBlock> = buildList {
        repeat(copies.coerceIn(1, 100)) {
            if (label.shop.isNotBlank()) add(ReceiptBlock.Line(label.shop, Align.CENTER))
            add(ReceiptBlock.Line(label.name, Align.CENTER, bold = true))
            add(ReceiptBlock.Line(Money.format(label.price), Align.CENTER, bold = true, large = true))
            if (label.unitNote.isNotBlank()) add(ReceiptBlock.Line(label.unitNote, Align.CENTER))
            label.barcode?.takeIf { it.isNotBlank() }?.let { add(ReceiptBlock.Barcode(it)) }
            add(ReceiptBlock.Feed(3))
        }
    }
}
