package cz.flos.server

import cz.flos.core.MenuCategory
import cz.flos.core.MenuData
import cz.flos.core.MenuItem
import cz.flos.core.WebCustomer
import cz.flos.core.WebOrder
import cz.flos.core.WebOrderAccepted
import cz.flos.core.WebOrderAck
import cz.flos.core.WebOrderLine
import cz.flos.core.WebOrderList
import cz.flos.core.WebOrderStatus
import cz.flos.core.WebOrders
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebOrderTest {
    private val site = "https://pizzafresca.cz"
    private lateinit var db: Db

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

    /** Owner, a paired till and a published menu; returns the till's token and the menu slug. */
    private suspend fun HttpClient.setUp(webOrdering: Boolean = true): Pair<String, String> {
        val token = postJson("/api/v1/auth/register", RegisterRequest("Pizza Fresca", "Hazem", "majitel@flos.cz", "tajneheslo1")).body<TokenResponse>().token
        val branch = get("/api/v1/branches") { bearerAuth(token) }.body<List<BranchDto>>().single()
        val device = postJson("/api/v1/devices", DeviceDto(branchId = branch.id, name = "P1"), token).body<DeviceDto>()
        val dt = postJson("/api/v1/device/pair", PairRequest(device.pairingCode!!)).body<PairResponse>().token
        val menu = MenuData("Pizza Fresca", categories = listOf(MenuCategory("Pizza", listOf(MenuItem("Diavola", 22000, id = 3)))), webOrdering = webOrdering)
        val path = put("/api/v1/device/menu") { bearerAuth(dt); contentType(ContentType.Application.Json); setBody(menu) }.body<MenuPublished>().path
        return dt to path.removePrefix("/menu/")
    }

    private val order = WebOrder(
        WebOrders.DELIVERY, listOf(WebOrderLine(10, "Diavola", half = 1, halfName = "Margherita", qty = 2, unitPrice = 180)),
        WebCustomer("Jan Novák", "777 123 456", "Ostrožná 12", "2. patro"), time = "19:30", note = "zvonek nefunguje",
        deliveryFee = 50, total = 410, agreedTerms = true,
    )

    @Test fun websiteSendsAndTillAnswers() = run { client ->
        val (dt, slug) = client.setUp()

        // Preflight from the restaurant's website is allowed; another site gets no CORS header.
        val pre = client.options("/menu/$slug/web-order") { header("Origin", site); header("Access-Control-Request-Method", "POST") }
        assertEquals(site, pre.headers["Access-Control-Allow-Origin"])
        assertNull(client.options("/menu/$slug/web-order") { header("Origin", "https://evil.example") }.headers["Access-Control-Allow-Origin"])

        // Bad orders are refused with a reason.
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/menu/$slug/web-order", order.copy(agreedTerms = false), origin = site).status)
        assertEquals(HttpStatusCode.NotFound, client.postJson("/menu/nic/web-order", order, origin = site).status)

        val sent = client.postJson("/menu/$slug/web-order", order, origin = site)
        assertEquals(HttpStatusCode.OK, sent.status)
        assertEquals(site, sent.headers["Access-Control-Allow-Origin"])
        val acc = sent.body<WebOrderAccepted>()
        assertEquals("NEW", client.get("/menu/$slug/web-order/${acc.id}?k=${acc.token}").body<WebOrderStatus>().status)
        // Without the token nobody can read the answer.
        assertEquals(HttpStatusCode.NotFound, client.get("/menu/$slug/web-order/${acc.id}?k=spatne").status)

        // The till fetches it once, with the customer's details, then answers with a ready time.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/device/web-orders").status)
        val waiting = client.get("/api/v1/device/web-orders") { bearerAuth(dt) }.body<WebOrderList>().orders
        assertEquals(1, waiting.size)
        assertEquals("Jan Novák", waiting[0].order.customer!!.name)
        assertEquals("Margherita", waiting[0].order.lines[0].halfName)
        assertTrue(client.get("/api/v1/device/web-orders") { bearerAuth(dt) }.body<WebOrderList>().orders.isEmpty())
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/v1/device/web-orders/${acc.id}/ack", WebOrderAck(true, 0), dt).status)
        assertTrue(client.postJson("/api/v1/device/web-orders/${acc.id}/ack", WebOrderAck(true, 30), dt).body<Ok>().ok)
        // A second answer (another till) changes nothing.
        assertEquals(false, client.postJson("/api/v1/device/web-orders/${acc.id}/ack", WebOrderAck(false), dt).body<Ok>().ok)

        val status = client.get("/menu/$slug/web-order/${acc.id}?k=${acc.token}").body<WebOrderStatus>()
        assertEquals("ACCEPTED", status.status)
        assertNotNull(status.readyAt)
        assertTrue(status.readyAt!! > System.currentTimeMillis() + 29 * 60_000)
    }

    /** Exactly what the Pizza Fresca website's order.js sends. */
    @Test fun theWebsitesPayloadIsTaken() = run { client ->
        val (dt, slug) = client.setUp()
        val json = """{"type":"DELIVERY","lines":[{"number":3,"name":"Diavola","half":2,"halfName":"Margherita",
            "extras":[{"name":"Burrata","price":80,"half":0,"full":true},{"name":"Rukola","price":30,"half":2,"full":false}],
            "note":"","qty":1,"unitPrice":285}],"customer":{"name":"Jan","phone":"+420 777 123 456","street":"Ostrožná 12, Opava","bell":""},
            "time":"","payment":"card","note":"","lang":"cs","deliveryFee":50,"total":335,"agreedTerms":true}"""
        val sent = client.post("/menu/$slug/web-order") { contentType(ContentType.Application.Json); header("Origin", site); setBody(json) }
        assertEquals(HttpStatusCode.OK, sent.status)
        val o = client.get("/api/v1/device/web-orders") { bearerAuth(dt) }.body<WebOrderList>().orders.single().order
        assertEquals(2, o.lines[0].extras[1].half)
        assertEquals("card", o.payment)
    }

    @Test fun switchedOffOnTheTillRefusesOrders() = run { client ->
        val (_, slug) = client.setUp(webOrdering = false)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/menu/$slug/web-order", order).status)
    }

    @Test fun tooManyOrdersFromOnePhoneAreSlowedDown() = run { client ->
        val (_, slug) = client.setUp()
        repeat(6) { assertEquals(HttpStatusCode.OK, client.postJson("/menu/$slug/web-order", order).status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.postJson("/menu/$slug/web-order", order).status)
    }

    @Test fun contactDetailsAreErasedAfter30Days() = run { client ->
        val (dt, slug) = client.setUp()
        val id = client.postJson("/menu/$slug/web-order", order).body<WebOrderAccepted>().id
        val old = System.currentTimeMillis() - WEB_CUSTOMER_KEEP_MS - 1000
        db.tx { update("UPDATE web_orders SET created_at = ? WHERE id = ?", old, id) }
        db.tx { eraseOldWebCustomers() }
        val customer = db.tx { queryOne("SELECT customer FROM web_orders WHERE id = ?", id) { it.getString(1) } }
        assertNull(customer)
        // The order itself stays.
        assertTrue(db.tx { queryOne("SELECT data FROM web_orders WHERE id = ?", id) { it.getString(1) } }!!.contains("Diavola"))
        // Too old for the till anyway.
        assertTrue(client.get("/api/v1/device/web-orders") { bearerAuth(dt) }.body<WebOrderList>().orders.isEmpty())
    }
}
