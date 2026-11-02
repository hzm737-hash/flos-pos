package cz.flos.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class PaymentMethod(val label: String) {
    CASH("Hotově"),
    CARD("Kartou"),
    QR_TRANSFER("QR platba převodem"),
}

data class BusinessInfo(
    val name: String,
    val addressLines: List<String> = emptyList(),
    val ico: String = "",
    val dic: String = "",
    val vatPayer: Boolean = false,
    val headerNote: String = "",
    val footer: String = "Děkujeme za nákup!",
)

data class Receipt(
    val number: String,
    val createdAt: LocalDateTime,
    val cashier: String,
    val cart: Cart,
    val payment: PaymentMethod,
    /** Cash handed over by the customer, for change calculation. */
    val cashGiven: Long? = null,
    /** Present when the customer pays by bank transfer; printed as a QR code. */
    val qr: QrPlatba? = null,
    val copy: Boolean = false,
) {
    val total: Long get() = cart.total
    /** Amount actually due in cash, rounded to whole crowns. */
    val cashDue: Long get() = if (payment == PaymentMethod.CASH) Money.roundToCrowns(total) else total
    val rounding: Long get() = cashDue - total
    val change: Long? get() = cashGiven?.let { it - cashDue }
}

enum class Align { LEFT, CENTER, RIGHT }

sealed interface ReceiptBlock {
    data class Line(val text: String, val align: Align = Align.LEFT, val bold: Boolean = false, val large: Boolean = false) : ReceiptBlock
    data object Separator : ReceiptBlock
    data class Qr(val content: String, val caption: String? = null) : ReceiptBlock
    data class Feed(val lines: Int) : ReceiptBlock
}

/** Lays out a receipt as printer-independent blocks for a paper width in characters (32 for 58 mm, 48 for 80 mm). */
class ReceiptLayout(private val width: Int) {

    private val dateFmt = DateTimeFormatter.ofPattern("d.M.yyyy HH:mm")

    fun layout(business: BusinessInfo, r: Receipt): List<ReceiptBlock> = buildList {
        add(ReceiptBlock.Line(business.name, Align.CENTER, bold = true, large = true))
        business.addressLines.filter { it.isNotBlank() }.forEach { add(ReceiptBlock.Line(it, Align.CENTER)) }
        if (business.ico.isNotBlank()) add(ReceiptBlock.Line("IČO: ${business.ico}", Align.CENTER))
        if (business.dic.isNotBlank()) add(ReceiptBlock.Line("DIČ: ${business.dic}", Align.CENTER))
        if (business.headerNote.isNotBlank()) add(ReceiptBlock.Line(business.headerNote, Align.CENTER))
        add(ReceiptBlock.Separator)

        val title = if (business.vatPayer) "Zjednodušený daňový doklad" else "Účtenka"
        add(ReceiptBlock.Line(title + if (r.copy) " – KOPIE" else "", Align.CENTER, bold = true))
        add(ReceiptBlock.Line(twoCol("Číslo: ${r.number}", r.createdAt.format(dateFmt))))
        if (r.cashier.isNotBlank()) add(ReceiptBlock.Line("Obsluha: ${r.cashier}"))
        add(ReceiptBlock.Separator)

        for (item in r.cart.items) {
            wrap(item.name, width).forEach { add(ReceiptBlock.Line(it)) }
            val qtyText = "  ${item.quantity} x ${Money.format(item.unitPrice, "")}" +
                if (business.vatPayer) "  ${item.vatRate.label}" else ""
            add(ReceiptBlock.Line(twoCol(qtyText, Money.format(item.gross, ""))))
            if (item.discount > 0) {
                add(ReceiptBlock.Line(twoCol("  Sleva ${item.discountPercent} %", Money.format(-item.discount, ""))))
            }
        }
        add(ReceiptBlock.Separator)
        if (r.cart.cartDiscount > 0) {
            add(ReceiptBlock.Line(twoCol("Mezisoučet", Money.format(r.cart.subtotal))))
            add(ReceiptBlock.Line(twoCol("Sleva ${r.cart.discountPercent} %", Money.format(-r.cart.cartDiscount))))
        }
        add(ReceiptBlock.Line(twoCol("CELKEM", Money.format(r.total)), bold = true))
        if (r.rounding != 0L) {
            add(ReceiptBlock.Line(twoCol("Zaokrouhlení", Money.format(r.rounding))))
            add(ReceiptBlock.Line(twoCol("K úhradě", Money.format(r.cashDue)), bold = true))
        }
        add(ReceiptBlock.Line(twoCol("Platba", r.payment.label)))
        if (r.payment == PaymentMethod.CASH && r.cashGiven != null) {
            add(ReceiptBlock.Line(twoCol("Přijato", Money.format(r.cashGiven))))
            add(ReceiptBlock.Line(twoCol("Vráceno", Money.format(r.change ?: 0))))
        }

        if (business.vatPayer) {
            add(ReceiptBlock.Separator)
            add(ReceiptBlock.Line(cols("Sazba", "Základ", "DPH", "Celkem")))
            for (v in r.cart.vatBreakdown()) {
                add(ReceiptBlock.Line(cols(v.rate.label, Money.format(v.base, ""), Money.format(v.vat, ""), Money.format(v.gross, ""))))
            }
        }

        r.qr?.let { qr ->
            add(ReceiptBlock.Separator)
            add(ReceiptBlock.Line("QR Platba", Align.CENTER, bold = true))
            add(ReceiptBlock.Qr(qr.toSpayd()))
            add(ReceiptBlock.Line(CzechBankAccount.fromIban(qr.iban)?.toString() ?: CzechBankAccount.formatIban(qr.iban), Align.CENTER))
            qr.variableSymbol?.let { add(ReceiptBlock.Line("VS: $it", Align.CENTER)) }
        }

        if (business.footer.isNotBlank()) {
            add(ReceiptBlock.Separator)
            wrap(business.footer, width).forEach { add(ReceiptBlock.Line(it, Align.CENTER)) }
        }
        add(ReceiptBlock.Feed(3))
    }

    /** Left text and right text on one line, padded to the paper width. */
    fun twoCol(left: String, right: String): String {
        val space = width - right.length
        val l = if (left.length > space - 1) left.take(maxOf(0, space - 1)) else left
        return l + " ".repeat(maxOf(1, width - l.length - right.length)) + right
    }

    private fun cols(vararg c: String): String {
        val first = width - 3 * (width / 4)
        val rest = width / 4
        return c[0].take(first).padEnd(first) + c.drop(1).joinToString("") { it.take(rest).padStart(rest) }
    }

    companion object {
        fun wrap(text: String, width: Int): List<String> {
            val out = mutableListOf<String>()
            var line = StringBuilder()
            for (word in text.split(' ')) {
                var w = word
                while (w.length > width) {
                    if (line.isNotEmpty()) { out += line.toString(); line = StringBuilder() }
                    out += w.take(width); w = w.drop(width)
                }
                if (line.isEmpty()) line.append(w)
                else if (line.length + 1 + w.length <= width) line.append(' ').append(w)
                else { out += line.toString(); line = StringBuilder(w) }
            }
            if (line.isNotEmpty()) out += line.toString()
            return out
        }

        /** Renders blocks as plain text, used for the on-screen preview and tests. */
        fun toPlainText(blocks: List<ReceiptBlock>, width: Int): String = buildString {
            for (b in blocks) when (b) {
                is ReceiptBlock.Line -> appendLine(
                    when (b.align) {
                        Align.LEFT -> b.text
                        Align.CENTER -> " ".repeat(maxOf(0, (width - b.text.length) / 2)) + b.text
                        Align.RIGHT -> b.text.padStart(width)
                    }
                )
                ReceiptBlock.Separator -> appendLine("-".repeat(width))
                is ReceiptBlock.Qr -> appendLine("[QR] ${b.content}")
                is ReceiptBlock.Feed -> repeat(b.lines) { appendLine() }
            }
        }
    }
}
