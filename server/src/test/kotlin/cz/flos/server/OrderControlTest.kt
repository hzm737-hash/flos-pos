package cz.flos.server

import cz.flos.core.GuestOrder
import cz.flos.core.GuestOrderLine
import cz.flos.core.MenuCategory
import cz.flos.core.MenuData
import cz.flos.core.MenuItem
import cz.flos.core.OpenTables
import cz.flos.core.OrderControl
import cz.flos.core.OrderHours
import cz.flos.core.OrderStatus
import cz.flos.core.TableLinkList
import cz.flos.core.TableLinkRequest
import cz.flos.core.WebCustomer
import cz.flos.core.WebOrder
import cz.flos.core.WebOrderLine
import cz.flos.core.WebOrders
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
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

class OrderControlTest {
    private val site = "https://pizzafresca.cz"

    private fun run(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        GuestThrottle.clear()
        val db = Db.inMemory().also { it.migrate() }
        application { flosModule(db, openSignup = true, webOrigins = listOf(site)) }
        block(createClient { install(ContentNegotiation) { json(FlosJson) } })
    }

    private suspend fun HttpClient.postJson(path: String, body: Any, token: String? = null): HttpResponse = post(path) {
        contentType(ContentType.Application.Json); token?.let { bearerAuth(it) }; setBody(body)
    }

    private suspend fun HttpClient.putJson(path: String, body: Any, token: String): HttpResponse = put(path) {
        contentType(ContentType.Application.Json); bearerAuth(token); setBody(body)
    }

    private val closedWeek = List(7) { "" }
    private val webOrder = WebOrder(WebOrders.PICKUP, listOf(WebOrderLine(3, "Diavola", qty = 1, unitPrice = 220)),
        WebCustomer("Jan Novák", "777 123 456"), total = 220, agreedTerms = true)

    @Test fun hoursAndStopOrders() = run { client ->
        val owner = client.postJson("/api/v1/auth/register", RegisterRequest("Pizza Fresca", "Hazem", "majitel@flos.cz", "tajneheslo1")).body<TokenResponse>().token
        val branch = client.get("/api/v1/branches") { bearerAuth(owner) }.body<List<BranchDto>>().single()
        val device = client.postJson("/api/v1/devices", DeviceDto(branchId = branch.id, name = "P1"), owner).body<DeviceDto>()
        val dt = client.postJson("/api/v1/device/pair", PairRequest(device.pairingCode!!)).body<PairResponse>().token
        val menu = MenuData("Pizza Fresca", categories = listOf(MenuCategory("Pizza", listOf(MenuItem("Diavola", 22000, id = 3)))), ordering = true, webOrdering = true)
        val slug = client.putJson("/api/v1/device/menu", menu, dt).body<MenuPublished>().path.removePrefix("/menu/")
        val link = client.postJson("/api/v1/device/table-links", TableLinkRequest(listOf("5", "6")), dt).body<TableLinkList>().links
        val key5 = link[0].path.substringAfter("k=")
        val key6 = link[1].path.substringAfter("k=")
        val line = GuestOrderLine(3, 1)

        // Nothing set yet: open as before.
        val first = client.get("/menu/$slug/order-status") { header("Origin", site) }
        assertEquals(site, first.headers["Access-Control-Allow-Origin"])
        assertTrue(first.body<OrderStatus>().qr.open)
        assertEquals(OrderControl(), client.get("/api/v1/device/order-control") { bearerAuth(dt) }.body<OrderControl>())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/device/order-control").status)

        // Unreadable hours are refused.
        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/v1/device/order-control", OrderControl(qrHoursOn = true, qrHours = List(7) { "od rána" }), dt).status)

        // Stopped on the till: the website gets the message, orders are turned away with it.
        val stop = OrderControl(webPaused = true, pauseMessage = "Máme plno, zkuste to za hodinu.")
        assertEquals(HttpStatusCode.OK, client.putJson("/api/v1/device/order-control", stop, dt).status)
        val st = client.get("/menu/$slug/order-status").body<OrderStatus>()
        assertFalse(st.web.open); assertEquals("paused", st.web.reason); assertEquals("Máme plno, zkuste to za hodinu.", st.web.message)
        assertTrue(st.qr.open)
        val refused = client.postJson("/menu/$slug/web-order", webOrder)
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        val body = refused.body<OrdersClosedBody>()
        assertEquals("paused", body.reason); assertEquals("Máme plno, zkuste to za hodinu.", body.error)
        assertEquals("Máme plno, zkuste to za hodinu.", client.postJson("/menu/$slug/web-order", webOrder.copy(lang = "en")).body<OrdersClosedBody>().error)
        // Table orders still come in.
        assertEquals(HttpStatusCode.OK, client.postJson("/menu/$slug/order", GuestOrder("5", key5, listOf(line))).status)

        // Outside the hours (closed every day): the table's menu is shown without ordering, with the reason.
        client.putJson("/api/v1/device/order-control", OrderControl(qrHoursOn = true, qrHours = closedWeek, openTablesAfterHours = true), dt)
        val page = client.get(link[0].path).bodyAsText()
        assertFalse(page.contains("flos-order")); assertTrue(page.contains("Teď objednávky nepřijímáme."))
        val late = client.postJson("/menu/$slug/order", GuestOrder("6", key6, listOf(line)))
        assertEquals(HttpStatusCode.Forbidden, late.status)
        assertEquals("hours", late.body<OrdersClosedBody>().reason)
        assertNull(late.body<OrdersClosedBody>().opensAt)
        assertTrue(client.postJson("/menu/$slug/web-order", webOrder).status == HttpStatusCode.OK) // web hours are separate

        // Table 5 still has an open bill on the till: those guests may keep ordering.
        assertEquals(HttpStatusCode.OK, client.putJson("/api/v1/device/open-tables", OpenTables(listOf("5")), dt).status)
        assertTrue(client.get(link[0].path).bodyAsText().contains("flos-order"))
        assertEquals(HttpStatusCode.OK, client.postJson("/menu/$slug/order", GuestOrder("5", key5, listOf(line))).status)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/menu/$slug/order", GuestOrder("6", key6, listOf(line))).status)
        assertTrue(client.get("/menu/$slug/order-status?t=5&k=$key5").body<OrderStatus>().qr.open)
        assertFalse(client.get("/menu/$slug/order-status?t=5&k=wrong").body<OrderStatus>().qr.open)
        // The same guests on the website's table menu.
        val atTable = webOrder.copy(type = WebOrders.TABLE, customer = null, table = "5", tableKey = key5)
        assertEquals(HttpStatusCode.OK, client.postJson("/menu/$slug/web-order", atTable).status)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/menu/$slug/web-order", atTable.copy(table = "6", tableKey = key6)).status)

        // Without the exception, or once the bill is paid, table 5 is closed too.
        client.putJson("/api/v1/device/order-control", OrderControl(qrHoursOn = true, qrHours = closedWeek, openTablesAfterHours = false), dt)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/menu/$slug/order", GuestOrder("5", key5, listOf(line))).status)
        client.putJson("/api/v1/device/order-control", OrderControl(qrHoursOn = true, qrHours = closedWeek), dt)
        client.putJson("/api/v1/device/open-tables", OpenTables(emptyList()), dt)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/menu/$slug/order", GuestOrder("5", key5, listOf(line))).status)

        // Web orders outside their hours: a pre-order for a later day still comes in and waits for the till,
        // which confirms it for that day; an order for today is turned away. Stopped on the till: nothing comes in.
        client.putJson("/api/v1/device/order-control", OrderControl(webHoursOn = true, webHours = closedWeek), dt)
        val tomorrow = java.time.LocalDate.now(OrderHours.ZONE).plusDays(1)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/menu/$slug/web-order", webOrder).status)
        val pre = client.postJson("/menu/$slug/web-order", webOrder.copy(date = tomorrow.toString(), time = "18:30", payment = "qr"))
        assertEquals(HttpStatusCode.OK, pre.status)
        val preId = pre.body<cz.flos.core.WebOrderAccepted>().id
        val inbox = client.get("/api/v1/device/web-orders") { bearerAuth(dt) }.body<cz.flos.core.WebOrderList>().orders
        assertTrue(inbox.any { it.id == preId && it.order.date == tomorrow.toString() && it.order.payment == "qr" })
        val at = tomorrow.atTime(18, 30).atZone(OrderHours.ZONE).toInstant().toEpochMilli()
        assertEquals(HttpStatusCode.OK, client.postJson("/api/v1/device/web-orders/$preId/ack", cz.flos.core.WebOrderAck(true, 0, at), dt).status)
        val token = pre.body<cz.flos.core.WebOrderAccepted>().token
        val answer = client.get("/menu/$slug/web-order/$preId?k=$token").body<cz.flos.core.WebOrderStatus>()
        assertEquals("ACCEPTED", answer.status); assertEquals(at, answer.readyAt)
        client.putJson("/api/v1/device/order-control", OrderControl(webHoursOn = true, webHours = closedWeek, webPaused = true), dt)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/menu/$slug/web-order", webOrder.copy(date = tomorrow.toString(), time = "18:30")).status)

        // Hours switched off again: open as before.
        client.putJson("/api/v1/device/order-control", OrderControl(qrHours = closedWeek), dt)
        assertEquals(HttpStatusCode.OK, client.postJson("/menu/$slug/order", GuestOrder("6", key6, listOf(line))).status)
        assertEquals(OrderHours.DEFAULT_WEEK, client.get("/api/v1/device/order-control") { bearerAuth(dt) }.body<OrderControl>().webHours)
    }
}
