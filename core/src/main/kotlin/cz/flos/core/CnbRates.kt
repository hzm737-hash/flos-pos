package cz.flos.core

import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Daily exchange rates of the Czech National Bank (kurzy devizového trhu). */
object CnbRates {
    const val URL = "https://www.cnb.cz/cs/financni-trhy/devizovy-trh/kurzy-devizoveho-trhu/kurzy-devizoveho-trhu/denni_kurz.txt"

    data class Rates(val date: LocalDate, val czkPerUnit: Map<String, Double>) {
        fun of(code: String): Double? = czkPerUnit[code.uppercase()]
    }

    /**
     * Parses the ČNB text file:
     * 03.11.2026 #213
     * země|měna|množství|kód|kurz
     * EMU|euro|1|EUR|24,330
     */
    fun parse(text: String): Rates {
        val lines = text.lines().filter { it.isNotBlank() }
        val date = LocalDate.parse(lines.first().substringBefore(' ').trim(), DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        val rates = lines.drop(2).mapNotNull { line ->
            val p = line.split('|')
            if (p.size < 5) return@mapNotNull null
            val amount = p[2].trim().toDoubleOrNull() ?: return@mapNotNull null
            val rate = p[4].trim().replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
            p[3].trim().uppercase() to rate / amount
        }.toMap()
        return Rates(date, rates)
    }

    fun fetch(timeoutMs: Int = 10_000): Rates {
        val conn = URI(URL).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        try {
            check(conn.responseCode == 200) { "ČNB: HTTP ${conn.responseCode}" }
            return parse(conn.inputStream.readBytes().toString(Charsets.UTF_8))
        } finally {
            conn.disconnect()
        }
    }
}
