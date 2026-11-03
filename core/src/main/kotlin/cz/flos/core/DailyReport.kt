package cz.flos.core

import java.time.LocalDate

/** A finished sale, reduced to what the end-of-day report needs. */
data class SaleSummary(
    val total: Long,
    val payment: PaymentMethod,
    val vat: List<VatLine>,
    val cancelled: Boolean = false,
    /** Voluntary tip, outside the VAT base. */
    val tip: Long = 0,
)

/**
 * Receipts paid by QR code or bank transfer: money that arrived, money still on its way and
 * earlier receipts whose money arrived on the report day. Amounts in haléře.
 */
data class TransferSummary(
    val confirmedCount: Int = 0,
    val confirmed: Long = 0,
    /** Receipts of the report day still waiting for (part of) their money. */
    val waitingCount: Int = 0,
    val waiting: Long = 0,
    /** Receipts of earlier days whose money arrived on the report day. */
    val lateCount: Int = 0,
    val late: Long = 0,
    /** Everything still waiting, from any day. */
    val openCount: Int = 0,
    val open: Long = 0,
    /** Part of [open] older than the due period. */
    val overdueCount: Int = 0,
    val overdue: Long = 0,
) {
    val isNotEmpty: Boolean get() = confirmedCount + waitingCount + lateCount + openCount > 0
}

data class DailyReport(
    val date: LocalDate,
    val receiptCount: Int,
    val cancelledCount: Int,
    val total: Long,
    val byPayment: Map<PaymentMethod, Long>,
    val byVat: List<VatLine>,
    /** Tips received with these sales; not part of [total] or the VAT base. */
    val tips: Long = 0,
    val transfers: TransferSummary? = null,
) {
    fun layout(business: BusinessInfo, width: Int): List<ReceiptBlock> {
        val l = ReceiptLayout(width)
        return buildList {
            add(ReceiptBlock.Line(business.name, Align.CENTER, bold = true))
            add(ReceiptBlock.Line("Denní uzávěrka", Align.CENTER, bold = true, large = true))
            add(ReceiptBlock.Line("${date.dayOfMonth}.${date.monthValue}.${date.year}", Align.CENTER))
            add(ReceiptBlock.Separator)
            add(ReceiptBlock.Line(l.twoCol("Počet účtenek", receiptCount.toString())))
            if (cancelledCount > 0) add(ReceiptBlock.Line(l.twoCol("Stornováno", cancelledCount.toString())))
            PaymentMethod.entries.forEach { add(ReceiptBlock.Line(l.twoCol(it.label, Money.format(byPayment[it] ?: 0)))) }
            add(ReceiptBlock.Line(l.twoCol("TRŽBA CELKEM", Money.format(total)), bold = true))
            if (tips != 0L) {
                add(ReceiptBlock.Line(l.twoCol("Spropitné (mimo DPH)", Money.format(tips))))
                add(ReceiptBlock.Line(l.twoCol("Přijato celkem", Money.format(total + tips))))
            }
            transfers?.let { t ->
                add(ReceiptBlock.Separator)
                add(ReceiptBlock.Line("Převody a QR platby", bold = true))
                add(ReceiptBlock.Line(l.twoCol("Přišlo (${t.confirmedCount})", Money.format(t.confirmed))))
                add(ReceiptBlock.Line(l.twoCol("Čeká (${t.waitingCount})", Money.format(t.waiting))))
                if (t.lateCount > 0) add(ReceiptBlock.Line(l.twoCol("Doplaceno za dřívější dny (${t.lateCount})", Money.format(t.late))))
                if (t.openCount > 0) add(ReceiptBlock.Line(l.twoCol("Neuhrazeno celkem (${t.openCount})", Money.format(t.open))))
                if (t.overdueCount > 0) add(ReceiptBlock.Line(l.twoCol("Z toho po splatnosti (${t.overdueCount})", Money.format(t.overdue)), bold = true))
            }
            if (business.vatPayer && byVat.isNotEmpty()) {
                add(ReceiptBlock.Separator)
                byVat.forEach {
                    add(ReceiptBlock.Line(l.twoCol("Základ ${it.rate.label}", Money.format(it.base))))
                    add(ReceiptBlock.Line(l.twoCol("DPH ${it.rate.label}", Money.format(it.vat))))
                }
            }
            add(ReceiptBlock.Feed(3))
        }
    }

    companion object {
        fun of(date: LocalDate, sales: List<SaleSummary>): DailyReport {
            val valid = sales.filterNot { it.cancelled }
            val vat = valid.flatMap { it.vat }.groupBy { it.rate }.map { (rate, lines) ->
                VatLine(rate, lines.sumOf { it.gross }, lines.sumOf { it.base }, lines.sumOf { it.vat })
            }.sortedByDescending { it.rate.percent }
            return DailyReport(
                date = date,
                receiptCount = valid.size,
                cancelledCount = sales.size - valid.size,
                total = valid.sumOf { it.total },
                byPayment = valid.groupBy { it.payment }.mapValues { (_, s) -> s.sumOf { it.total } },
                byVat = vat,
                tips = valid.sumOf { it.tip },
            )
        }
    }
}
