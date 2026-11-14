package cz.flos.server

import cz.flos.core.OpenTables
import cz.flos.core.OrderChannelStatus
import cz.flos.core.OrderControl
import cz.flos.core.OrderHours
import cz.flos.core.OrderStatus
import cz.flos.core.TableOrders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import java.sql.Connection

/** Open tables a till sent longer ago than this no longer count (the till is off or offline). */
private const val OPEN_TABLES_FRESH_MS = 15 * 60 * 1000L

/** The answer when orders are not taken now: the message for the customer, why, and when it opens again. */
@Serializable data class OrdersClosedBody(val error: String, val reason: String, val messageEn: String = "", val opensAt: Long? = null)

/** Thrown when an order comes while orders are stopped or outside the hours; answered with [OrdersClosedBody]. */
class OrdersClosed(val status: OrderChannelStatus) : RuntimeException(status.message)

/** The company's ordering hours and "stop orders" switch; the defaults (no limits) until a till sets them. */
internal fun Connection.orderControl(orgId: Long): OrderControl =
    queryOne("SELECT data FROM order_control WHERE org_id = ?", orgId) {
        runCatching { FlosJson.decodeFromString(OrderControl.serializer(), it.getString(1)) }.getOrNull()
    } ?: OrderControl()

/** True when a till of the company has an open bill on [table] (sent within the last minutes). */
internal fun Connection.tableHasOpenBill(orgId: Long, table: String, now: Long = System.currentTimeMillis()): Boolean =
    query("SELECT tables FROM open_tables WHERE org_id = ? AND updated_at > ?", orgId, now - OPEN_TABLES_FRESH_MS) { it.getString(1) }
        .any { row -> runCatching { FlosJson.decodeFromString(OpenTables.serializer(), row) }.getOrNull()?.tables?.any { it == table.trim() } == true }

/** Whether a guest at [table] may order from the table now. */
internal fun Connection.qrOrderStatus(orgId: Long, table: String?, now: Long = System.currentTimeMillis()): OrderChannelStatus {
    val c = orderControl(orgId)
    val open = OrderHours.qrStatus(c, now)
    if (open.open || open.reason != "hours" || !c.openTablesAfterHours || table.isNullOrBlank()) return open
    return OrderHours.qrStatus(c, now, tableHasOpenBill(orgId, table, now))
}

/** Whether the website may send delivery and pick-up orders now. */
internal fun Connection.webOrderStatus(orgId: Long, now: Long = System.currentTimeMillis()): OrderChannelStatus =
    OrderHours.webStatus(orderControl(orgId), now)

/** Turns an order away when [status] is closed. */
internal fun requireOpen(status: OrderChannelStatus) { if (!status.open) throw OrdersClosed(status) }

internal suspend fun ApplicationCall.respondClosed(e: OrdersClosed, en: Boolean = false) {
    val s = e.status
    response.headers.append("Cache-Control", "no-store")
    respond(HttpStatusCode.Forbidden, OrdersClosedBody(if (en) s.messageEn else s.message, s.reason, s.messageEn, s.opensAt))
}

/** Public: whether orders are taken now, for the restaurant's website and the guests' menu page. */
internal fun Route.orderStatusRoutes(db: Db, origins: List<String>) {
    options("/menu/{slug}/order-status") { call.allowOrigin(origins); call.respond(HttpStatusCode.NoContent) }
    get("/menu/{slug}/order-status") {
        call.allowOrigin(origins)
        call.response.headers.append("Cache-Control", "no-store")
        val slug = call.parameters["slug"].orEmpty()
        val now = System.currentTimeMillis()
        val status = db.tx {
            val orgId = queryOne("SELECT org_id FROM menus WHERE slug = ?", slug) { it.getLong(1) } ?: throw ApiError(HttpStatusCode.NotFound, "Menu nenalezeno")
            // With a table and its key (from the table's QR code) the answer is for that table (open bill after hours).
            val table = call.request.queryParameters["t"]?.trim()
            val key = call.request.queryParameters["k"]?.trim()
            val forTable = if (!table.isNullOrBlank() && !key.isNullOrBlank() && tableKeyValid(slug, table, key)) table else null
            OrderStatus(qrOrderStatus(orgId, forTable, now), webOrderStatus(orgId, now))
        }
        call.respond(status)
    }
}

/** For the till: read and set the ordering hours and "stop orders"; send the tables with an open bill. */
internal fun Route.orderControlDeviceRoutes(db: Db) {
    get("/device/order-control") {
        val d = call.device(db)
        call.respond(db.tx { orderControl(d.orgId) })
    }

    put("/device/order-control") {
        val d = call.device(db)
        val c = OrderHours.cleaned(call.receive<OrderControl>())
        OrderHours.problem(c.qrHours)?.let { throw ApiError(HttpStatusCode.BadRequest, it) }
        OrderHours.problem(c.webHours)?.let { throw ApiError(HttpStatusCode.BadRequest, it) }
        val now = System.currentTimeMillis()
        val data = FlosJson.encodeToString(OrderControl.serializer(), c)
        db.tx {
            val n = update("UPDATE order_control SET data = ?, updated_at = ? WHERE org_id = ?", data, now, d.orgId)
            if (n == 0) update("INSERT INTO order_control (org_id, data, updated_at) VALUES (?,?,?)", d.orgId, data, now)
        }
        call.respond(c)
    }

    put("/device/open-tables") {
        val d = call.device(db)
        val tables = call.receive<OpenTables>().tables.map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= TableOrders.MAX_TABLE }.distinct()
        if (tables.size > 500) throw ApiError(HttpStatusCode.BadRequest, "Nejvýše 500 stolů")
        val data = FlosJson.encodeToString(OpenTables.serializer(), OpenTables(tables))
        val now = System.currentTimeMillis()
        db.tx {
            val n = update("UPDATE open_tables SET tables = ?, updated_at = ? WHERE device_id = ? AND org_id = ?", data, now, d.deviceId, d.orgId)
            if (n == 0) update("INSERT INTO open_tables (device_id, org_id, tables, updated_at) VALUES (?,?,?,?)", d.deviceId, d.orgId, data, now)
        }
        call.respond(Ok())
    }
}
