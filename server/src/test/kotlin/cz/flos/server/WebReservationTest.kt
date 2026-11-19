package cz.flos.server

import cz.flos.core.MenuData
import cz.flos.core.OrderHours
import cz.flos.core.ReservationRules
import cz.flos.core.WebReservation
import cz.flos.core.WebReservationAccepted
import cz.flos.core.WebReservationAck
import cz.flos.core.WebReservationInfo
import cz.flos.core.WebReservationList
import cz.flos.core.WebReservationStatus
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WebReservationTest {
    private val site = "https://pizzafresca.cz"
    private lateinit var db: Db
    private var owner = ""

    private fun run(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        GuestThrottle.clear()
        db = Db.inMemory().also { it.migrate() }
        application { flosModule(db, openSignup = true, webOrigins = listOf(site)) }
        block(createClient { install(ContentNegotiation) { json(FlosJson) } })
    }

    private suspend fun HttpClient.postJson(path: String, body: Any, token: String? = null, origin: String? = null): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        token?.let { bearerAuth(it) }
        origin?.let { header("Origin", it) }
        setBody(body)
    }

    private suspend fun HttpClient.setUp(rules: ReservationRules? = ReservationRules(from = "00:00", to = "23:59", noticeMinutes = 0)): Pair<String, String> {
        val token = postJson("/api/v1/auth/register", RegisterRequest("Pizza Fresca", "Hazem", "majitel@flos.cz", "tajneheslo1")).body<TokenResponse>().token
        owner = token
        val branch = get("/api/v1/branches") { bearerAuth(token) }.body<List<BranchDto>>().single()
        val device = postJson("/api/v1/devices", DeviceDto(branchId = branch.id, name = "P1"), token).body<DeviceDto>()
        val dt = postJson("/api/v1/device/pair", PairRequest(device.pairingCode!!)).body<PairResponse>().token
        val path = put("/api/v1/device/menu") { bearerAuth(dt); contentType(ContentType.Application.Json); setBody(MenuData("Pizza Fresca", reservations = rules)) }.body<MenuPublished>().path
        return dt to path.removePrefix("/menu/")
    }

    private val tomorrow = LocalDate.now(OrderHours.ZONE).plusDays(1).toString()
    private val res = WebReservation("Jan Novák", "777 123 456", "jan@example.cz", 4, tomorrow, "19:30", "u okna", agreedTerms = true)

    @Test fun websiteBooksAndTillConfirms() = run { client ->
        val (dt, slug) = client.setUp()
        val pre = client.options("/menu/$slug/reservation") { header("Origin", site); header("Access-Control-Request-Method", "POST") }
        assertEquals(site, pre.headers["Access-Control-Allow-Origin"])

        val info = client.get("/menu/$slug/reservation-info") { header("Origin", site) }.body<WebReservationInfo>()
        assertTrue(info.open)
        assertEquals(8, info.rules!!.maxGuests)

        assertEquals(HttpStatusCode.BadRequest, client.postJson("/menu/$slug/reservation", res.copy(guests = 20), origin = site).status)
        assertEquals(HttpStatusCode.NotFound, client.postJson("/menu/nic/reservation", res, origin = site).status)
        val sent = client.postJson("/menu/$slug/reservation", res, origin = site)
        assertEquals(HttpStatusCode.OK, sent.status)
        val acc = sent.body<WebReservationAccepted>()
        assertEquals("NEW", client.get("/menu/$slug/reservation/${acc.id}?k=${acc.token}").body<WebReservationStatus>().status)
        assertEquals(HttpStatusCode.NotFound, client.get("/menu/$slug/reservation/${acc.id}?k=spatne").status)

        // The till gets it once with the contact details, and confirms it with a table.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/device/web-reservations").status)
        val waiting = client.get("/api/v1/device/web-reservations") { bearerAuth(dt) }.body<WebReservationList>().reservations
        assertEquals(1, waiting.size)
        assertEquals("Jan Novák", waiting[0].reservation.name)
        assertEquals("jan@example.cz", waiting[0].reservation.email)
        assertEquals(4, waiting[0].reservation.guests)
        assertTrue(client.get("/api/v1/device/web-reservations") { bearerAuth(dt) }.body<WebReservationList>().reservations.isEmpty())
        assertTrue(client.postJson("/api/v1/device/web-reservations/${acc.id}/ack", WebReservationAck(true, "5", "Těšíme se!"), dt).body<Ok>().ok)
        assertFalse(client.postJson("/api/v1/device/web-reservations/${acc.id}/ack", WebReservationAck(false), dt).body<Ok>().ok)

        val st = client.get("/menu/$slug/reservation/${acc.id}?k=${acc.token}").body<WebReservationStatus>()
        assertEquals("ACCEPTED", st.status)
        assertEquals("Těšíme se!", st.message)

        // The web admin sees it with the table.
        val list = client.get("/api/v1/web-reservations") { bearerAuth(owner) }.body<List<WebReservationView>>()
        assertEquals("5", list.single().table)
        assertTrue(list.single().customer.startsWith("Jan Novák"))
    }

    @Test fun declinedWithAReason() = run { client ->
        val (dt, slug) = client.setUp()
        val acc = client.postJson("/menu/$slug/reservation", res, origin = site).body<WebReservationAccepted>()
        assertTrue(client.postJson("/api/v1/device/web-reservations/${acc.id}/ack", WebReservationAck(false, message = "Máme plno."), dt).body<Ok>().ok)
        val st = client.get("/menu/$slug/reservation/${acc.id}?k=${acc.token}").body<WebReservationStatus>()
        assertEquals("REJECTED", st.status)
        assertEquals("Máme plno.", st.message)
    }

    @Test fun switchedOffAndThrottled() = run { client ->
        val (_, slug) = client.setUp(rules = null)
        assertFalse(client.get("/menu/$slug/reservation-info").body<WebReservationInfo>().open)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/menu/$slug/reservation", res, origin = site).status)
        assertNull(client.options("/menu/$slug/reservation") { header("Origin", "https://evil.example") }.headers["Access-Control-Allow-Origin"])
    }

    @Test fun samePhoneCannotFlood() = run { client ->
        val (_, slug) = client.setUp()
        repeat(3) { assertEquals(HttpStatusCode.OK, client.postJson("/menu/$slug/reservation", res, origin = site).status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.postJson("/menu/$slug/reservation", res, origin = site).status)
    }
}
