package cz.flos.core

import java.net.URLEncoder

/**
 * Messages to customers through apps the shop already has: WhatsApp, SMS and e-mail open
 * with the text filled in, and the cashier only presses Send. No paid gateway is needed.
 *
 * Templates use placeholders in braces: {jmeno}, {datum}, {cas}, {hoste}, {stul}, {cislo},
 * {castka}, {provozovna}, {telefon}, {ucet}, {vs}.
 */
object Notify {
    const val RESERVATION = "Dobrý den {jmeno}, potvrzujeme rezervaci na {datum} v {cas} pro {hoste} os. Těšíme se na Vás. {provozovna}, tel. {telefon}"
    const val REMINDER = "Dobrý den {jmeno}, připomínáme Vaši rezervaci dnes v {cas}. {provozovna}"
    const val ORDER_READY = "Dobrý den {jmeno}, Vaše objednávka č. {cislo} je připravena k vyzvednutí. {provozovna}"
    const val DOCUMENT = "Dobrý den {jmeno}, posíláme {cislo} na částku {castka}. Platba na účet {ucet}, VS {vs}. {provozovna}"

    /** Replaces {placeholders}; unknown ones and those without a value disappear. Both braces are escaped: Android's regex engine rejects a bare "}". */
    fun fill(template: String, values: Map<String, String>): String =
        Regex("""\{([a-zA-Z]+)\}""").replace(template) { m -> values[m.groupValues[1].lowercase()].orEmpty() }
            .replace(Regex("""[ \t]{2,}"""), " ").replace(Regex("""\s+([,.!?])"""), "$1").trim()

    /** "777 123 456", "+420 777…", "00420777…" → "420777123456"; null when it is not a phone number. */
    fun phoneDigits(raw: String): String? {
        val t = raw.trim()
        var d = t.filter(Char::isDigit)
        if (t.startsWith("00")) d = d.drop(2)
        if (!t.startsWith("+") && !t.startsWith("00") && d.length == 9) d = "420$d"
        return d.takeIf { it.length in 10..15 }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun whatsapp(phone: String, text: String): String? = phoneDigits(phone)?.let { "https://wa.me/$it?text=${enc(text)}" }

    fun sms(phone: String, text: String): String? = phoneDigits(phone)?.let { "sms:+$it?body=${enc(text)}" }

    fun email(to: String, subject: String, text: String): String? =
        to.trim().takeIf { it.contains('@') && !it.contains(' ') }?.let { "mailto:$it?subject=${enc(subject)}&body=${enc(text)}" }
}
