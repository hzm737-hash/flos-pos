package cz.flos.core.bank

/**
 * A credit on the business bank account, read from the bank's notification e-mail or from the
 * bank's API. Amounts are in haléře.
 */
data class IncomingPayment(
    /** Stable id from the source (IMAP message, Fio transaction id) so a payment is never counted twice. */
    val sourceId: String,
    val amount: Long,
    val variableSymbol: String? = null,
    /** Epoch milliseconds when known. */
    val at: Long? = null,
    val counterAccount: String? = null,
    val counterName: String? = null,
    val message: String? = null,
    val currency: String = "CZK",
)
