package cz.flos.pos.bank

import cz.flos.core.bank.BankEmailParser
import cz.flos.core.bank.ExpectedPayment
import cz.flos.core.bank.FioApi
import cz.flos.core.bank.ImapAccount
import cz.flos.core.bank.ImapClient
import cz.flos.core.bank.IncomingPayment
import cz.flos.core.bank.MatchResult
import cz.flos.core.bank.Mime
import cz.flos.core.bank.PaymentMatcher
import cz.flos.core.bank.StatementParser
import cz.flos.pos.data.AppSettings
import cz.flos.pos.data.BankDao
import cz.flos.pos.data.BankPaymentEntity
import cz.flos.pos.data.BankPaymentStatus
import cz.flos.pos.data.BankSource
import cz.flos.pos.data.SaleEntity
import cz.flos.pos.data.SalesDao
import cz.flos.pos.data.SettingsStore
import cz.flos.pos.data.TransferStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What one check of the bank found. */
data class BankCheck(
    val newPayments: Int,
    val confirmed: List<SaleEntity>,
    val underpaid: List<SaleEntity>,
    val unmatched: Int,
    /** Tips found in this check: sale → tip amount added. */
    val tips: List<Pair<SaleEntity, Long>>,
)

/**
 * Reads new payments from the bank (notification e-mails or statements from any Czech bank, the Fio API, or a statement file), matches them to
 * receipts paid by QR code / transfer by variable symbol and amount, and records what the
 * customer sent above the receipt total as a tip.
 */
private val STATEMENT_FILES = setOf("gpc", "abo", "xml", "zip", "txt")

class BankSync(
    private val settings: SettingsStore,
    private val bank: BankDao,
    private val sales: SalesDao,
) {
    private val lock = Mutex()
    private var lastFioCall = 0L

    suspend fun check(): BankCheck = lock.withLock {
        val s = settings.state.value
        val incoming = withContext(Dispatchers.IO) {
            when (s.bankSource) {
                BankSource.OFF -> emptyList()
                BankSource.EMAIL -> readMailbox(s)
                // Several tokens (CZK and EUR account) can be given, separated by commas.
                BankSource.FIO -> {
                    val wait = FioApi.MIN_INTERVAL_MS - (System.currentTimeMillis() - lastFioCall)
                    if (wait > 0) emptyList()
                    else fioTokens(s).flatMap { FioApi.fetchNew(it) }.also { lastFioCall = System.currentTimeMillis() }
                }
            }
        }
        process(incoming)
    }

    /**
     * Imports a statement file downloaded from internet banking (GPC/ABO or CAMT.053 XML, also
     * zipped), so Flos works with any Czech bank even without e-mail or API access.
     */
    suspend fun importStatement(bytes: ByteArray): BankCheck = lock.withLock {
        val incoming = withContext(Dispatchers.Default) { StatementParser.parse(bytes) }
        if (incoming.isEmpty() && StatementParser.detect(bytes) == null && !isZip(bytes))
            throw IllegalArgumentException("Soubor není výpis ve formátu GPC/ABO ani CAMT.053 (XML)")
        process(incoming)
    }

    private fun isZip(b: ByteArray) = b.size > 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    private suspend fun process(incoming: List<IncomingPayment>): BankCheck {
        var added = 0
        var unmatched = 0
        val confirmed = mutableListOf<SaleEntity>()
        val underpaid = mutableListOf<SaleEntity>()
        val tips = mutableListOf<Pair<SaleEntity, Long>>()
        for (p in incoming) {
            if (reportedElsewhere(p)) continue
            val id = bank.insert(p.toEntity())
            if (id < 0) continue // read before
            added++
            val waiting = bank.waitingSales()
            when (val r = PaymentMatcher.match(p, waiting.map { it.expected() })) {
                MatchResult.NoMatch -> unmatched++
                else -> {
                    val sale = waiting.first { it.id == r.saleId }
                    val (updated, tipAdded) = apply(sale, r)
                    bank.update(p.toEntity().copy(id = id, saleId = sale.id, status = BankPaymentStatus.MATCHED.name))
                    if (updated.transferStatus == TransferStatus.CONFIRMED) confirmed += updated else underpaid += updated
                    if (tipAdded > 0) tips += updated to tipAdded
                }
            }
        }
        return BankCheck(added, confirmed, underpaid, unmatched, tips)
    }

    /**
     * The same payment can reach Flos twice: as a notification e-mail and again in the daily
     * statement (or from the Fio API). Same amount, currency and VS within three days from a
     * different kind of source is taken as the same payment.
     */
    private suspend fun reportedElsewhere(p: IncomingPayment): Boolean {
        val kind = p.sourceId.substringBefore(':')
        val at = p.at ?: System.currentTimeMillis()
        val day = 86_400_000L
        val vs = PaymentMatcher.variableSymbolOf(p)
        return bank.similar(p.amount, p.currency, at - 3 * day, at + 3 * day).any { other ->
            other.sourceId.substringBefore(':') != kind && other.sourceId != p.sourceId &&
                PaymentMatcher.normalizeVs(other.variableSymbol) == vs
        }
    }

    /** A person assigns a payment to a receipt by hand. Returns the updated sale and the tip it added. */
    suspend fun assign(payment: BankPaymentEntity, sale: SaleEntity): Pair<SaleEntity, Long> = lock.withLock {
        val expected = sale.expected()
        val value = PaymentMatcher.valueIn(IncomingPayment(payment.sourceId, payment.amount, currency = payment.currency), expected) ?: payment.amount
        val r = PaymentMatcher.settle(expected, value)
        val result = apply(sale, r)
        bank.update(payment.copy(saleId = sale.id, status = BankPaymentStatus.MATCHED.name))
        result
    }

    suspend fun ignore(payment: BankPaymentEntity) = bank.update(payment.copy(status = BankPaymentStatus.IGNORED.name))

    /** Confirms a receipt by hand (the money is seen in internet banking); [amount] above the total is a tip. */
    suspend fun confirmManually(sale: SaleEntity, amount: Long): Pair<SaleEntity, Long> = lock.withLock {
        apply(sale, PaymentMatcher.settle(sale.copy(bankPaid = 0).expected(), amount))
    }

    private suspend fun apply(sale: SaleEntity, r: MatchResult): Pair<SaleEntity, Long> {
        val updated = when (r) {
            // A tip from abroad comes in euro; the till keeps tips in crowns at the receipt's rate.
            is MatchResult.Paid -> sale.copy(
                bankStatus = TransferStatus.CONFIRMED.name, bankPaid = r.paid, tip = sale.toCzk(r.tip), bankConfirmedAt = System.currentTimeMillis(),
            )
            is MatchResult.Underpaid -> sale.copy(bankStatus = TransferStatus.UNDERPAID.name, bankPaid = r.paid)
            MatchResult.NoMatch -> sale
        }
        sales.update(updated)
        return updated to (updated.tip - sale.tip).coerceAtLeast(0)
    }

    private fun fioTokens(s: AppSettings) = s.fioToken.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        .ifEmpty { throw IllegalStateException("Chybí token Fio API") }

    private fun SaleEntity.expected() =
        if (isForeign) ExpectedPayment(id, variableSymbol, payAmount!!, bankPaid, payCurrency!!, eurRate)
        else ExpectedPayment(id, variableSymbol, received, bankPaid)

    private fun IncomingPayment.toEntity() = BankPaymentEntity(
        sourceId = sourceId, readAt = System.currentTimeMillis(), bookedAt = at, amount = amount, variableSymbol = variableSymbol,
        counterAccount = counterAccount, counterName = counterName,
        message = listOfNotNull(message, originalAmount?.let { "(${cz.flos.core.Money.format(it, originalCurrency ?: "")})" }).joinToString(" ").ifBlank { null },
        currency = currency,
    )

    private fun readMailbox(s: AppSettings): List<IncomingPayment> {
        require(s.imapHost.isNotBlank() && s.imapUser.isNotBlank()) { "Vyplňte poštovní server a uživatele" }
        val account = "${s.imapUser}@${s.imapHost}/${s.imapFolder}"
        val (validity, lastUid) = settings.imapPosition(account)
        val client = ImapClient(ImapAccount(s.imapHost.trim(), s.imapPort, s.imapUser.trim(), s.imapPassword, s.imapFolder.ifBlank { "INBOX" }))
        // A different UIDVALIDITY means the old UIDs do not apply; start again from recent days.
        var mails = client.fetchNew(lastUid)
        if (validity != 0L && client.uidValidity != validity && lastUid > 0) mails = client.fetchNew(0)
        val maxUid = mails.maxOfOrNull { it.uid } ?: if (client.uidValidity == validity) lastUid else 0
        settings.saveImapPosition(account, client.uidValidity, maxUid)
        return mails.flatMap { m -> paymentsInMail("imap:${client.uidValidity}:${m.uid}", m.raw) }
    }

    /**
     * A bank e-mail is either a notification about one payment (read from its text) or a daily
     * statement sent as an attachment (GPC/ABO or CAMT.053, as most Czech banks can send).
     */
    private fun paymentsInMail(sourceId: String, raw: ByteArray): List<IncomingPayment> {
        val fromFiles = Mime.attachments(raw)
            .filter { a -> a.name.substringAfterLast('.').lowercase() in STATEMENT_FILES }
            .flatMap { a -> runCatching { StatementParser.parse(a.bytes) }.getOrDefault(emptyList()) }
        if (fromFiles.isNotEmpty()) return fromFiles
        val msg = Mime.parse(raw)
        return listOfNotNull(BankEmailParser.parse(sourceId, msg.subject, msg.text))
    }

    /** Checks the connection without storing anything; returns a short description for the settings screen. */
    suspend fun test(s: AppSettings): String = withContext(Dispatchers.IO) {
        when (s.bankSource) {
            BankSource.OFF -> "Vypnuto"
            BankSource.EMAIL -> {
                val client = ImapClient(ImapAccount(s.imapHost.trim(), s.imapPort, s.imapUser.trim(), s.imapPassword, s.imapFolder.ifBlank { "INBOX" }))
                val mails = client.fetchNew(0, firstRunDays = 7, max = 30)
                val payments = mails.sumOf { m -> paymentsInMail("t", m.raw).size }
                "OK: ${mails.size} e-mailů za 7 dní, z toho $payments příchozích plateb"
            }
            BankSource.FIO -> {
                val wait = FioApi.MIN_INTERVAL_MS - (System.currentTimeMillis() - lastFioCall)
                if (wait > 0) kotlinx.coroutines.delay(wait)
                val list = fioTokens(s).flatMap { FioApi.fetchPeriod(it) }.also { lastFioCall = System.currentTimeMillis() }
                "OK: ${list.size} příchozích plateb za 7 dní"
            }
        }
    }
}
