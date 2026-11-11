package cz.flos.server

import cz.flos.core.backoffice.BoSettingChange
import cz.flos.core.backoffice.BoSettingsResult
import cz.flos.core.backoffice.BoSettingsUpload
import cz.flos.core.backoffice.TillSettingKeys
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import java.sql.Connection

/** The settings of the tills as the back office shows them: the company's and each till's own. */
@Serializable data class TillSettingsView(
    val deviceKeys: List<String>,
    val company: Map<String, BoSettingChange> = emptyMap(),
    val devices: List<TillSettingsDevice> = emptyList(),
)
@Serializable data class TillSettingsDevice(
    val id: Long, val name: String, val lastSeenAt: Long? = null,
    val values: Map<String, BoSettingChange> = emptyMap(),
    /** Secrets set on that till (names only). */
    val secrets: List<String> = emptyList(),
)
/** A change from the web: [deviceId] null = for the whole company. */
@Serializable data class TillSettingsWrite(val deviceId: Long? = null, val values: Map<String, String>)

internal object TillSettingsStore {
    private const val MAX_KEYS = 400

    fun check(key: String, value: String) {
        if (!TillSettingKeys.synced(key)) throw ApiError(HttpStatusCode.BadRequest, "Toto nastavení nelze měnit z back office: $key")
        if (value.length > TillSettingKeys.MAX_VALUE) throw ApiError(HttpStatusCode.BadRequest, "Příliš dlouhý text: $key")
    }

    fun scope(key: String, deviceId: Long): Long = if (key in TillSettingKeys.DEVICE) deviceId else 0

    /**
     * Writes one setting when it was changed later than the stored one (or nothing is stored yet).
     * [changedAt] is when it was changed; [now] is the server time used to send it to the other tills.
     */
    fun put(c: Connection, orgId: Long, deviceId: Long, key: String, value: String, changedAt: Long, now: Long) {
        val existing = c.queryOne("SELECT updated_at, svalue FROM till_settings WHERE org_id = ? AND device_id = ? AND skey = ?", orgId, deviceId, key) {
            it.getLong(1) to it.getString(2)
        }
        when {
            existing == null -> c.insert("INSERT INTO till_settings (org_id, device_id, skey, svalue, updated_at, written_at) VALUES (?,?,?,?,?,?)",
                orgId, deviceId, key, value, changedAt, now)
            changedAt > existing.first && value != existing.second ->
                c.update("UPDATE till_settings SET svalue = ?, updated_at = ?, written_at = ? WHERE org_id = ? AND device_id = ? AND skey = ?",
                    value, changedAt, now, orgId, deviceId, key)
            changedAt > existing.first -> c.update("UPDATE till_settings SET updated_at = ? WHERE org_id = ? AND device_id = ? AND skey = ?",
                changedAt, orgId, deviceId, key)
        }
    }

    fun read(c: Connection, orgId: Long, deviceId: Long, since: Long = 0): Map<String, BoSettingChange> =
        c.query("SELECT skey, svalue, updated_at FROM till_settings WHERE org_id = ? AND device_id = ? AND written_at >= ?", orgId, deviceId, since) {
            it.getString(1) to BoSettingChange(it.getString(2), it.getLong(3))
        }.toMap()

    /** What a till uses: the company's settings with its own (printer, language…) on top. */
    fun forDevice(c: Connection, orgId: Long, deviceId: Long, since: Long): Map<String, BoSettingChange> =
        read(c, orgId, 0, since).filterKeys { it !in TillSettingKeys.DEVICE } + read(c, orgId, deviceId, since).filterKeys { it in TillSettingKeys.DEVICE }

    fun limit(n: Int) { if (n > MAX_KEYS) throw ApiError(HttpStatusCode.BadRequest, "Příliš mnoho nastavení najednou") }
}

/** The till sends what changed on it and gets what changed in the back office; per setting, the later change wins. */
internal fun Route.deviceSettingsRoutes(db: Db) {
    post("/device/settings") {
        val d = call.device(db)
        val up = call.receive<BoSettingsUpload>()
        TillSettingsStore.limit(up.changes.size)
        val now = System.currentTimeMillis()
        val result = db.tx {
            for ((key, ch) in up.changes) {
                if (!TillSettingKeys.synced(key) || ch.value.length > TillSettingKeys.MAX_VALUE) continue // a newer till may know more
                TillSettingsStore.put(this, d.orgId, TillSettingsStore.scope(key, d.deviceId), key, ch.value, ch.changedAt.coerceAtMost(now), now)
            }
            val secrets = up.secrets.filter { it.value && it.key.length <= 40 }.keys.sorted().take(20).joinToString(",")
            update("UPDATE devices SET secrets_set = ? WHERE id = ? AND org_id = ?", secrets, d.deviceId, d.orgId)
            BoSettingsResult(now, TillSettingsStore.forDevice(this, d.orgId, d.deviceId, up.since))
        }
        call.respond(result)
    }
}

/** Seeing and changing the tills' settings in the web admin. */
internal fun Route.tillSettingsRoutes(db: Db) {
    get("/till-settings") {
        val c = call.reader(db)
        call.respond(db.tx {
            val devices = query("SELECT id, name, last_seen_at, secrets_set FROM devices WHERE org_id = ? AND active = TRUE ORDER BY id", c.orgId) {
                TillSettingsDevice(it.getLong(1), it.getString(2), it.longOrNull("last_seen_at"), secrets = it.getString(4).split(',').filter(String::isNotBlank))
            }.map { it.copy(values = TillSettingsStore.read(this, c.orgId, it.id)) }
            TillSettingsView(TillSettingKeys.DEVICE.sorted(), TillSettingsStore.read(this, c.orgId, 0), devices)
        })
    }
    put("/till-settings") {
        val c = call.writer(db)
        val w = call.receive<TillSettingsWrite>()
        TillSettingsStore.limit(w.values.size)
        w.values.forEach { (k, v) -> TillSettingsStore.check(k, v) }
        val now = System.currentTimeMillis()
        db.tx {
            if (w.deviceId != null && queryOne("SELECT 1 FROM devices WHERE id = ? AND org_id = ?", w.deviceId, c.orgId) { 1 } == null) {
                throw ApiError(HttpStatusCode.NotFound, "Pokladna nenalezena")
            }
            for ((k, v) in w.values) {
                val device = if (k in TillSettingKeys.DEVICE) w.deviceId ?: throw ApiError(HttpStatusCode.BadRequest, "Nastavení $k patří ke konkrétní pokladně") else 0
                TillSettingsStore.put(this, c.orgId, device, k, v, now, now)
            }
        }
        call.respond(Ok())
    }
}
