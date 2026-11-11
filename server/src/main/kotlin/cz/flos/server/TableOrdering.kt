package cz.flos.server

import cz.flos.core.GuestOrder
import cz.flos.core.GuestOrderAccepted
import cz.flos.core.MenuData
import cz.flos.core.TableLink
import cz.flos.core.TableLinkList
import cz.flos.core.TableLinkRequest
import cz.flos.core.TableOrder
import cz.flos.core.TableOrderAck
import cz.flos.core.TableOrderList
import cz.flos.core.TableOrders
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.security.SecureRandom
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

@Serializable data class GuestOrderStatus(val status: String)

/** Orders older than this are no longer handed to a till (the guests have long gone or asked the staff). */
private const val ORDER_TTL_MS = 3 * 60 * 60 * 1000L
/** An order a till fetched but did not answer is handed out again after this. */
private const val REFETCH_MS = 60_000L

/**
 * Limits on guest orders, kept in memory: per address 10 orders in 10 minutes, per table 6, and at most
 * 200 waiting orders per company. The table's signed key is the real protection; this stops a flood.
 */
internal object GuestThrottle {
    private const val WINDOW = 10 * 60 * 1000L
    private val hits = ConcurrentHashMap<String, MutableList<Long>>()

    fun allow(key: String, max: Int, now: Long = System.currentTimeMillis()): Boolean {
        val list = hits.computeIfAbsent(key) { mutableListOf() }
        synchronized(list) {
            list.removeAll { it < now - WINDOW }
            if (list.size >= max) return false
            list += now
        }
        if (hits.size > 50_000) hits.entries.removeIf { e -> synchronized(e.value) { e.value.none { it >= now - WINDOW } } }
        return true
    }

    fun clear() = hits.clear()
}

private data class MenuRow(val orgId: Long, val menu: MenuData?, val secret: String?)

private fun Connection.menuBySlug(slug: String): MenuRow? =
    queryOne("SELECT org_id, data, table_secret FROM menus WHERE slug = ?", slug) {
        MenuRow(it.getLong(1), runCatching { FlosJson.decodeFromString(MenuData.serializer(), it.getString(2)) }.getOrNull(), it.getString(3))
    }

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** The company's secret for signing table QR codes, made the first time it is needed. */
private fun Connection.tableSecret(orgId: Long): ByteArray {
    queryOne("SELECT table_secret FROM menus WHERE org_id = ?", orgId) { it.getString(1) }?.let { return unhex(it) }
    val fresh = hex(ByteArray(32).also { SecureRandom().nextBytes(it) })
    update("UPDATE menus SET table_secret = ? WHERE org_id = ? AND table_secret IS NULL", fresh, orgId)
    return unhex(queryOne("SELECT table_secret FROM menus WHERE org_id = ?", orgId) { it.getString(1) }!!)
}

private fun validKey(row: MenuRow, table: String?, key: String?): Boolean =
    !table.isNullOrBlank() && !key.isNullOrBlank() && row.secret != null && TableOrders.keyMatches(unhex(row.secret), table, key)

/** The public menu page (with ordering when the QR code of a table opened it) and the guest order API. */
internal fun Route.menuRoutes(db: Db) {
    get("/menu/{slug}") {
        val slug = call.parameters["slug"].orEmpty()
        val row = db.tx { menuBySlug(slug) }
        val menu = row?.menu ?: return@get call.respondText("Menu nenalezeno", status = HttpStatusCode.NotFound)
        val table = call.request.queryParameters["t"]?.trim()
        val key = call.request.queryParameters["k"]?.trim()
        val ordering = menu.ordering && validKey(row, table, key)
        if (ordering) {
            call.response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src data: 'self'; script-src 'self'; connect-src 'self'")
            call.response.headers.append("Cache-Control", "no-store")
        } else {
            call.response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src data: 'self'")
            call.response.headers.append("Cache-Control", "public, max-age=60")
        }
        val html = if (ordering) cz.flos.core.MenuPage.html(menu, table, key, slug) else cz.flos.core.MenuPage.html(menu)
        call.respondText(html, ContentType.Text.Html.withCharset(Charsets.UTF_8))
    }

    post("/menu/{slug}/order") {
        val slug = call.parameters["slug"].orEmpty()
        val length = call.request.headers["Content-Length"]?.toLongOrNull()
        if (length != null && length > 32_000) throw ApiError(HttpStatusCode.PayloadTooLarge, "Objednávka je příliš velká")
        val order = call.receive<GuestOrder>()
        val row = db.tx { menuBySlug(slug) } ?: throw ApiError(HttpStatusCode.NotFound, "Menu nenalezeno")
        val menu = row.menu ?: throw ApiError(HttpStatusCode.NotFound, "Menu nenalezeno")
        if (!validKey(row, order.table, order.key)) throw ApiError(HttpStatusCode.Forbidden, "Neplatný QR kód stolu. Naskenujte prosím kód na stole znovu.")
        TableOrders.problem(menu, order)?.let { throw ApiError(HttpStatusCode.BadRequest, it) }
        if (!GuestThrottle.allow("ip:" + call.clientIp(), 10) || !GuestThrottle.allow("table:${row.orgId}:${order.table.trim()}", 6)) {
            throw ApiError(HttpStatusCode.TooManyRequests, "Příliš mnoho objednávek. Chvíli počkejte nebo zavolejte obsluhu.")
        }
        val clean = order.copy(
            table = order.table.trim(), key = "",
            note = TableOrders.cleanNote(order.note),
            lines = order.lines.map { it.copy(note = TableOrders.cleanNote(it.note)) },
        )
        val now = System.currentTimeMillis()
        val id = db.tx {
            val waiting = queryOne("SELECT COUNT(*) FROM table_orders WHERE org_id = ? AND status IN ('NEW','FETCHED') AND created_at > ?", row.orgId, now - ORDER_TTL_MS) { it.getLong(1) } ?: 0
            if (waiting >= 200) throw ApiError(HttpStatusCode.TooManyRequests, "Příliš mnoho čekajících objednávek. Zavolejte prosím obsluhu.")
            insert("INSERT INTO table_orders (org_id, table_name, data, status, created_at) VALUES (?,?,?,?,?)",
                row.orgId, clean.table, FlosJson.encodeToString(GuestOrder.serializer(), clean), "NEW", now)
        }
        call.respond(GuestOrderAccepted(id))
    }

    /** The guest's page asks whether the staff accepted the order; only with the same table's key. */
    get("/menu/{slug}/order/{id}") {
        val slug = call.parameters["slug"].orEmpty()
        val id = call.parameters["id"]?.toLongOrNull() ?: throw ApiError(HttpStatusCode.BadRequest, "Chybný parametr id")
        val table = call.request.queryParameters["t"]?.trim()
        val row = db.tx { menuBySlug(slug) } ?: throw ApiError(HttpStatusCode.NotFound, "Nenalezeno")
        if (!validKey(row, table, call.request.queryParameters["k"])) throw ApiError(HttpStatusCode.Forbidden, "Neplatný QR kód stolu")
        val status = db.tx {
            queryOne("SELECT status FROM table_orders WHERE id = ? AND org_id = ? AND table_name = ?", id, row.orgId, table) { it.getString(1) }
        } ?: throw ApiError(HttpStatusCode.NotFound, "Nenalezeno")
        call.response.headers.append("Cache-Control", "no-store")
        call.respond(GuestOrderStatus(if (status == "FETCHED") "NEW" else status))
    }
}

/** For the till: links for the tables' QR codes, waiting orders, and the answer to each. */
internal fun Route.tableOrderDeviceRoutes(db: Db) {
    post("/device/table-links") {
        val d = call.device(db)
        val req = call.receive<TableLinkRequest>()
        val tables = req.tables.map { it.trim() }.filter { it.isNotEmpty() && it.length <= TableOrders.MAX_TABLE }.distinct()
        if (tables.size > 500) throw ApiError(HttpStatusCode.BadRequest, "Nejvýše 500 stolů")
        val links = db.tx {
            val slug = queryOne("SELECT slug FROM menus WHERE org_id = ?", d.orgId) { it.getString(1) }
                ?: throw ApiError(HttpStatusCode.Conflict, "Nejdřív zveřejněte QR menu")
            val secret = tableSecret(d.orgId)
            tables.map { t ->
                TableLink(t, "/menu/$slug?t=" + URLEncoder.encode(t, Charsets.UTF_8).replace("+", "%20") + "&k=" + TableOrders.key(secret, t))
            }
        }
        call.respond(TableLinkList(links))
    }

    get("/device/table-orders") {
        val d = call.device(db)
        val now = System.currentTimeMillis()
        val orders = db.tx {
            val list = query(
                "SELECT id, table_name, data, created_at FROM table_orders WHERE org_id = ? AND created_at > ? AND " +
                    "(status = 'NEW' OR (status = 'FETCHED' AND fetched_at < ?)) ORDER BY id LIMIT 50",
                d.orgId, now - ORDER_TTL_MS, now - REFETCH_MS,
            ) { r ->
                val o = FlosJson.decodeFromString(GuestOrder.serializer(), r.getString(3))
                TableOrder(r.getLong(1), r.getString(2), o.lines, o.note, r.getLong(4))
            }
            list.forEach { update("UPDATE table_orders SET status = 'FETCHED', fetched_at = ? WHERE id = ?", now, it.id) }
            list
        }
        call.respond(TableOrderList(orders))
    }

    post("/device/table-orders/{id}/ack") {
        val d = call.device(db)
        val id = call.parameters["id"]?.toLongOrNull() ?: throw ApiError(HttpStatusCode.BadRequest, "Chybný parametr id")
        val ack = call.receive<TableOrderAck>()
        val changed = db.tx {
            update("UPDATE table_orders SET status = ? WHERE id = ? AND org_id = ? AND status IN ('NEW','FETCHED')",
                if (ack.accepted) "ACCEPTED" else "REJECTED", id, d.orgId)
        }
        call.respond(Ok(changed > 0))
    }
}
