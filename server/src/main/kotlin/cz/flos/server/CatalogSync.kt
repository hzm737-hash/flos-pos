package cz.flos.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.security.SecureRandom
import java.sql.Connection
import java.util.Base64

/** Product photos: uploaded in the back office, shown on the tills and on the QR menu. */
internal object Images {
    const val MAX_BYTES = 1_500_000
    private val random = SecureRandom()
    private val REF = Regex("""^(\d{1,18})-([0-9a-f]{32})$""")

    /** The image type from its first bytes; only JPEG, PNG and WebP are kept. */
    fun sniff(b: ByteArray): String? = when {
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
        b.size > 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
        else -> null
    }

    fun save(c: Connection, orgId: Long, bytes: ByteArray): String {
        if (bytes.size > MAX_BYTES) throw ApiError(HttpStatusCode.PayloadTooLarge, "Fotka je příliš velká (nejvýše 1,5 MB)")
        val mime = sniff(bytes) ?: throw ApiError(HttpStatusCode.BadRequest, "Fotka musí být JPG, PNG nebo WebP")
        val key = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val id = c.insert("INSERT INTO images (org_id, img_key, mime, data, created_at) VALUES (?,?,?,?,?)",
            orgId, key, mime, Base64.getEncoder().encodeToString(bytes), System.currentTimeMillis())
        return "$id-$key"
    }

    fun belongs(c: Connection, orgId: Long, ref: String): Boolean {
        val m = REF.matchEntire(ref) ?: return false
        return c.queryOne("SELECT 1 FROM images WHERE id = ? AND img_key = ? AND org_id = ?", m.groupValues[1].toLong(), m.groupValues[2], orgId) { 1 } != null
    }

    fun load(c: Connection, ref: String): Pair<String, ByteArray>? {
        val m = REF.matchEntire(ref) ?: return null
        return c.queryOne("SELECT mime, data FROM images WHERE id = ? AND img_key = ?", m.groupValues[1].toLong(), m.groupValues[2]) {
            it.getString(1) to Base64.getDecoder().decode(it.getString(2))
        }
    }
}

/** Public photo by its unguessable reference; the photos are not secret, they are shown on the menu. */
internal fun Route.publicImageRoutes(db: Db) {
    get("/img/{ref}") {
        val (mime, bytes) = db.tx { Images.load(this, call.parameters["ref"].orEmpty()) }
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorBody("Nenalezeno"))
        call.response.headers.append("Cache-Control", "public, max-age=31536000, immutable")
        call.respondBytes(bytes, ContentType.parse(mime))
    }
}

/** Upload of a photo from the web admin (base64 in JSON, like the rest of the API). */
internal fun Route.imageRoutes(db: Db) {
    post("/images") {
        val c = call.writer(db)
        val up = call.receive<ImageUpload>()
        val bytes = runCatching { Base64.getDecoder().decode(up.data.substringAfter("base64,")) }.getOrNull()
            ?: throw ApiError(HttpStatusCode.BadRequest, "Poškozená fotka")
        call.respond(HttpStatusCode.Created, ImageRef(db.tx { Images.save(this, c.orgId, bytes) }))
    }
}

/**
 * Categories and products a till created or changed. The last change wins: a till's change is taken
 * when it was made after the back office's own last change of the same item.
 */
internal fun Route.deviceCatalogRoutes(db: Db) {
    post("/device/catalog") {
        val d = call.device(db)
        val up = call.receive<DeviceCatalogUpload>()
        if (up.categories.size > 500 || up.products.size > 5000) throw ApiError(HttpStatusCode.BadRequest, "Příliš mnoho položek najednou")
        val now = System.currentTimeMillis()
        val result = db.tx {
            val cats = mutableMapOf<Long, Long>()
            for (x in up.categories) {
                if (x.name.isBlank() && !x.deleted) continue
                val existing = x.remoteId?.let { id -> queryOne("SELECT updated_at FROM categories WHERE id = ? AND org_id = ?", id, d.orgId) { it.getLong(1) } }
                when {
                    x.remoteId == null || existing == null -> if (!x.deleted) {
                        cats[x.localId] = insert("INSERT INTO categories (org_id, name, color, sort_order, icon, updated_at) VALUES (?,?,?,?,?,?)",
                            d.orgId, x.name.take(200), x.color, x.sortOrder, x.icon.take(40), now)
                    }
                    x.updatedAt > existing -> {
                        if (x.deleted) {
                            update("UPDATE categories SET deleted = TRUE, updated_at = ? WHERE id = ? AND org_id = ?", now, x.remoteId, d.orgId)
                            update("UPDATE products SET category_id = NULL, updated_at = ? WHERE category_id = ? AND org_id = ?", now, x.remoteId, d.orgId)
                        } else {
                            update("UPDATE categories SET name = ?, color = ?, sort_order = ?, icon = ?, updated_at = ? WHERE id = ? AND org_id = ?",
                                x.name.take(200), x.color, x.sortOrder, x.icon.take(40), now, x.remoteId, d.orgId)
                        }
                        cats[x.localId] = x.remoteId
                    }
                    else -> cats[x.localId] = x.remoteId // the back office changed it later: keep that
                }
            }
            val prods = mutableMapOf<Long, Long>()
            for (x in up.products) {
                if (x.name.isBlank() || x.price < 0 || x.vatPercent !in listOf(0, 12, 21)) continue
                if (x.options.length > 4000 || x.prices.length > 2000) continue
                val category = x.categoryRemoteId?.takeIf { id -> queryOne("SELECT 1 FROM categories WHERE id = ? AND org_id = ?", id, d.orgId) { 1 } != null }
                val existing = x.remoteId?.let { id -> queryOne("SELECT updated_at FROM products WHERE id = ? AND org_id = ?", id, d.orgId) { it.getLong(1) } }
                when {
                    x.remoteId == null || existing == null -> prods[x.localId] = insert(
                        """INSERT INTO products (org_id, category_id, name, price, vat_percent, barcode, sort_order, active, option_text, price_lists, open_price, icon, updated_at)
                           VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                        d.orgId, category, x.name.take(200), x.price, x.vatPercent, x.barcode.take(60), x.sortOrder, x.active, x.options, x.prices, x.openPrice, x.icon.take(40), now,
                    )
                    x.updatedAt > existing -> {
                        update(
                            """UPDATE products SET category_id = ?, name = ?, price = ?, vat_percent = ?, barcode = ?, sort_order = ?, active = ?, option_text = ?,
                               price_lists = ?, open_price = ?, icon = ?, updated_at = ? WHERE id = ? AND org_id = ?""",
                            category, x.name.take(200), x.price, x.vatPercent, x.barcode.take(60), x.sortOrder, x.active, x.options, x.prices, x.openPrice,
                            x.icon.take(40), now, x.remoteId, d.orgId,
                        )
                        prods[x.localId] = x.remoteId
                    }
                    else -> prods[x.localId] = x.remoteId
                }
            }
            DeviceCatalogResult(cats, prods)
        }
        call.respond(result)
    }
}
