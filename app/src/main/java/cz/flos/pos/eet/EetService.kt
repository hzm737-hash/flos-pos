package cz.flos.pos.eet

import cz.flos.pos.ui.tr
import android.content.Context
import cz.flos.core.eet.EetCertificate
import cz.flos.core.eet.EetClient
import cz.flos.core.eet.EetFormat
import cz.flos.core.eet.EetResult
import cz.flos.core.eet.EetSale
import cz.flos.pos.data.AppSettings
import cz.flos.pos.data.EetDao
import cz.flos.pos.data.EetRecordEntity
import cz.flos.pos.data.EetStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Records sales in EET 2.0.
 *
 * Every sale is first stored as a PENDING record, so nothing is lost if the phone has no
 * internet. It is then sent at once; if no answer comes within the response time limit, the
 * receipt is printed without POK and the record is resent automatically (see [EetRetryWorker])
 * with prvni_zaslani=false and a new message UUID, until a confirmation arrives. The law asks
 * for sending within 48 hours of the sale; the app warns well before that.
 */
class EetService(
    private val context: Context,
    private val dao: EetDao,
    private val settings: () -> AppSettings,
) {
    private val mutex = Mutex()
    private val certFile get() = File(context.filesDir, "eet/pokladni_certifikat.p12")
    private var cached: Pair<String, EetCertificate>? = null

    fun hasCertificate(): Boolean = certFile.exists()

    /** Loads the stored certificate, or returns null with no exception when none is set. */
    fun certificate(password: String = settings().eetCertPassword): EetCertificate? {
        if (!certFile.exists()) return null
        val key = "${certFile.lastModified()}|$password"
        cached?.let { if (it.first == key) return it.second }
        return runCatching { EetCertificate.fromPkcs12(certFile.readBytes(), password) }.getOrNull()
            ?.also { cached = key to it }
    }

    /** Checks the .p12 file and password, then stores the file in the app's private storage. */
    suspend fun importCertificate(bytes: ByteArray, password: String): EetCertificate = withContext(Dispatchers.IO) {
        val cert = EetCertificate.fromPkcs12(bytes, password)
        certFile.parentFile?.mkdirs()
        certFile.writeBytes(bytes)
        cached = null
        cert
    }

    /** Creates the PENDING record for a sale or storno. Returns null when EET is switched off. */
    suspend fun createRecord(saleId: Long, poradCis: String, saleTime: Long, amount: Long, storno: Boolean): EetRecordEntity? {
        val s = settings()
        if (!s.eetEnabled) return null
        val dat = EetFormat.dateTime(OffsetDateTime.ofInstant(Instant.ofEpochMilli(saleTime), EetFormat.PRAGUE))
        val r = EetRecordEntity(
            saleId = saleId, storno = storno, eic = s.effectiveEic,
            idJednotky = s.eetIdJednotky.toIntOrNull() ?: 0, idPokl = s.eetIdPokl.trim(),
            poradCis = poradCis, datTrzby = dat, saleTime = saleTime, amount = amount,
        )
        return r.copy(id = dao.insert(r))
    }

    /** Sends one record now and stores the outcome. */
    suspend fun send(recordId: Long): EetRecordEntity? = mutex.withLock {
        val r = dao.get(recordId) ?: return@withLock null
        if (r.status != EetStatus.PENDING) return@withLock r
        sendLocked(r)
    }

    /** Resends everything still waiting. Returns how many records are still pending afterwards. */
    suspend fun sendPending(): Int = mutex.withLock {
        var left = 0
        for (r in dao.pending()) {
            val after = sendLocked(r)
            if (after.status == EetStatus.PENDING) left++
        }
        left
    }

    private suspend fun sendLocked(r: EetRecordEntity): EetRecordEntity = withContext(Dispatchers.IO) {
        val s = settings()
        val cert = certificate()
        val now = System.currentTimeMillis()
        if (cert == null) {
            return@withContext r.copy(lastAttemptAt = now, lastError = tr("Chybí pokladní certifikát EET nebo heslo k němu."))
                .also { dao.update(it) }
        }
        val sale = EetSale(
            eicPopl = r.eic, idJednotky = r.idJednotky, idPokl = r.idPokl, poradCis = r.poradCis,
            datTrzby = OffsetDateTime.parse(r.datTrzby, DateTimeFormatter.ISO_OFFSET_DATE_TIME), amount = r.amount,
        )
        val attempt = EetClient(s.eetEnvironment, cert, s.eetTimeoutMs.coerceAtLeast(2000))
            .send(sale, firstAttempt = r.attempts == 0, verification = s.eetVerification)
        val base = r.copy(attempts = r.attempts + 1, lastAttemptAt = now, lastUuid = attempt.uuid.ifEmpty { r.lastUuid })
        val updated = when (val res = attempt.result) {
            is EetResult.Confirmed -> base.copy(
                status = EetStatus.CONFIRMED, pok = res.pok, test = res.test, lastError = null,
                warnings = res.warnings.joinToString("\n") { "${it.code}: ${it.text}" }.ifEmpty { null },
            )
            is EetResult.VerifiedOk -> base.copy(status = EetStatus.VERIFIED, lastError = null,
                warnings = res.warnings.joinToString("\n") { "${it.code}: ${it.text}" }.ifEmpty { null })
            is EetResult.Error -> base.copy(
                status = if (res.retryable) EetStatus.PENDING else EetStatus.REJECTED,
                lastError = (res.code?.let { "[$it] " } ?: "") + res.message,
                warnings = res.warnings.joinToString("\n") { "${it.code}: ${it.text}" }.ifEmpty { null },
            )
        }
        dao.update(updated)
        updated
    }

    /** Marks a rejected record for sending again, e.g. after the settings were corrected. */
    suspend fun requeue(r: EetRecordEntity) = mutex.withLock {
        dao.update(r.copy(status = EetStatus.PENDING))
    }

    /**
     * Sends a 1 Kč message in verification mode to check certificate, EIČ and connection.
     * Nothing is recorded by the tax administration.
     */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val s = settings()
        val cert = certificate() ?: return@withContext tr("Nejdřív nahrajte pokladní certifikát a zadejte heslo.")
        val sale = EetSale(
            eicPopl = s.effectiveEic, idJednotky = s.eetIdJednotky.toIntOrNull() ?: 0, idPokl = s.eetIdPokl.trim(),
            poradCis = "TEST-" + (System.currentTimeMillis() / 1000), datTrzby = OffsetDateTime.now(EetFormat.PRAGUE), amount = 100,
        )
        when (val r = EetClient(s.eetEnvironment, cert, s.eetTimeoutMs.coerceAtLeast(2000)).send(sale, true, verification = true).result) {
            is EetResult.VerifiedOk -> tr("Spojení s EET funguje, zpráva je v pořádku.") + warningsText(r.warnings)
            is EetResult.Confirmed -> tr("Spojení funguje, POK %s", r.pok) + warningsText(r.warnings)
            is EetResult.Error -> tr("Chyba: ") + (r.code?.let { "[$it] " } ?: "") + r.message + warningsText(r.warnings)
        }
    }

    private fun warningsText(w: List<cz.flos.core.eet.EetWarning>) =
        if (w.isEmpty()) "" else tr("\nVarování: ") + w.joinToString("; ") { "${it.code} ${it.text}" }

    companion object {
        /** Warn the cashier when a sale has waited this long; the legal limit is 48 hours. */
        const val WARN_AFTER_HOURS = 24L
    }
}
