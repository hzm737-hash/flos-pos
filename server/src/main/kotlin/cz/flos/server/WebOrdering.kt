package cz.flos.server

import cz.flos.core.MenuData
import cz.flos.core.Notify
import cz.flos.core.WebCustomer
import cz.flos.core.WebOrder
import cz.flos.core.WebOrderAccepted
import cz.flos.core.WebOrderAck
import cz.flos.core.WebOrderInbox
import cz.flos.core.WebOrderList
import cz.flos.core.WebOrderStatus
import cz.flos.core.WebOrders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import java.security.MessageDigest
import java.sql.Connection

/** Web orders older than this are no longer handed to a till. */
private const val WEB_ORDER_TTL_MS = 6 * 60 * 60 * 1000L
/** A web order a till fetched but did not answer is handed out again after this. */
private const val WEB_REFETCH_MS = 60_000L
/** The customer's name, phone and address are erased this long after the order (see the privacy policy). */
const val WEB_CUSTOMER_KEEP_MS = 30L * 24 * 60 * 60 * 1000

/**
 * Websites allowed to send orders from the browser (CORS), from FLOS_WEB_ORIGINS, e.g.
 * "https://pizzafresca.cz,https://www.pizzafresca.cz". Empty: only pages on the back office's own address.
 */
fun webOriginsFromEnv(): List<String> =
    System.getenv("FLOS_WEB_ORIGINS").orEmpty().split(',').map { it.trim().trimEnd('/') }.filter { it.startsWith("https://") || it.startsWith("http://localhost") }

internal fun ApplicationCall.allowOrigin(origins: List<String>) {
    val origin = request.headers["Origin"]?.trimEnd('/') ?: return
    if (origin !in origins) return
    response.headers.append("Access-Control-Allow-Origin", origin)
    response.headers.append("Vary", "Origin")
    response.headers.append("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    response.headers.append("Access-Control-Allow-Headers", "Content-Type")
    response.headers.append("Access-Control-Max-Age", "600")
}

private data class WebMenu(val orgId: Long, val menu: MenuData?)

private fun Connection.webMenu(slug: String): WebMenu? =
    queryOne("SELECT org_id, data FROM menus WHERE slug = ?", slug) {
        WebMenu(it.getLong(1), runCatching { FlosJson.decodeFromString(MenuData.serializer(), it.getString(2)) }.getOrNull())
    }

/** Erases contact details of old web orders; the orders themselves stay for the records. */
internal fun Connection.eraseOldWebCustomers(now: Long = System.currentTimeMillis()) =
    update("UPDATE web_orders SET customer = NULL WHERE customer IS NOT NULL AND created_at < ?", now - WEB_CUSTOMER_KEEP_MS)

/** The public part: the website sends an order and asks for its answer. */
internal fun Route.webOrderRoutes(db: Db, origins: List<String>) {
    options("/menu/{slug}/web-order") { call.allowOrigin(origins); call.respond(HttpStatusCode.NoContent) }
    options("/menu/{slug}/web-order/{id}") { call.allowOrigin(origins); call.respond(HttpStatusCode.NoContent) }

    post("/menu/{slug}/web-order") {
        call.allowOrigin(origins)
        call.response.headers.append("Cache-Control", "no-store")
        val length = call.request.headers["Content-Length"]?.toLongOrNull()
        if (length != null && length > 64_000) throw ApiError(HttpStatusCode.PayloadTooLarge, "Objednávka je příliš velká")
        val order = WebOrders.cleaned(call.receive<WebOrder>())
        val row = db.tx { webMenu(call.parameters["slug"].orEmpty()) } ?: throw ApiError(HttpStatusCode.NotFound, "Menu nenalezeno")
        val en = order.lang == "en"
        val atTable = order.type == WebOrders.TABLE
        if (atTable) {
            // A guest at a table, on the website's menu opened by the table's QR code: needs QR ordering on.
            if (row.menu?.ordering != true) throw ApiError(HttpStatusCode.Forbidden, if (en) "Ordering from the table is switched off." else "Objednávky ze stolu jsou vypnuté.")
        } else if (row.menu?.webOrdering != true) throw ApiError(HttpStatusCode.Forbidden, if (en) "Online orders are switched off." else "Objednávky z webu jsou vypnuté.")
        WebOrders.problem(order)?.let { throw ApiError(HttpStatusCode.BadRequest, it) }
        if (atTable && !db.tx { tableKeyValid(call.parameters["slug"].orEmpty(), order.table, order.tableKey) }) {
            throw ApiError(HttpStatusCode.Forbidden, if (en) "Invalid table QR code. Please scan the code on the table again." else "Neplatný QR kód stolu. Naskenujte prosím kód na stole znovu.")
        }
        // Ordering hours and "stop orders" on the till: the table's guests follow the QR ones, the rest the web ones.
        val gate = db.tx { if (atTable) qrOrderStatus(row.orgId, order.table) else webOrderStatus(row.orgId) }
        if (!gate.open) return@post call.respondClosed(OrdersClosed(gate), en)
        val who = if (atTable) "web-table:${row.orgId}:${order.table}" else "web-phone:${row.orgId}:" + Notify.phoneDigits(order.customer!!.phone)!!
        if (!GuestThrottle.allow("web-ip:" + call.clientIp(), if (atTable) 10 else 6) || !GuestThrottle.allow(who, 6)) {
            throw ApiError(HttpStatusCode.TooManyRequests, if (en) "Too many orders. Please wait a moment or call us." else "Příliš mnoho objednávek. Chvíli počkejte nebo nám zavolejte.")
        }
        val now = System.currentTimeMillis()
        val token = Tokens.new("w_")
        val id = db.tx {
            eraseOldWebCustomers(now)
            val waiting = queryOne("SELECT COUNT(*) FROM web_orders WHERE org_id = ? AND status IN ('NEW','FETCHED') AND created_at > ?", row.orgId, now - WEB_ORDER_TTL_MS) { it.getLong(1) } ?: 0
            if (waiting >= 100) throw ApiError(HttpStatusCode.TooManyRequests, if (order.lang == "en") "Too many waiting orders. Please call us." else "Příliš mnoho čekajících objednávek. Zavolejte nám prosím.")
            insert("INSERT INTO web_orders (org_id, token, data, customer, status, created_at) VALUES (?,?,?,?,?,?)",
                row.orgId, token,
                FlosJson.encodeToString(WebOrder.serializer(), order.copy(customer = null, tableKey = "")),
                order.customer?.takeUnless { atTable }?.let { FlosJson.encodeToString(WebCustomer.serializer(), it) },
                "NEW", now)
        }
        call.respond(WebOrderAccepted(id, token))
    }

    /** The customer's page asks whether the order was accepted; only with the token it got when sending. */
    get("/menu/{slug}/web-order/{id}") {
        call.allowOrigin(origins)
        call.response.headers.append("Cache-Control", "no-store")
        val id = call.parameters["id"]?.toLongOrNull() ?: throw ApiError(HttpStatusCode.BadRequest, "Chybný parametr id")
        val key = call.request.queryParameters["k"].orEmpty()
        val row = db.tx { webMenu(call.parameters["slug"].orEmpty()) } ?: throw ApiError(HttpStatusCode.NotFound, "Nenalezeno")
        val found = db.tx {
            queryOne("SELECT token, status, ready_at FROM web_orders WHERE id = ? AND org_id = ?", id, row.orgId) {
                Triple(it.getString(1), it.getString(2), it.longOrNull("ready_at"))
            }
        }
        if (found == null || !MessageDigest.isEqual(found.first.toByteArray(), key.toByteArray())) throw ApiError(HttpStatusCode.NotFound, "Nenalezeno")
        call.respond(WebOrderStatus(if (found.second == "FETCHED") "NEW" else found.second, found.third))
    }
}

/** For the till: waiting web orders and the answer to each. */
internal fun Route.webOrderDeviceRoutes(db: Db) {
    get("/device/web-orders") {
        val d = call.device(db)
        val now = System.currentTimeMillis()
        val orders = db.tx {
            val list = query(
                "SELECT id, data, customer, created_at FROM web_orders WHERE org_id = ? AND created_at > ? AND " +
                    "(status = 'NEW' OR (status = 'FETCHED' AND fetched_at < ?)) ORDER BY id LIMIT 30",
                d.orgId, now - WEB_ORDER_TTL_MS, now - WEB_REFETCH_MS,
            ) { r ->
                val o = FlosJson.decodeFromString(WebOrder.serializer(), r.getString(2))
                val c = r.getString(3)?.let { FlosJson.decodeFromString(WebCustomer.serializer(), it) }
                WebOrderInbox(r.getLong(1), o.copy(customer = c), r.getLong(4))
            }
            list.forEach { update("UPDATE web_orders SET status = 'FETCHED', fetched_at = ? WHERE id = ?", now, it.id) }
            list
        }
        call.respond(WebOrderList(orders))
    }

    post("/device/web-orders/{id}/ack") {
        val d = call.device(db)
        val id = call.parameters["id"]?.toLongOrNull() ?: throw ApiError(HttpStatusCode.BadRequest, "Chybný parametr id")
        val ack = call.receive<WebOrderAck>()
        if (ack.accepted && ack.readyMinutes !in 1..240) throw ApiError(HttpStatusCode.BadRequest, "Neplatný čas přípravy")
        val readyAt = if (ack.accepted) System.currentTimeMillis() + ack.readyMinutes * 60_000L else null
        val changed = db.tx {
            update("UPDATE web_orders SET status = ?, ready_at = ? WHERE id = ? AND org_id = ? AND status IN ('NEW','FETCHED')",
                if (ack.accepted) "ACCEPTED" else "REJECTED", readyAt, id, d.orgId)
        }
        call.respond(Ok(changed > 0))
    }
}
