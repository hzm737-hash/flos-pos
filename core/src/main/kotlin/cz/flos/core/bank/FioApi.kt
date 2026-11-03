package cz.flos.core.bank

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate
import java.time.ZoneId

/**
 * Fio banka API (read-only token from internet banking → Nastavení → API).
 * "last" returns the movements since the previous call; Fio keeps the bookmark, so each
 * movement comes only once. Fio allows one call per 30 seconds per token.
 */
object FioApi {
    const val BASE = "https://fioapi.fio.cz/v1/rest"
    const val MIN_INTERVAL_MS = 30_000L

    fun fetchNew(token: String, timeoutMs: Int = 20_000): List<IncomingPayment> {
        require(token.isNotBlank()) { "Chybí token Fio API" }
        return get("$BASE/last/${token.trim()}/transactions.json", timeoutMs)
    }

    /** Movements of the last [days] days; unlike [fetchNew] this does not move Fio's bookmark, so it is safe for a test. */
    fun fetchPeriod(token: String, days: Long = 7, timeoutMs: Int = 20_000): List<IncomingPayment> {
        require(token.isNotBlank()) { "Chybí token Fio API" }
        val to = LocalDate.now(ZoneId.of("Europe/Prague"))
        return get("$BASE/periods/${token.trim()}/${to.minusDays(days)}/$to/transactions.json", timeoutMs)
    }

    private fun get(url: String, timeoutMs: Int): List<IncomingPayment> {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        try {
            when (val code = conn.responseCode) {
                200 -> return parse(conn.inputStream.readBytes().toString(Charsets.UTF_8))
                409 -> throw IllegalStateException("Fio API: příliš časté dotazy, zkusím to za 30 s")
                401, 404, 500 -> throw IllegalStateException("Fio API odmítlo token (HTTP $code). Zkontrolujte token v nastavení.")
                else -> throw IllegalStateException("Fio API: HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Incoming CZK movements from a Fio transactions.json document. */
    fun parse(json: String): List<IncomingPayment> {
        val root = Json.parseToJsonElement(json).jsonObject
        val statement = root["accountStatement"]?.jsonObject ?: return emptyList()
        val currency = statement["info"]?.jsonObject?.get("currency")?.jsonPrimitive?.contentOrNull ?: "CZK"
        val list = statement["transactionList"]?.takeIf { it !is JsonNull }?.jsonObject?.get("transaction")
            ?.takeIf { it !is JsonNull }?.jsonArray ?: return emptyList()
        return list.mapNotNull { el ->
            val t = el.jsonObject
            val amount = col(t, 1)?.let { BigDecimal(it).movePointRight(2).toLong() } ?: return@mapNotNull null
            if (amount <= 0) return@mapNotNull null
            val id = col(t, 22) ?: return@mapNotNull null
            // For a converted payment Fio puts the original amount in "Upřesnění", e.g. "15.90 EUR".
            val detail = col(t, 18)?.let { Regex("""^\s*([0-9]+(?:[.,][0-9]{1,2})?)\s+([A-Z]{3})\s*$""").find(it) }
            val account = col(t, 2)?.let { acc -> col(t, 3)?.let { "$acc/$it" } ?: acc }
            IncomingPayment(
                sourceId = "fio:$id",
                amount = amount,
                variableSymbol = col(t, 5),
                at = col(t, 0)?.take(10)?.let { runCatching { LocalDate.parse(it).atStartOfDay(ZoneId.of("Europe/Prague")).toInstant().toEpochMilli() }.getOrNull() },
                counterAccount = account,
                counterName = col(t, 10),
                message = col(t, 16) ?: col(t, 25),
                currency = col(t, 14) ?: currency,
                originalAmount = detail?.let { BigDecimal(it.groupValues[1].replace(',', '.')).movePointRight(2).toLong() },
                originalCurrency = detail?.groupValues?.get(2),
            )
        }
    }

    private fun col(t: JsonObject, n: Int): String? {
        val c: JsonElement = t["column$n"] ?: return null
        if (c is JsonNull) return null
        return c.jsonObject["value"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
}
