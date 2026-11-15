package cz.flos.core.bank

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * Payment advices (avíza) of ČSOB in the MT942 format, as Business Connector delivers them
 * (files "AV_<account>_<date>_<n>.STA", windows-1250). Each :61: line is one movement; the :86:
 * block after it has the details in "?NN" subfields:
 *  - 111 domestic: ?00 counter name, ?21 counter account, ?22 "VS:…", ?25–?28 message;
 *  - 030 foreign / SEPA: ?20 counter name, ?22–?26 purpose, ?31 IBAN;
 *  - 040 other: ?00 name, ?21 "VS:…", ?22–?25 detail.
 * Only credits (C) are returned; reversals (RC/RD) and debits are skipped.
 */
object Mt942Parser {
    private val LINE61 = Regex("""^(\d{6})(\d{4})?(RC|RD|C|D)[A-Z]?(\d+(?:,\d{0,2})?)(.{4})(.*)$""")
    private val SUB = Regex("""\?(\d{2})""")
    private val OCMT = Regex("""/OCMT/([A-Z]{3})(\d+(?:,\d{0,2})?)""")

    fun looksLike(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 400), Charsets.ISO_8859_1)
        return head.contains(":61:") || (head.contains("942") && head.contains(":25:"))
    }

    fun parse(bytes: ByteArray): List<IncomingPayment> = parse(decode(bytes))

    /** ČSOB sends windows-1250; a file saved as UTF-8 is read as UTF-8. */
    fun decode(bytes: ByteArray): String {
        val utf = runCatching {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        return utf ?: String(bytes, charset("windows-1250"))
    }

    fun parse(text: String): List<IncomingPayment> {
        val lines = text.split("\r\n", "\n", "\r").filter { it.isNotEmpty() }
        val account = lines.firstOrNull { it.startsWith(":25:") }?.removePrefix(":25:")?.trim().orEmpty()
        val currency = lines.firstOrNull { it.startsWith(":34F:") }?.removePrefix(":34F:")?.take(3)?.takeIf { it.length == 3 && it.all(Char::isLetter) } ?: "CZK"

        // Group lines into movements: a :61: line, its continuation lines, then :86: and its continuation lines.
        val movements = mutableListOf<Pair<StringBuilder, StringBuilder>>()
        var inDetail = false
        for (line in lines) {
            when {
                line.startsWith(":61:") -> { movements += StringBuilder(line.removePrefix(":61:")) to StringBuilder(); inDetail = false }
                line.startsWith(":86:") -> { movements.lastOrNull()?.second?.append(line.removePrefix(":86:")); inDetail = true }
                line.startsWith(":") && line.length > 3 && line[1].isDigit() -> inDetail = false
                movements.isNotEmpty() -> if (inDetail) movements.last().second.append(line) else movements.last().first.append('\n').append(line)
            }
        }

        val seen = mutableMapOf<String, Int>()
        return movements.mapNotNull { (basic, detail) -> movement(basic.toString(), detail.toString(), account, currency, seen) }
    }

    private fun movement(basicText: String, detail: String, account: String, currency: String, seen: MutableMap<String, Int>): IncomingPayment? {
        val firstLine = basicText.substringBefore('\n')
        val m = LINE61.find(firstLine) ?: return null
        if (m.groupValues[3] != "C") return null
        val amount = BigDecimal(m.groupValues[4].replace(',', '.').trimEnd('.')).movePointRight(2).toLong()
        if (amount <= 0) return null
        val valueDate = m.groupValues[1]
        val bankRef = m.groupValues[6].substringAfter("//", "").trim().substringBefore(' ').ifBlank { null }

        val kind = detail.take(3)
        val fields = subfields(detail)
        val name: String?
        val counter: String?
        val vs: String?
        val message: String?
        when (kind) {
            "030" -> {
                name = fields["20"]
                counter = fields["31"]
                vs = null
                message = listOf("22", "23", "24", "25", "26").mapNotNull { fields[it] }.joinToString(" ")
            }
            "040" -> {
                name = fields["00"]
                counter = null
                vs = symbol(fields["21"], "VS")
                message = listOf("22", "23", "24", "25").mapNotNull { fields[it] }.joinToString(" ")
            }
            else -> {
                name = fields["00"]
                counter = fields["21"]?.let(::shortAccount)
                vs = symbol(fields["22"], "VS") ?: symbol(fields["29"], "VS")
                message = listOf("25", "26", "27", "28").mapNotNull { fields[it] }.joinToString("")
            }
        }
        val original = OCMT.find(basicText)
        val at = runCatching {
            LocalDate.of(2000 + valueDate.take(2).toInt(), valueDate.substring(2, 4).toInt(), valueDate.substring(4, 6).toInt())
                .atStartOfDay(ZoneId.of("Europe/Prague")).toInstant().toEpochMilli()
        }.getOrNull()
        val key = "csob:" + (bankRef ?: "$account:$valueDate:$amount:${vs.orEmpty()}")
        val n = (seen[key] ?: 0) + 1
        seen[key] = n
        return IncomingPayment(
            sourceId = if (n == 1) key else "$key#$n",
            amount = amount,
            variableSymbol = vs,
            at = at,
            counterAccount = counter,
            counterName = name,
            message = message?.trim()?.ifBlank { null },
            currency = currency,
            originalAmount = original?.let { BigDecimal(it.groupValues[2].replace(',', '.').trimEnd('.')).movePointRight(2).toLong() },
            originalCurrency = original?.groupValues?.get(1),
        )
    }

    /** "?00NAME?20ZAUCT.PLATBA?21…" → {"00": "NAME", …}; "." alone means empty. */
    private fun subfields(detail: String): Map<String, String> {
        val marks = SUB.findAll(detail).toList()
        return marks.mapIndexed { i, mk ->
            val end = if (i + 1 < marks.size) marks[i + 1].range.first else detail.length
            mk.groupValues[1] to detail.substring(mk.range.last + 1, end).trim()
        }.filter { it.second.isNotEmpty() && it.second != "." }.toMap()
    }

    private fun symbol(field: String?, prefix: String): String? =
        field?.removePrefix("$prefix:")?.trim()?.trimStart('0')?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    /** "000019-0000000019/0300" → "19-19/0300", "000000-0181582348/0300" → "181582348/0300". */
    private fun shortAccount(a: String): String {
        val bank = a.substringAfter('/', "")
        val num = a.substringBefore('/')
        val prefix = num.substringBefore('-', "").trimStart('0')
        val main = num.substringAfter('-').trimStart('0')
        if (main.isEmpty()) return a
        return (if (prefix.isNotEmpty()) "$prefix-" else "") + main + (if (bank.isNotEmpty()) "/$bank" else "")
    }
}
