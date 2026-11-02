package cz.flos.core

import java.time.LocalDate

/** A finished sale, reduced to what the end-of-day report needs. */
data class SaleSummary(
    val total: Long,
    val payment: PaymentMethod,
    val vat: List<VatLine>,
    val cancelled: Boolean = false,
)

data class DailyReport(
    val date: LocalDate,
    val receiptCount: Int,
    val cancelledCount: Int,
    val total: Long,
    val byPayment: Map<PaymentMethod, Long>,
    val byVat: List<VatLine>,
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
            )
        }
    }
}
