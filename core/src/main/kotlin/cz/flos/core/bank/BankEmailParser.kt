package cz.flos.core.bank

import cz.flos.core.Money

/**
 * Reads an incoming-payment notification e-mail from a Czech bank. Banks word these differently,
 * so the parser looks for the usual labels (Částka, VS / Variabilní symbol, Protiúčet, Zpráva)
 * and refuses anything that looks like an outgoing payment.
 */
object BankEmailParser {
    private const val NUM = """[+\-−]?\s?\d{1,3}(?:[ \u00A0.,']?\d{3})*(?:[,.]\d{1,2})?"""

    private val amountLabeled = Regex(
        """(?iu)(?:částka|castka|amount|objem|suma|připsaná částka|výše platby)\s*(?:transakce|platby)?\s*[:=]?\s*($NUM)\s*(CZK|Kč|Kc|EUR|€)?""",
    )
    private val amountWithCurrency = Regex("""($NUM)\s*(CZK|Kč|Kc)\b""", RegexOption.IGNORE_CASE)
    private val vsRegex = Regex("""(?iu)(?:\bVS\b|\bV\.S\.|variabilní symbol|variabilni symbol|var\. ?symbol)\s*[:.=]?\s*(\d{1,10})""")
    private val accountRegex = Regex("""(?iu)(?:protiúčet|protiucet|z účtu|z uctu|účet plátce|ucet platce|counter ?account|from account)\s*[:=]?\s*([0-9]{0,6}-?[0-9]{2,10}/[0-9]{4}|[A-Z]{2}\d{2}[A-Z0-9 ]{10,30})""")
    private val nameRegex = Regex("""(?iu)(?:název protiúčtu|nazev protiuctu|plátce|platce|odesílatel|odesilatel|jméno plátce)\s*[:=]\s*([^\r\n]{2,80})""")
    private val messageRegex = Regex("""(?iu)(?:zpráva pro příjemce|zprava pro prijemce|zpráva příjemci|zprava prijemci|zpráva|zprava|message)\s*[:=]\s*([^\r\n]{1,140})""")

    private val incomingWords = listOf(
        "příchozí", "prichozi", "příjem", "prijem", "připsán", "pripsan", "připsána", "kredit", "credit", "incoming", "received",
        "přišla platba", "prisla platba", "zvýšení zůstatku", "zvyseni zustatku",
    )
    private val outgoingWords = listOf(
        "odchozí", "odchozi", "výdaj", "vydaj", "odepsán", "odepsan", "debet", "debit", "outgoing", "snížení zůstatku", "snizeni zustatku",
        "platba kartou", "výběr", "vyber z bankomatu",
    )

    fun parse(sourceId: String, subject: String, body: String, receivedAt: Long? = null): IncomingPayment? {
        val text = (subject + "\n" + body).replace(' ', ' ')
        val lower = text.lowercase()
        val amountMatch = amountLabeled.find(text) ?: amountWithCurrency.find(text) ?: return null
        val rawAmount = amountMatch.groupValues[1]
        val currency = amountMatch.groupValues.getOrNull(2)?.uppercase()?.let { if (it == "KČ" || it == "KC") "CZK" else if (it == "€") "EUR" else it }
            ?.takeIf { it.isNotEmpty() } ?: "CZK"
        val negative = rawAmount.trim().startsWith("-") || rawAmount.trim().startsWith("−")
        val amount = parseAmount(rawAmount) ?: return null
        val incoming = incomingWords.any { it in lower }
        val outgoing = outgoingWords.any { it in lower }
        // A minus sign, or outgoing wording without any incoming wording, means money left the account.
        if (negative || (outgoing && !incoming) || amount <= 0) return null
        return IncomingPayment(
            sourceId = sourceId,
            amount = amount,
            variableSymbol = vsRegex.find(text)?.groupValues?.get(1),
            at = receivedAt,
            counterAccount = accountRegex.find(text)?.groupValues?.get(1)?.trim(),
            counterName = nameRegex.find(text)?.groupValues?.get(1)?.trim(),
            message = messageRegex.find(text)?.groupValues?.get(1)?.trim(),
            currency = currency,
        )
    }

    /** "1 234,50", "1.234,50", "+250,00", "250" → haléře. */
    fun parseAmount(raw: String): Long? {
        var s = raw.replace("+", "").replace("-", "").replace("−", "").replace(" ", "").replace("'", "").trim()
        val lastComma = s.lastIndexOf(',')
        val lastDot = s.lastIndexOf('.')
        s = when {
            // Both used: the last one is the decimal mark ("1.234,50" or "1,234.50").
            lastComma >= 0 && lastDot >= 0 -> if (lastComma > lastDot) s.replace(".", "") else s.replace(",", "")
            // Only dots, each followed by three digits: thousands ("3.400").
            lastDot >= 0 && Regex("""^\d{1,3}(\.\d{3})+$""").matches(s) -> s.replace(".", "")
            else -> s
        }
        return Money.parse(s)
    }
}
