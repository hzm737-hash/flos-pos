package cz.flos.server

import cz.flos.core.backoffice.BoUpdateInfo
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom

@Serializable data class AppReleaseDto(
    val id: Long, val platform: String, val versionCode: Int, val versionName: String, val notes: String = "",
    val size: Long, val sha256: String, val createdAt: Long, val active: Boolean = true,
    /** Tills that get it; empty = all. */
    val devices: List<Long> = emptyList(),
)
@Serializable data class AppReleaseWrite(val active: Boolean = true, val devices: List<Long> = emptyList(), val notes: String = "")
@Serializable data class AppDeviceVersion(val id: Long, val name: String, val platform: String? = null, val versionCode: Int? = null, val versionName: String? = null, val lastSeenAt: Long? = null)
@Serializable data class AppReleasesView(val releases: List<AppReleaseDto>, val devices: List<AppDeviceVersion>)

internal object AppReleases {
    const val MAX_BYTES = 150 * 1024 * 1024
    val PLATFORMS = setOf("android")
    private val random = SecureRandom()

    fun key(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** An APK is a zip file with the app's manifest inside. */
    fun looksLikeApk(b: ByteArray): Boolean =
        b.size > 1000 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2] == 3.toByte() && b[3] == 4.toByte() &&
            runCatching {
                java.util.zip.ZipInputStream(b.inputStream()).use { z -> generateSequence { z.nextEntry }.any { it.name == "AndroidManifest.xml" } }
            }.getOrDefault(false)

    fun devices(s: String): List<Long> = s.split(',').mapNotNull { it.trim().toLongOrNull() }

    fun list(c: java.sql.Connection, orgId: Long): List<AppReleaseDto> =
        c.query("SELECT id, platform, version_code, version_name, notes, size, sha256, created_at, active, devices FROM app_releases WHERE org_id = ? ORDER BY version_code DESC, id DESC", orgId) {
            AppReleaseDto(it.getLong(1), it.getString(2), it.getInt(3), it.getString(4), it.getString(5), it.getLong(6), it.getString(7), it.getLong(8), it.getBoolean(9), devices(it.getString(10)))
        }
}

/** Uploading new app versions and choosing which tills get them (owners and managers, or the build with a write API key). */
internal fun Route.appReleaseRoutes(db: Db) {
    get("/app-releases") {
        val c = call.reader(db)
        call.respond(db.tx {
            AppReleasesView(AppReleases.list(this, c.orgId), query(
                "SELECT id, name, platform, version_code, app_version, last_seen_at FROM devices WHERE org_id = ? AND active = TRUE ORDER BY id", c.orgId,
            ) { AppDeviceVersion(it.getLong(1), it.getString(2), it.getString(3), it.intOrNull("version_code"), it.getString(5), it.longOrNull("last_seen_at")) })
        })
    }
    post("/app-releases") {
        val c = call.writer(db)
        val q = call.request.queryParameters
        val platform = q["platform"] ?: "android"
        if (platform !in AppReleases.PLATFORMS) throw ApiError(HttpStatusCode.BadRequest, "Zatím lze nahrát jen aplikaci pro Android")
        val name = q["versionName"]?.trim().orEmpty().take(40)
        val code = q["versionCode"]?.toIntOrNull() ?: Regex("""(\d+)$""").find(name)?.value?.toIntOrNull()
            ?: throw ApiError(HttpStatusCode.BadRequest, "Zadejte číslo verze (např. 1.0.123)")
        if (code !in 1..2_000_000_000) throw ApiError(HttpStatusCode.BadRequest, "Neplatné číslo verze")
        val bytes = call.receiveChannel().readRemaining(AppReleases.MAX_BYTES + 1L).readByteArray()
        if (bytes.size > AppReleases.MAX_BYTES) throw ApiError(HttpStatusCode.PayloadTooLarge, "Soubor je příliš velký")
        if (!AppReleases.looksLikeApk(bytes)) throw ApiError(HttpStatusCode.BadRequest, "Soubor není aplikace pro Android (APK)")
        val now = System.currentTimeMillis()
        val id = db.tx {
            // The same version uploaded again replaces the old file.
            update("DELETE FROM app_releases WHERE org_id = ? AND platform = ? AND version_code = ?", c.orgId, platform, code)
            insert("INSERT INTO app_releases (org_id, platform, version_code, version_name, notes, size, sha256, dl_key, data, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                c.orgId, platform, code, name.ifBlank { code.toString() }, q["notes"].orEmpty().take(2000), bytes.size.toLong(), AppReleases.sha256(bytes), AppReleases.key(), bytes, now)
        }
        call.respond(HttpStatusCode.Created, db.tx { AppReleases.list(this, c.orgId).first { it.id == id } })
    }
    put("/app-releases/{id}") {
        val c = call.writer(db)
        val w = call.receive<AppReleaseWrite>()
        val id = call.parameters["id"]?.toLongOrNull() ?: throw ApiError(HttpStatusCode.BadRequest, "Chybný parametr id")
        val n = db.tx {
            val own = query("SELECT id FROM devices WHERE org_id = ?", c.orgId) { it.getLong(1) }.toSet()
            val chosen = w.devices.filter { it in own }.distinct().take(200).joinToString(",")
            update("UPDATE app_releases SET active = ?, devices = ?, notes = ? WHERE id = ? AND org_id = ?", w.active, chosen, w.notes.take(2000), id, c.orgId)
        }
        if (n == 0) throw ApiError(HttpStatusCode.NotFound, "Verze nenalezena")
        call.respond(Ok())
    }
    delete("/app-releases/{id}") {
        val c = call.writer(db)
        val id = call.parameters["id"]?.toLongOrNull() ?: throw ApiError(HttpStatusCode.BadRequest, "Chybný parametr id")
        db.tx { update("DELETE FROM app_releases WHERE id = ? AND org_id = ?", id, c.orgId) }
        call.respond(Ok())
    }

    /** The till says which version it runs and gets the newest version meant for it, if newer. */
    get("/device/update") {
        val d = call.device(db)
        val q = call.request.queryParameters
        val platform = q["platform"].orEmpty().take(20)
        val code = q["code"]?.toIntOrNull() ?: 0
        val info = db.tx {
            update("UPDATE devices SET platform = ?, version_code = ?, app_version = ? WHERE id = ? AND org_id = ?",
                platform, code, q["name"].orEmpty().take(40), d.deviceId, d.orgId)
            query("SELECT id, version_code, version_name, notes, size, sha256, dl_key, devices FROM app_releases WHERE org_id = ? AND platform = ? AND active = TRUE AND version_code > ? ORDER BY version_code DESC",
                d.orgId, platform, code) { r ->
                val targets = AppReleases.devices(r.getString(8))
                if (targets.isNotEmpty() && d.deviceId !in targets) null
                else BoUpdateInfo(true, r.getInt(2), r.getString(3), r.getString(4), r.getLong(5), r.getString(6), "/dl/app/${r.getLong(1)}/${r.getString(7)}/flos.apk")
            }.firstNotNullOfOrNull { it }
        }
        call.respond(info ?: BoUpdateInfo())
    }
}

/** The download itself, by its unguessable link, so the phone's browser can fetch it without signing in. */
internal fun Route.appDownloadRoutes(db: Db) {
    get("/dl/app/{id}/{key}/flos.apk") {
        val id = call.parameters["id"]?.toLongOrNull()
        val key = call.parameters["key"].orEmpty()
        val bytes = if (id == null || !Regex("^[0-9a-f]{32}$").matches(key)) null
        else db.tx { queryOne("SELECT data FROM app_releases WHERE id = ? AND dl_key = ? AND active = TRUE", id, key) { it.getBytes(1) } }
        if (bytes == null) return@get call.respond(HttpStatusCode.NotFound, ErrorBody("Nenalezeno"))
        call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "flos.apk").toString())
        call.respondBytes(bytes, ContentType.parse("application/vnd.android.package-archive"))
    }
}
