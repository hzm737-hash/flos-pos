package cz.flos.server

import cz.flos.core.MenuData
import cz.flos.core.Notify
import cz.flos.core.WebOrders
import cz.flos.core.WebReservation
import cz.flos.core.WebReservationAccepted
import cz.flos.core.WebReservationAck
import cz.flos.core.WebReservationInbox
import cz.flos.core.WebReservationInfo
import cz.flos.core.WebReservationList
import cz.flos.core.WebReservationStatus
import cz.flos.core.WebReservations
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.sql.Connection

/** A reservation a till fetched but did not answer is handed out again after this. */
private const val RES_REFETCH_MS = 60_000L

/** The customer's name, phone and e-mail as kept apart from the reservation (erased after 30 days). */
@Serializable data class WebReservationContact(val name: String, val phone: String, val email: String = "")

/** A web reservation in the web admin. */
@Serializable data class WebReservationView(
    val id: Long, val status: String, val createdAt: Long, val at: Long, val guests: Int,
    val customer: String = "", val note: String = "", val table: String = "", val reply: String? = null,
)

private fun Connection.reservationMenu(slug: String): Pair<Long, MenuData?>? =
    queryOne("SELECT org_id, data FROM menus WHERE slug = ?", slug) {
        it.getLong(1) to runCatching { FlosJson.decodeFromString(MenuData.serializer(), it.getString(2)) }.getOrNull()
    }

/** Erases contact details of old web reservations; the reservations themselves stay for the records. */
internal fun Connection.eraseOldReservationContacts(now: Long = System.currentTimeMillis()) =
    update("UPDATE web_reservations SET customer = NULL WHERE customer IS NOT NULL AND created_at < ?", now - WEB_CUSTOMER_KEEP_MS)

/** The public part: the website asks for the rules, sends a reservation and asks for its answer. */
internal fun Route.webReservationRoutes(db: Db, origins: List<String>) {
    options("/menu/{slug}/reservation") { call.allowOrigin(origins); call.respond(HttpStatusCode.NoContent) }
    options("/menu/{slug}/reservation/{id}") { call.allowOrigin(origins); call.respond(HttpStatusCode.NoContent) }
    options("/menu/{slug}/reservation-info") { call.allowOrigin(origins); call.respond(HttpStatusCode.NoContent) }

    get("/menu/{slug}/reservation-info") {
        call.allowOrigin(origins)
        call.response.headers.append("Cache-Control", "no-store")
        val row = db.tx { reservationMenu(call.parameters["slug"].orEmpty()) } ?: throw ApiError(HttpStatusCode.NotFound, "Menu nenalezeno")
        val rules = row.second?.reservations?.let { WebReservations.cleanRules(it) }
        call.respond(WebReservationInfo(rules != null, rules))
    }

    post("/menu/{slug}/reservation") {
        call.allowOrigin(origins)
        call.response.headers.append("Cache-Control", "no-store")
        val length = call.request.headers["Content-Length"]?.toLongOrNull()
        if (length != null && length > 8_000) throw ApiError(HttpStatusCode.PayloadTooLarge, "Rezervace je příliš velká")
        val r = WebReservations.cleaned(call.receive<WebReservation>())
        val en = r.lang == "en"
        val row = db.tx { reservationMenu(call.parameters["slug"].orEmpty()) } ?: throw ApiError(HttpStatusCode.NotFound, "Menu nenalezeno")
        val rules = row.second?.reservations?.let { WebReservations.cleanRules(it) }
            ?: throw ApiError(HttpStatusCode.Forbidden, if (en) "Online reservations are switched off. Please call us." else "Rezervace z webu jsou vypnuté. Zavolejte nám prosím.")
        WebReservations.problem(r, rules)?.let { throw ApiError(HttpStatusCode.BadRequest, it) }
        val orgId = row.first
        val who = "res-phone:$orgId:" + Notify.phoneDigits(r.phone)!!
        if (!GuestThrottle.allow("res-ip:" + call.clientIp(), 5) || !GuestThrottle.allow(who, 3)) {
            throw ApiError(HttpStatusCode.TooManyRequests, if (en) "Too many reservations. Please wait a moment or call us." else "Příliš mnoho rezervací. Chvíli počkejte nebo nám zavolejte.")
        }
        val now = System.currentTimeMillis()
        val at = WebReservations.startMillis(r)!!
        val token = Tokens.new("r_")
        val id = db.tx {
            eraseOldReservationContacts(now)
            val waiting = queryOne("SELECT COUNT(*) FROM web_reservations WHERE org_id = ? AND status IN ('NEW','FETCHED') AND at > ?", orgId, now) { it.getLong(1) } ?: 0
            if (waiting >= 100) throw ApiError(HttpStatusCode.TooManyRequests, if (en) "Too many waiting reservations. Please call us." else "Příliš mnoho čekajících rezervací. Zavolejte nám prosím.")
            insert("INSERT INTO web_reservations (org_id, token, data, customer, status, at, created_at) VALUES (?,?,?,?,?,?,?)",
                orgId, token,
                FlosJson.encodeToString(WebReservation.serializer(), r.copy(name = "", phone = "", email = "")),
                FlosJson.encodeToString(WebReservationContact.serializer(), WebReservationContact(r.name, r.phone, r.email)),
                "NEW", at, now)
        }
        call.respond(WebReservationAccepted(id, token))
    }

    /** The customer's page asks whether the reservation was confirmed; only with the token it got when sending. */
    get("/menu/{slug}/reservation/{id}") {
        call.allowOrigin(origins)
        call.response.headers.append("Cache-Control", "no-store")
        val id = call.parameters["id"]?.toLongOrNull() ?: throw ApiError(HttpStatusCode.BadRequest, "Chybný parametr id")
        val key = call.request.queryParameters["k"].orEmpty()
        val row = db.tx { reservationMenu(call.parameters["slug"].orEmpty()) } ?: throw ApiError(HttpStatusCode.NotFound, "Nenalezeno")
        val found = db.tx {
            queryOne("SELECT token, status, at, reply FROM web_reservations WHERE id = ? AND org_id = ?", id, row.first) {
                listOf(it.getString(1), it.getString(2), it.getLong(3).toString(), it.getString(4))
            }
        }
        if (found == null || !MessageDigest.isEqual(found[0]!!.toByteArray(), key.toByteArray())) throw ApiError(HttpStatusCode.NotFound, "Nenalezeno")
        val status = if (found[1] == "FETCHED") "NEW" else found[1]!!
        call.respond(WebReservationStatus(status, found[2]!!.toLong(), found[3]?.takeIf { it.isNotBlank() }))
    }
}

/** For the till: waiting web reservations and the answer to each. */
internal fun Route.webReservationDeviceRoutes(db: Db) {
    get("/device/web-reservations") {
        val d = call.device(db)
        val now = System.currentTimeMillis()
        val list = db.tx {
            val list = query(
                "SELECT id, data, customer, created_at FROM web_reservations WHERE org_id = ? AND at > ? AND " +
                    "(status = 'NEW' OR (status = 'FETCHED' AND fetched_at < ?)) ORDER BY id LIMIT 30",
                d.orgId, now, now - RES_REFETCH_MS,
            ) { r ->
                val res = FlosJson.decodeFromString(WebReservation.serializer(), r.getString(2))
                val c = r.getString(3)?.let { FlosJson.decodeFromString(WebReservationContact.serializer(), it) }
                WebReservationInbox(r.getLong(1), res.copy(name = c?.name.orEmpty(), phone = c?.phone.orEmpty(), email = c?.email.orEmpty()), r.getLong(4))
            }
            list.forEach { update("UPDATE web_reservations SET status = 'FETCHED', fetched_at = ? WHERE id = ?", now, it.id) }
            list
        }
        call.respond(WebReservationList(list))
    }

    post("/device/web-reservations/{id}/ack") {
        val d = call.device(db)
        val id = call.parameters["id"]?.toLongOrNull() ?: throw ApiError(HttpStatusCode.BadRequest, "Chybný parametr id")
        val ack = call.receive<WebReservationAck>()
        val reply = WebOrders.cleanReply(ack.message).ifEmpty { null }
        val table = ack.table.filter { it >= ' ' }.trim().take(40).ifEmpty { null }
        val changed = db.tx {
            update("UPDATE web_reservations SET status = ?, table_name = ?, reply = ? WHERE id = ? AND org_id = ? AND status IN ('NEW','FETCHED')",
                if (ack.accepted) "ACCEPTED" else "REJECTED", table, reply, id, d.orgId)
        }
        call.respond(Ok(changed > 0))
    }
}

/** The web admin: reservations from the website and the till's answers. */
internal fun Route.webReservationAdminRoutes(db: Db) {
    get("/web-reservations") {
        val c = call.reader(db)
        val days = (call.request.queryParameters["days"]?.toIntOrNull() ?: 14).coerceIn(1, 90)
        val from = System.currentTimeMillis() - days * 86_400_000L
        call.respond(db.tx {
            query("SELECT id, status, created_at, at, data, customer, table_name, reply FROM web_reservations WHERE org_id = ? AND created_at > ? ORDER BY at DESC LIMIT 500", c.orgId, from) { r ->
                val res = runCatching { FlosJson.decodeFromString(WebReservation.serializer(), r.getString(5)) }.getOrNull()
                val who = r.getString(6)?.let { t -> runCatching { FlosJson.decodeFromString(WebReservationContact.serializer(), t) }.getOrNull() }
                WebReservationView(
                    r.getLong(1), r.getString(2), r.getLong(3), r.getLong(4), res?.guests ?: 0,
                    who?.let { listOf(it.name, it.phone, it.email).filter(String::isNotBlank).joinToString(", ") }.orEmpty(),
                    res?.note.orEmpty(), r.getString(7).orEmpty(), r.getString(8),
                )
            }
        })
    }
}
