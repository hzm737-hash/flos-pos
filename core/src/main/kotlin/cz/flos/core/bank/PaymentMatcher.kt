package cz.flos.core.bank

import kotlin.math.roundToLong

/** A receipt paid by QR code or bank transfer that waits for the money to arrive. */
data class ExpectedPayment(
    val saleId: Long,
    val variableSymbol: String?,
    /** What the customer should send, in [currency] minor units (haléře, euro cents). */
    val total: Long,
    /** What already arrived for this receipt, in [currency]. */
    val alreadyPaid: Long = 0,
    /** CZK, or EUR for a payer from abroad who got a SEPA QR code. */
    val currency: String = "CZK",
    /** CZK per 1 EUR used on the receipt; needed to compare euro and crown amounts. */
    val czkPerEur: Double? = null,
)

sealed interface MatchResult {
    val saleId: Long?

    /** Paid in full; [paid] and [tip] are in the receipt's currency. [tip] is what came above the total. */
    data class Paid(override val saleId: Long, val paid: Long, val tip: Long) : MatchResult

    /** Less than the receipt total has arrived so far. */
    data class Underpaid(override val saleId: Long, val paid: Long, val missing: Long) : MatchResult

    /** No waiting receipt fits; the payment goes to the list to check by hand. */
    data object NoMatch : MatchResult { override val saleId: Long? = null }
}

/**
 * Pairs a bank payment with a waiting receipt.
 *
 * 1. The variable symbol decides. It is taken from the payment's VS field or, for payments from
 *    abroad where there is no such field, from the message ("/VS/2026000012" or "VS 2026000012").
 * 2. Without a usable symbol, a payment of exactly the receipt total is accepted when only one
 *    waiting receipt has that total, so a mistyped symbol does not block a clear case.
 * Payments from abroad are compared in euro. Because the bank converts at its own rate and
 * foreign banks may deduct a fee, up to [FOREIGN_TOLERANCE_PERCENT] % less still counts as paid.
 */
object PaymentMatcher {
    const val FOREIGN_TOLERANCE_PERCENT = 3

    private val vsInText = Regex("""(?i)(?:/VS/|\bVS\s*[:.]?\s*|variabilni symbol\s*:?\s*|variabilní symbol\s*:?\s*)(\d{1,10})""")

    fun variableSymbolOf(p: IncomingPayment): String? =
        normalizeVs(p.variableSymbol) ?: p.message?.let { m -> vsInText.find(m)?.groupValues?.get(1) }?.let(::normalizeVs)

    fun match(payment: IncomingPayment, waiting: List<ExpectedPayment>): MatchResult {
        if (payment.amount <= 0) return MatchResult.NoMatch
        val vs = variableSymbolOf(payment)
        val byVs = vs?.let { v -> waiting.firstOrNull { normalizeVs(it.variableSymbol) == v } }
        val target = byVs ?: waiting.filter { it.alreadyPaid == 0L && valueIn(payment, it) == it.total }.singleOrNull()
            ?: return MatchResult.NoMatch
        val value = valueIn(payment, target) ?: return MatchResult.NoMatch
        return settle(target, value)
    }

    /** The payment expressed in the receipt's currency, or null when it cannot be converted. */
    fun valueIn(p: IncomingPayment, target: ExpectedPayment): Long? {
        val cur = p.currency.uppercase()
        val orig = p.originalCurrency?.uppercase()
        return when (target.currency.uppercase()) {
            "CZK" -> when {
                cur == "CZK" -> p.amount
                cur == "EUR" && target.czkPerEur != null -> (p.amount * target.czkPerEur).roundToLong()
                else -> null
            }
            "EUR" -> when {
                cur == "EUR" -> p.amount
                orig == "EUR" && p.originalAmount != null -> p.originalAmount
                cur == "CZK" && target.czkPerEur != null -> (p.amount / target.czkPerEur).roundToLong()
                else -> null
            }
            else -> if (cur == target.currency.uppercase()) p.amount else null
        }
    }

    /** Applies [amount] (in the receipt's currency) to [target], e.g. when a person assigns a payment by hand. */
    fun settle(target: ExpectedPayment, amount: Long): MatchResult {
        val paid = target.alreadyPaid + amount
        val tolerance = if (target.currency.equals("CZK", true)) 0 else target.total * FOREIGN_TOLERANCE_PERCENT / 100
        return when {
            paid >= target.total -> MatchResult.Paid(target.saleId, paid, paid - target.total)
            paid >= target.total - tolerance -> MatchResult.Paid(target.saleId, paid, 0)
            else -> MatchResult.Underpaid(target.saleId, paid, target.total - paid)
        }
    }

    /** "0002026000012" and "2026000012" are the same symbol; banks pad it with zeros. */
    fun normalizeVs(vs: String?): String? = vs?.filter(Char::isDigit)?.trimStart('0')?.takeIf { it.isNotEmpty() }
}
