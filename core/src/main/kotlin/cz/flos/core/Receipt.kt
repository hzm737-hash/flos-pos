package cz.flos.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * How a sale was paid.
 *
 * [eet] tells whether the payment is recorded in EET 2.0. The law covers payments made in
 * person (cash, card, a transfer or QR payment ordered at the counter, vouchers used like
 * cash). Sales paid on an invoice are not recorded, even when the invoice is paid at the counter.
 */
enum class PaymentMethod(val label: String, val eet: Boolean = true) {
    CASH("Hotově"),
    CARD("Kartou"),
    QR_TRANSFER("QR platba převodem"),
    BANK_TRANSFER("Bankovní převod"),
    MEAL_VOUCHER("Stravenky"),
    INVOICE("Na fakturu", eet = false),
}

/** One method of a mixed payment and the amount paid by it (haléře). */
data class PaymentPart(val method: PaymentMethod, val amount: Long) {
    companion object {
        /** "CASH:12000,MEAL_VOUCHER:15000" as stored with the sale. */
        fun format(parts: List<PaymentPart>): String = parts.joinToString(",") { "${it.method.name}:${it.amount}" }

        fun parse(text: String?): List<PaymentPart> = text.orEmpty().split(',').mapNotNull { t ->
            val m = PaymentMethod.entries.firstOrNull { it.name == t.substringBefore(':').trim() } ?: return@mapNotNull null
            t.substringAfter(':', "").trim().toLongOrNull()?.let { PaymentPart(m, it) }
        }

        /** The method shown as the sale's payment type: the one with the biggest share. */
        fun main(parts: List<PaymentPart>): PaymentMethod = parts.maxByOrNull { it.amount }?.method ?: PaymentMethod.CASH
    }
}

data class BusinessInfo(
    val name: String,
    val addressLines: List<String> = emptyList(),
    val ico: String = "",
    val dic: String = "",
    val vatPayer: Boolean = false,
    val headerNote: String = "",
    val footer: String = "Děkujeme za nákup!",
    /** Phone, e-mail and web, printed under the address when set. */
    val contacts: List<String> = emptyList(),
    /** Entry in the commercial or trade register ("Zapsáno v OR vedeném KS v Praze, oddíl C, vložka 123"). */
    val registry: String = "",
    val style: ReceiptStyle = ReceiptStyle(),
)

/** How receipts and documents look. The defaults print exactly the classic Flos receipt. */
data class ReceiptStyle(
    val showCashier: Boolean = true,
    /** VAT letter next to each item (for VAT payers). */
    val showItemVat: Boolean = true,
    /** The total in double size. */
    val bigTotal: Boolean = false,
    /** Second footer line, e.g. "Sledujte nás na Instagramu @bistro". */
    val extraFooter: String = "",
    /** Printed on quotations and pro forma invoices, e.g. payment terms. */
    val documentTerms: String = "",
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
    /** EET 2.0 details; null when the business does not record sales in EET. */
    val eet: EetReceiptInfo? = null,
    /** Number of the original receipt when this document cancels it (storno). */
    val stornoOf: String? = null,
    /** Number of the original receipt when this document returns some of its items (negative quantities). */
    val refundOf: String? = null,
    /** A receipt, or a non-fiscal check print / order ticket printed before payment. */
    val kind: DocumentKind = DocumentKind.RECEIPT,
    /** Order note: delivery address, customer, table. */
    val note: String = "",
    /** For a payer from abroad: a SEPA (EPC) QR code with the amount in euro. */
    val epc: EpcQr? = null,
    /** CZK per 1 EUR used for [epc]. */
    val eurRate: Double? = null,
    /** Mixed payment (e.g. meal vouchers and the rest in cash): the amount of each method; empty = all by [payment]. */
    val parts: List<PaymentPart> = emptyList(),
    /** Quote valid until, pro forma due date, or the date of a customer order. */
    val validUntil: java.time.LocalDate? = null,
) {
    val total: Long get() = cart.total
    /** Amount actually due in cash, rounded to whole crowns. */
    val cashDue: Long get() = if (payment == PaymentMethod.CASH && parts.size <= 1) Money.roundToCrowns(total) else total
    val rounding: Long get() = cashDue - total
    val change: Long? get() = cashGiven?.let { it - cashDue }
}

enum class DocumentKind {
    /** The sales receipt (zjednodušený daňový doklad for VAT payers). */
    RECEIPT,
    /** Check print before payment, so the seller and customer can review the items. Not a tax document. */
    PRE_BILL,
    /** Ticket for an open order (delivery, takeaway) that has not been paid yet. Not a tax document. */
    ORDER,
    /** Price quotation (cenová nabídka) for a customer. Not a tax document. */
    QUOTE,
    /** Pro forma invoice asking for payment in advance. Not a tax document. */
    PROFORMA,
    /** Customer order for a later date (dort, catering); paid when it is picked up. Not a tax document. */
    SALES_ORDER,
}

data class EetReceiptInfo(
    val idJednotky: Int,
    val idPokl: String,
    /** Confirmation code from the tax administration; null while the sale waits to be sent. */
    val pok: String?,
    val test: Boolean = false,
)

enum class Align { LEFT, CENTER, RIGHT }

sealed interface ReceiptBlock {
    /**
     * [large] prints double width and height (half as many characters per line); [tall] prints double
     * height only, so a line keeps its full width but is easier to read (customer details on web orders).
     */
    data class Line(
        val text: String, val align: Align = Align.LEFT, val bold: Boolean = false, val large: Boolean = false,
        val tall: Boolean = false,
    ) : ReceiptBlock
    data object Separator : ReceiptBlock
    data class Qr(val content: String, val caption: String? = null) : ReceiptBlock
    data class Feed(val lines: Int) : ReceiptBlock
    /** A one-dimensional barcode (EAN or CODE 128) with its digits underneath, e.g. on price labels. */
    data class Barcode(val code: String) : ReceiptBlock
}

/** Lays out a receipt as printer-independent blocks for a paper width in characters (32 for 58 mm, 48 for 80 mm). */
class ReceiptLayout(private val width: Int) {

    private val dateFmt = DateTimeFormatter.ofPattern("d.M.yyyy HH:mm")
    private val dayFmt = DateTimeFormatter.ofPattern("d.M.yyyy")

    fun layout(business: BusinessInfo, r: Receipt): List<ReceiptBlock> = buildList {
        add(ReceiptBlock.Line(business.name, Align.CENTER, bold = true, large = true))
        business.addressLines.filter { it.isNotBlank() }.forEach { add(ReceiptBlock.Line(it, Align.CENTER)) }
        if (business.ico.isNotBlank()) add(ReceiptBlock.Line("IČO: ${business.ico}", Align.CENTER))
        if (business.dic.isNotBlank()) add(ReceiptBlock.Line("DIČ: ${business.dic}", Align.CENTER))
        business.contacts.filter { it.isNotBlank() }.forEach { add(ReceiptBlock.Line(it, Align.CENTER)) }
        if (business.headerNote.isNotBlank()) add(ReceiptBlock.Line(business.headerNote, Align.CENTER))
        add(ReceiptBlock.Separator)

        val fiscal = r.kind == DocumentKind.RECEIPT
        val title = when {
            r.kind == DocumentKind.PRE_BILL -> "PŘEDBĚŽNÝ ÚČET"
            r.kind == DocumentKind.ORDER -> "OBJEDNÁVKA č. ${r.number}"
            r.kind == DocumentKind.QUOTE -> "CENOVÁ NABÍDKA č. ${r.number}"
            r.kind == DocumentKind.PROFORMA -> "ZÁLOHOVÁ FAKTURA č. ${r.number}"
            r.kind == DocumentKind.SALES_ORDER -> "OBJEDNÁVKA ZÁKAZNÍKA č. ${r.number}"
            r.stornoOf != null -> "STORNO účtenky ${r.stornoOf}"
            r.refundOf != null -> "VRATKA k účtence ${r.refundOf}"
            business.vatPayer -> "Zjednodušený daňový doklad"
            else -> "Účtenka"
        }
        add(ReceiptBlock.Line(title + if (r.copy) " – KOPIE" else "", Align.CENTER, bold = true))
        if (!fiscal) add(ReceiptBlock.Line("Není daňový doklad", Align.CENTER))
        if (fiscal) add(ReceiptBlock.Line(twoCol("Číslo: ${r.number}", r.createdAt.format(dateFmt))))
        else add(ReceiptBlock.Line(r.createdAt.format(dateFmt), Align.CENTER))
        if (r.cashier.isNotBlank() && business.style.showCashier) add(ReceiptBlock.Line("Obsluha: ${r.cashier}"))
        if (r.note.isNotBlank()) addAll(noteLines(r.note, bold = !fiscal))
        add(ReceiptBlock.Separator)

        for (item in r.cart.items) {
            wrap(item.name, width).forEach { add(ReceiptBlock.Line(it)) }
            val qtyText = "  ${Qty.format(item.quantity)} x ${Money.format(item.unitPrice, "")}" +
                if (business.vatPayer && business.style.showItemVat) "  ${item.vatRate.label}" else ""
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
        val sign = if (r.stornoOf != null) -1 else 1
        if (business.style.bigTotal) add(ReceiptBlock.Line("CELKEM " + Money.format(sign * r.total), Align.CENTER, bold = true, large = true))
        else add(ReceiptBlock.Line(twoCol("CELKEM", Money.format(sign * r.total)), bold = true))
        if (r.rounding != 0L) {
            add(ReceiptBlock.Line(twoCol("Zaokrouhlení", Money.format(r.rounding))))
            add(ReceiptBlock.Line(twoCol("K úhradě", Money.format(r.cashDue)), bold = true))
        }
        if (!fiscal) {
            add(ReceiptBlock.Separator)
            when (r.kind) {
                DocumentKind.QUOTE -> add(ReceiptBlock.Line("Ceny platí do: ${r.validUntil?.format(dayFmt) ?: "dle dohody"}", Align.CENTER, bold = true))
                DocumentKind.PROFORMA -> {
                    add(ReceiptBlock.Line("K úhradě předem", Align.CENTER, bold = true))
                    r.validUntil?.let { add(ReceiptBlock.Line("Splatnost: ${it.format(dayFmt)}", Align.CENTER)) }
                    r.qr?.let { qr ->
                        add(ReceiptBlock.Line("QR Platba", Align.CENTER, bold = true))
                        add(ReceiptBlock.Qr(qr.toSpayd()))
                        add(ReceiptBlock.Line(CzechBankAccount.fromIban(qr.iban)?.toString() ?: CzechBankAccount.formatIban(qr.iban), Align.CENTER))
                        qr.variableSymbol?.let { add(ReceiptBlock.Line("VS: $it", Align.CENTER)) }
                    }
                }
                DocumentKind.SALES_ORDER -> r.validUntil?.let { add(ReceiptBlock.Line("Termín: ${it.format(dayFmt)}", Align.CENTER, bold = true)) }
                else -> {
                    add(ReceiptBlock.Line(if (r.kind == DocumentKind.ORDER) "NEZAPLACENO" else "Účet zatím není zaplacen.", Align.CENTER, bold = true))
                    // An order paid on delivery: the customer can pay with the QR payment from the ticket.
                    if (r.kind == DocumentKind.ORDER) r.qr?.let { qr ->
                        qr.amount?.let { add(ReceiptBlock.Line(twoCol("K úhradě", Money.format(it)), bold = true)) }
                        add(ReceiptBlock.Line("QR Platba", Align.CENTER, bold = true))
                        add(ReceiptBlock.Qr(qr.toSpayd()))
                        add(ReceiptBlock.Line(CzechBankAccount.fromIban(qr.iban)?.toString() ?: CzechBankAccount.formatIban(qr.iban), Align.CENTER))
                        qr.variableSymbol?.let { add(ReceiptBlock.Line("VS: $it", Align.CENTER)) }
                    }
                }
            }
            if (r.kind != DocumentKind.QUOTE) add(ReceiptBlock.Line("Účtenka bude vystavena po zaplacení.", Align.CENTER))
            if ((r.kind == DocumentKind.QUOTE || r.kind == DocumentKind.PROFORMA || r.kind == DocumentKind.SALES_ORDER) && business.style.documentTerms.isNotBlank()) {
                wrap(business.style.documentTerms, width).forEach { add(ReceiptBlock.Line(it, Align.CENTER)) }
            }
            if (r.kind == DocumentKind.PROFORMA && business.registry.isNotBlank()) wrap(business.registry, width).forEach { add(ReceiptBlock.Line(it, Align.CENTER)) }
            add(ReceiptBlock.Feed(3))
            return@buildList
        }
        if (r.parts.size > 1) {
            add(ReceiptBlock.Line("Platba kombinovaná:"))
            r.parts.forEach { add(ReceiptBlock.Line(twoCol("  " + it.method.label, Money.format(it.amount)))) }
            val cashPart = r.parts.filter { it.method == PaymentMethod.CASH }.sumOf { it.amount }
            if (cashPart > 0 && r.cashGiven != null) {
                add(ReceiptBlock.Line(twoCol("Přijato hotově", Money.format(r.cashGiven))))
                add(ReceiptBlock.Line(twoCol("Vráceno", Money.format((r.cashGiven - cashPart).coerceAtLeast(0)))))
            }
        } else add(ReceiptBlock.Line(twoCol("Platba", r.payment.label)))
        if (r.parts.size <= 1 && r.payment == PaymentMethod.CASH && r.cashGiven != null) {
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

        r.eet?.let { e ->
            add(ReceiptBlock.Separator)
            add(ReceiptBlock.Line(twoCol("Evidenční jednotka", e.idJednotky.toString())))
            add(ReceiptBlock.Line(twoCol("Pokladna", e.idPokl)))
            if (e.pok != null) {
                add(ReceiptBlock.Line("POK:" + if (e.test) " (TEST)" else ""))
                add(ReceiptBlock.Line(e.pok))
            } else {
                add(ReceiptBlock.Line("Tržba bude do EET odeslána dodatečně."))
            }
        }

        if (r.stornoOf == null) r.qr?.let { qr ->
            add(ReceiptBlock.Separator)
            add(ReceiptBlock.Line("QR Platba", Align.CENTER, bold = true))
            add(ReceiptBlock.Qr(qr.toSpayd()))
            add(ReceiptBlock.Line(CzechBankAccount.fromIban(qr.iban)?.toString() ?: CzechBankAccount.formatIban(qr.iban), Align.CENTER))
            qr.variableSymbol?.let { add(ReceiptBlock.Line("VS: $it", Align.CENTER)) }
        }

        if (r.stornoOf == null) r.epc?.let { epc ->
            add(ReceiptBlock.Separator)
            add(ReceiptBlock.Line("Platba ze zahraničí (SEPA)", Align.CENTER, bold = true))
            add(ReceiptBlock.Line("K úhradě: " + Money.format(epc.amount, "EUR"), Align.CENTER, bold = true))
            r.eurRate?.let { add(ReceiptBlock.Line("Kurz: 1 EUR = " + "%.3f".format(java.util.Locale.ROOT, it).replace('.', ',') + " Kč", Align.CENTER)) }
            add(ReceiptBlock.Qr(epc.toPayload()))
            add(ReceiptBlock.Line("IBAN: " + CzechBankAccount.formatIban(epc.iban), Align.CENTER))
            epc.bic?.let { add(ReceiptBlock.Line("BIC: $it", Align.CENTER)) }
            wrap("Zpráva: " + epc.text, width).forEach { add(ReceiptBlock.Line(it, Align.CENTER)) }
        }

        if (business.footer.isNotBlank()) {
            add(ReceiptBlock.Separator)
            wrap(business.footer, width).forEach { add(ReceiptBlock.Line(it, Align.CENTER)) }
        }
        if (business.style.extraFooter.isNotBlank()) wrap(business.style.extraFooter, width).forEach { add(ReceiptBlock.Line(it, Align.CENTER)) }
        if (business.registry.isNotBlank()) wrap(business.registry, width).forEach { add(ReceiptBlock.Line(it, Align.CENTER)) }
        add(ReceiptBlock.Feed(3))
    }

    /** Left text and right text on one line, padded to the paper width. */
    /**
     * The order note. On a delivery / pick-up web order ([WebOrders.ticketNote]) the customer's name,
     * address and wished time print double height and the phone number double size, so they are easy
     * to read; everything else stays normal size.
     */
    internal fun noteLines(note: String, bold: Boolean): List<ReceiptBlock.Line> {
        if (!note.startsWith("WEB ") || '\n' !in note) return wrap(note, width).map { ReceiptBlock.Line(it, bold = bold) }
        return note.split('\n').map { it.trimEnd('\r') }.filter { it.isNotBlank() }.flatMap { l ->
            val number = l.removePrefix(PHONE).trim()
            when {
                l.startsWith(PHONE) && number.length <= width / 2 ->
                    listOf(ReceiptBlock.Line(PHONE.trim(), bold = bold), ReceiptBlock.Line(number, bold = true, large = true))
                l.startsWith(PHONE) || BIG_NOTE_LINES.any { l.startsWith(it) } ->
                    wrap(l, width).map { ReceiptBlock.Line(it, bold = true, tall = true) }
                else -> wrap(l, width).map { ReceiptBlock.Line(it, bold = bold) }
            }
        }
    }

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
        /** Splits [text] into lines of at most [width] characters; a line break in the text starts a new line. */
        /** Starts of the web order lines printed bigger: name, address and wished time tall, the phone number large. */
        private val BIG_NOTE_LINES = listOf("Zákazník: ", "Adresa: ", "Přeje si: ")
        private const val PHONE = "Tel.: "

        fun wrap(text: String, width: Int): List<String> =
            if ('\n' in text) text.split('\n').map { it.trimEnd('\r') }.filter { it.isNotBlank() }.flatMap { wrapLine(it, width) }
            else wrapLine(text, width)

        private fun wrapLine(text: String, width: Int): List<String> {
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
                is ReceiptBlock.Barcode -> appendLine("[|||] ${b.code}")
            }
        }
    }
}
