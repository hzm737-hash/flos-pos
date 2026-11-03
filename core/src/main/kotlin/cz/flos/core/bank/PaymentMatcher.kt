package cz.flos.core.bank

/** A receipt paid by QR code or bank transfer that waits for the money to arrive. */
data class ExpectedPayment(
    val saleId: Long,
    val variableSymbol: String?,
    /** Receipt total in haléře. */
    val total: Long,
    /** What already arrived for this receipt (earlier partial payments). */
    val alreadyPaid: Long = 0,
)

sealed interface MatchResult {
    val saleId: Long?

    /** Paid in full; [tip] is what the customer sent above the receipt total. */
    data class Paid(override val saleId: Long, val paid: Long, val tip: Long) : MatchResult

    /** Less than the receipt total has arrived so far. */
    data class Underpaid(override val saleId: Long, val paid: Long, val missing: Long) : MatchResult

    /** No waiting receipt fits; the payment goes to the list to check by hand. */
    data object NoMatch : MatchResult { override val saleId: Long? = null }
}

/**
 * Pairs a bank payment with a waiting receipt.
 *
 * 1. The variable symbol decides when the payment has one (Flos puts it in every QR code).
 * 2. Without a usable symbol, a payment of exactly the receipt total is accepted when only one
 *    waiting receipt has that total, so a mistyped symbol does not block a clear case.
 * Everything else is left for a person to assign.
 */
object PaymentMatcher {
    fun match(payment: IncomingPayment, waiting: List<ExpectedPayment>): MatchResult {
        if (payment.amount <= 0 || !payment.currency.equals("CZK", ignoreCase = true)) return MatchResult.NoMatch
        val vs = normalizeVs(payment.variableSymbol)
        val byVs = vs?.let { v -> waiting.firstOrNull { normalizeVs(it.variableSymbol) == v } }
        val target = byVs ?: waiting.filter { it.alreadyPaid == 0L && it.total == payment.amount }.singleOrNull()
            ?: return MatchResult.NoMatch
        return settle(target, payment.amount)
    }

    /** Applies [amount] to [target], e.g. when a person assigns a payment by hand. */
    fun settle(target: ExpectedPayment, amount: Long): MatchResult {
        val paid = target.alreadyPaid + amount
        return if (paid >= target.total) MatchResult.Paid(target.saleId, paid, paid - target.total)
        else MatchResult.Underpaid(target.saleId, paid, target.total - paid)
    }

    /** "0002026000012" and "2026000012" are the same symbol; banks pad it with zeros. */
    fun normalizeVs(vs: String?): String? = vs?.filter(Char::isDigit)?.trimStart('0')?.takeIf { it.isNotEmpty() }
}
