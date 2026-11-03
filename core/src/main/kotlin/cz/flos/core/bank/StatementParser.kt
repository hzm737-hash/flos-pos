package cz.flos.core.bank

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads incoming payments from a bank statement file. Every Czech bank exports at least one of:
 *  - GPC / ABO (the Czech "Gemini" format, *.gpc / *.abo), fixed 128-character lines;
 *  - ISO 20022 CAMT.053 statement (or CAMT.054 notification) XML.
 * Only credits (money coming in) are returned.
 */
object StatementParser {
    enum class Format { GPC, CAMT }

    private val PRAGUE: ZoneId = ZoneId.of("Europe/Prague")

    fun detect(bytes: ByteArray): Format? {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1).trimStart('﻿', 'ï', '»', '¿', ' ', '\r', '\n', '\t')
        return when {
            head.startsWith("074") || head.startsWith("075") -> Format.GPC
            head.startsWith("<") && Regex("camt\\.05[234]|BkToCstmr").containsMatchIn(head) -> Format.CAMT
            else -> null
        }
    }

    /** Parses a statement, or each statement inside a ZIP archive. Unknown content gives an empty list. */
    fun parse(bytes: ByteArray): List<IncomingPayment> {
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) return unzip(bytes).flatMap { parse(it) }
        return when (detect(bytes)) {
            Format.GPC -> parseGpc(bytes)
            Format.CAMT -> parseCamt(bytes)
            null -> emptyList()
        }
    }

    private fun unzip(bytes: ByteArray): List<ByteArray> = runCatching {
        val out = mutableListOf<ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory && out.size < 100) out += zip.readNBytes(20 * 1024 * 1024)
            }
        }
        out
    }.getOrDefault(emptyList())

    // ---------------------------------------------------------------- GPC / ABO

    private val GPC_DATE = DateTimeFormatter.ofPattern("ddMMyy")
    private val CURRENCY_BY_NUMBER = mapOf("203" to "CZK", "978" to "EUR", "840" to "USD", "826" to "GBP", "985" to "PLN", "348" to "HUF", "756" to "CHF")

    /**
     * GPC record "075" (positions 1-based):
     * 4-19 own account, 20-35 counter account, 36-48 document number, 49-60 amount in haléře,
     * 61 accounting code (1 debit, 2 credit, 4 storno of debit, 5 storno of credit), 62-71 VS,
     * 72-81 counter bank code + KS, 82-91 SS, 92-97 value date DDMMYY, 98-117 counter-account name,
     * 119-122 currency (ISO numeric), 123-128 due date. Lines 076-079 that follow carry the message.
     */
    fun parseGpc(bytes: ByteArray): List<IncomingPayment> {
        val text = decodeGpc(bytes)
        val out = mutableListOf<IncomingPayment>()
        var current: IncomingPayment? = null
        val seen = HashMap<String, Int>()
        fun flush() { current?.let { out += it }; current = null }
        for (raw in text.split(Regex("\r?\n"))) {
            val line = raw.trimEnd('\r')
            if (line.length < 3) continue
            when (line.substring(0, 3)) {
                "075" -> {
                    flush()
                    if (line.length < 97) continue
                    val f = { from: Int, to: Int -> line.substring(minOf(from - 1, line.length), minOf(to, line.length)) }
                    if (f(61, 61) != "2") continue // only money that came in
                    val amount = f(49, 60).trim().toLongOrNull() ?: continue
                    if (amount <= 0) continue
                    val own = f(4, 19).trimStart('0')
                    val counter = f(20, 35).trim().trimStart('0')
                    val bank = f(74, 77).trim().takeIf { it.isNotBlank() && it.any { c -> c != '0' } }
                    val vs = f(62, 71).trim().trimStart('0').ifEmpty { null }
                    val date = runCatching { LocalDate.parse(f(92, 97), GPC_DATE) }.getOrNull()
                    val currency = f(119, 122).trim().takeLast(3).let { CURRENCY_BY_NUMBER[it] } ?: "CZK"
                    val doc = f(36, 48).trim()
                    val key = "gpc:$own:$doc:${f(92, 97)}:$amount:${vs.orEmpty()}"
                    val n = seen.merge(key, 1, Int::plus)!!
                    current = IncomingPayment(
                        sourceId = if (n == 1) key else "$key#$n",
                        amount = amount,
                        variableSymbol = vs,
                        at = date?.atStartOfDay(PRAGUE)?.toInstant()?.toEpochMilli(),
                        counterAccount = counter.ifEmpty { null }?.let { c -> if (bank != null) "$c/$bank" else c },
                        counterName = f(98, 117).trim().ifEmpty { null },
                        currency = currency,
                    )
                }
                "076", "078", "079" -> current = current?.let { p ->
                    val extra = line.substring(3).trim().replace(Regex("\\s{2,}"), " ")
                    if (extra.isEmpty()) p else {
                        val message = listOfNotNull(p.message, extra).joinToString(" ")
                        // A payment without VS (e.g. from abroad) may carry "/VS/123" in the message.
                        p.copy(message = message, variableSymbol = p.variableSymbol ?: PaymentMatcher.variableSymbolOf(p.copy(message = message)))
                    }
                }
                else -> flush()
            }
        }
        flush()
        return out
    }

    /** GPC files are windows-1250 at most banks; some send UTF-8 or the old Kamenický code page. */
    private fun decodeGpc(bytes: ByteArray): String {
        val utf8 = runCatching {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        return utf8 ?: String(bytes, runCatching { Charset.forName("windows-1250") }.getOrDefault(Charsets.ISO_8859_1))
    }

    // ---------------------------------------------------------------- CAMT.053 / 054

    fun parseCamt(bytes: ByteArray): List<IncomingPayment> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Never fetch anything a file refers to.
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val out = mutableListOf<IncomingPayment>()
        val seen = HashMap<String, Int>()
        for (stmt in doc.documentElement.all("Stmt") + doc.documentElement.all("Ntfctn") + doc.documentElement.all("Rpt")) {
            val own = stmt.child("Acct")?.child("Id")?.let { it.text("IBAN") ?: it.child("Othr")?.text("Id") }.orEmpty()
            for (entry in stmt.childrenNamed("Ntry")) {
                if (entry.text("CdtDbtInd") != "CRDT") continue
                if (entry.text("RvslInd") == "true") continue
                // Pending or informational entries are not money on the account yet ("<Sts>BOOK</Sts>" or "<Sts><Cd>BOOK</Cd></Sts>").
                val status = entry.child("Sts")?.let { it.text("Cd") ?: it.textContent.trim() }
                if (status == "PDNG" || status == "INFO") continue
                val entryAmount = entry.child("Amt")
                val booked = (entry.child("BookgDt") ?: entry.child("ValDt"))?.let { d -> d.text("Dt") ?: d.text("DtTm") }
                val at = booked?.let { parseDate(it) }
                val entryRef = entry.text("AcctSvcrRef") ?: entry.text("NtryRef")
                val details = entry.all("TxDtls").ifEmpty { listOf(entry) }
                for ((i, tx) in details.withIndex()) {
                    val amt = (if (details.size > 1) tx.child("Amt") ?: tx.child("AmtDtls")?.child("TxAmt")?.child("Amt") else null) ?: entryAmount ?: continue
                    val amount = parseAmount(amt.textContent) ?: continue
                    if (amount <= 0) continue
                    val currency = amt.getAttribute("Ccy").ifBlank { "CZK" }
                    val refs = tx.child("Refs")
                    val e2e = refs?.text("EndToEndId")
                    val structured = tx.all("CdtrRefInf").mapNotNull { it.text("Ref") }
                    val message = tx.all("Ustrd").joinToString(" ") { it.textContent.trim() }.ifBlank { null }
                        ?: tx.text("AddtlTxInf") ?: entry.text("AddtlNtryInf")
                    val vs = (listOfNotNull(e2e) + structured).firstNotNullOfOrNull { vsOf(it) }
                    val party = tx.child("RltdPties")
                    val debtor = party?.child("Dbtr")?.let { it.child("Pty") ?: it }
                    val debtorAcct = party?.child("DbtrAcct")?.child("Id")
                    val instructed = tx.child("AmtDtls")?.child("InstdAmt")?.child("Amt")
                    val original = instructed?.takeIf { it.getAttribute("Ccy").isNotBlank() && it.getAttribute("Ccy") != currency }
                    val txRef = refs?.text("AcctSvcrRef") ?: refs?.text("TxId") ?: entryRef
                    val key = "camt:$own:${txRef ?: (booked + ":" + amount + ":" + vs.orEmpty())}" + if (details.size > 1 && txRef == entryRef) ":$i" else ""
                    val n = seen.merge(key, 1, Int::plus)!!
                    var p = IncomingPayment(
                        sourceId = if (n == 1) key else "$key#$n",
                        amount = amount,
                        variableSymbol = vs,
                        at = at,
                        counterAccount = debtorAcct?.let { it.text("IBAN") ?: it.child("Othr")?.text("Id") },
                        counterName = debtor?.text("Nm"),
                        message = message,
                        currency = currency,
                        originalAmount = original?.let { parseAmount(it.textContent) },
                        originalCurrency = original?.getAttribute("Ccy"),
                    )
                    if (p.variableSymbol == null) p = p.copy(variableSymbol = PaymentMatcher.variableSymbolOf(p))
                    out += p
                }
            }
        }
        return out
    }

    /** A reference field holds the VS as "VS123", "VS:123", "/VS/123" or as plain digits. */
    fun vsOf(ref: String): String? {
        val r = ref.trim()
        if (r.isEmpty() || r.equals("NOTPROVIDED", true)) return null
        Regex("""(?i)VS[:/ ]*(\d{1,10})""").find(r)?.let { return it.groupValues[1].trimStart('0').ifEmpty { null } }
        if (r.length <= 10 && r.all(Char::isDigit)) return r.trimStart('0').ifEmpty { null }
        return null
    }

    private fun parseAmount(s: String): Long? = s.trim().toBigDecimalOrNull()?.movePointRight(2)?.setScale(0, java.math.RoundingMode.HALF_UP)?.toLong()

    private fun parseDate(s: String): Long? =
        runCatching { LocalDate.parse(s.take(10)).atStartOfDay(PRAGUE).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()

    private fun Element.childrenNamed(name: String): List<Element> {
        val out = mutableListOf<Element>()
        var n = firstChild
        while (n != null) {
            if (n is Element && (n.localName ?: n.nodeName.substringAfter(':')) == name) out += n
            n = n.nextSibling
        }
        return out
    }

    private fun Element.child(name: String): Element? = childrenNamed(name).firstOrNull()

    private fun Element.text(name: String): String? = child(name)?.textContent?.trim()?.ifEmpty { null }

    private fun Element.all(name: String): List<Element> {
        val list = getElementsByTagNameNS("*", name)
        return (0 until list.length).map { list.item(it) as Element }
    }
}
