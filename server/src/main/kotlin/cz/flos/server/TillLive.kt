package cz.flos.server

import cz.flos.core.GuestOrder
import cz.flos.core.MenuData
import cz.flos.core.ProductOptions
import cz.flos.core.WebCustomer
import cz.flos.core.WebOrder
import cz.flos.core.WebOrders
import cz.flos.core.backoffice.BoTillSnapshot
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable

/** The latest picture of one till, as the web admin shows it. */
@Serializable data class TillLiveDevice(val id: Long, val name: String, val lastSeenAt: Long? = null, val receivedAt: Long? = null, val snapshot: BoTillSnapshot? = null)

/** An order a guest sent from a table's QR code, with the dish names from the published menu. */
@Serializable data class TableOrderView(val id: Long, val table: String, val status: String, val createdAt: Long, val note: String = "", val lines: List<TableOrderLineView>)
@Serializable data class TableOrderLineView(val name: String, val qty: Int, val choices: String = "", val note: String = "")

/**
 * An order from the restaurant's website (delivery, pick-up or a table) and the till's answer: the confirmed
 * time ([readyAt]) or the refusal, with the staff's message to the customer ([reply]). The customer's name
 * is gone once the contact details were erased (after 30 days).
 */
@Serializable data class WebOrderView(
    val id: Long, val type: String, val status: String, val createdAt: Long, val readyAt: Long? = null, val reply: String? = null,
    val customer: String = "", val wish: String = "", val total: Long = 0, val lines: List<String> = emptyList(), val note: String = "", val table: String = "",
)

/** The till sends what is going on there; nothing in it can be changed from the back office. */
internal fun Route.deviceSnapshotRoutes(db: Db) {
    put("/device/snapshot") {
        val d = call.device(db)
        val text = call.receiveText()
        if (text.length > BoTillSnapshot.MAX_BYTES) throw ApiError(HttpStatusCode.PayloadTooLarge, "Příliš velký přehled pokladny")
        val s = runCatching { FlosJson.decodeFromString(BoTillSnapshot.serializer(), text) }.getOrNull()
            ?: throw ApiError(HttpStatusCode.BadRequest, "Poškozený přehled pokladny")
        val now = System.currentTimeMillis()
        // Kept within limits whatever the till sends.
        val clean = s.copy(
            takenAt = s.takenAt.coerceAtMost(now),
            openOrders = s.openOrders.take(BoTillSnapshot.MAX_LIST), shiftCloses = s.shiftCloses.take(60), cashMoves = s.cashMoves.take(BoTillSnapshot.MAX_LIST),
            customers = s.customers.take(BoTillSnapshot.MAX_CUSTOMERS), reservations = s.reservations.take(BoTillSnapshot.MAX_LIST),
            documents = s.documents.take(BoTillSnapshot.MAX_LIST), audit = s.audit.take(BoTillSnapshot.MAX_LIST),
        )
        val data = FlosJson.encodeToString(BoTillSnapshot.serializer(), clean)
        db.tx {
            val n = update("UPDATE till_snapshots SET data = ?, taken_at = ?, received_at = ? WHERE device_id = ? AND org_id = ?", data, clean.takenAt, now, d.deviceId, d.orgId)
            if (n == 0) update("INSERT INTO till_snapshots (device_id, org_id, data, taken_at, received_at) VALUES (?,?,?,?,?)", d.deviceId, d.orgId, data, clean.takenAt, now)
        }
        call.respond(Ok())
    }
}

/** Reading what the tills sent, and the orders from the tables, in the web admin. */
internal fun Route.tillLiveRoutes(db: Db) {
    get("/till-live") {
        val c = call.reader(db)
        call.respond(db.tx {
            query(
                """SELECT d.id, d.name, d.last_seen_at, s.received_at, s.data FROM devices d LEFT JOIN till_snapshots s ON s.device_id = d.id
                   WHERE d.org_id = ? AND d.active = TRUE ORDER BY d.id""", c.orgId,
            ) {
                val snap = it.getString(5)?.let { t -> runCatching { FlosJson.decodeFromString(BoTillSnapshot.serializer(), t) }.getOrNull() }
                TillLiveDevice(it.getLong(1), it.getString(2), it.longOrNull("last_seen_at"), it.longOrNull("received_at"), snap)
            }
        })
    }
    get("/web-orders") {
        val c = call.reader(db)
        val days = (call.request.queryParameters["days"]?.toIntOrNull() ?: 2).coerceIn(1, 31)
        val from = System.currentTimeMillis() - days * 86_400_000L
        call.respond(db.tx {
            query("SELECT id, status, created_at, ready_at, reply, data, customer FROM web_orders WHERE org_id = ? AND created_at > ? ORDER BY id DESC LIMIT 500", c.orgId, from) { r ->
                val o = runCatching { FlosJson.decodeFromString(WebOrder.serializer(), r.getString(6)) }.getOrNull()
                val who = r.getString(7)?.let { t -> runCatching { FlosJson.decodeFromString(WebCustomer.serializer(), t) }.getOrNull() }
                WebOrderView(
                    r.getLong(1), o?.type.orEmpty(), r.getString(2), r.getLong(3), r.longOrNull("ready_at"), r.getString(5),
                    who?.let { listOf(it.name, it.phone).filter(String::isNotBlank).joinToString(", ") }.orEmpty(),
                    o?.let { WebOrders.wishText(it) }.orEmpty(), o?.total ?: 0,
                    o?.lines.orEmpty().map { l -> "${l.qty}× ${l.number} ${l.name}" + (if (l.half != null) " / ${l.half} ${l.halfName}" else "") },
                    o?.note.orEmpty(), o?.table.orEmpty(),
                )
            }
        })
    }
    get("/table-orders") {
        val c = call.reader(db)
        val days = (call.request.queryParameters["days"]?.toIntOrNull() ?: 2).coerceIn(1, 31)
        val from = System.currentTimeMillis() - days * 86_400_000L
        call.respond(db.tx {
            val menu = queryOne("SELECT data FROM menus WHERE org_id = ?", c.orgId) { r -> runCatching { FlosJson.decodeFromString(MenuData.serializer(), r.getString(1)) }.getOrNull() }
            val items = menu?.categories?.flatMap { it.items }?.filter { it.id != 0L }?.associateBy { it.id }.orEmpty()
            query("SELECT id, table_name, status, created_at, data FROM table_orders WHERE org_id = ? AND created_at > ? ORDER BY id DESC LIMIT 500", c.orgId, from) { r ->
                val o = runCatching { FlosJson.decodeFromString(GuestOrder.serializer(), r.getString(5)) }.getOrNull()
                TableOrderView(r.getLong(1), r.getString(2), r.getString(3), r.getLong(4), o?.note.orEmpty(), o?.lines.orEmpty().map { l ->
                    val item = items[l.id]
                    val groups = item?.let { ProductOptions.parse(it.options) }.orEmpty()
                    val chosen = l.choices.flatMapIndexed { gi, picks -> picks.mapNotNull { groups.getOrNull(gi)?.choices?.getOrNull(it)?.name } }
                    TableOrderLineView(item?.name ?: "#${l.id}", l.qty, chosen.joinToString(", "), l.note)
                })
            }
        })
    }
}
