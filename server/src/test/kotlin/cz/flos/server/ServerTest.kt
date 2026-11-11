package cz.flos.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ServerTest {

    private fun run(openSignup: Boolean = true, block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        val db = Db.inMemory().also { it.migrate() }
        application { flosModule(db, openSignup) }
        val client = createClient { install(ContentNegotiation) { json(FlosJson) } }
        block(client)
    }

    private suspend fun HttpClient.postJson(path: String, body: Any, token: String? = null): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        token?.let { bearerAuth(it) }
        setBody(body)
    }

    private suspend fun HttpClient.register(email: String = "majitel@flos.cz"): String =
        postJson("/api/v1/auth/register", RegisterRequest("Kavárna Flos", "Hazem", email, "tajneheslo1")).body<TokenResponse>().token

    @Test fun closedSignupAllowsOnlyTheFirstOwner() = run(openSignup = false) { client ->
        assertTrue(client.get("/api/v1/auth/signup").body<SignupStatus>().open)
        client.register()
        assertEquals(false, client.get("/api/v1/auth/signup").body<SignupStatus>().open)
        val second = client.postJson("/api/v1/auth/register", RegisterRequest("Cizí firma", "Někdo", "cizi@firma.cz", "tajneheslo2"))
        assertEquals(HttpStatusCode.Forbidden, second.status)
    }

    @Test fun registerLoginAndOrgIsolation() = run { client ->
        val token = client.register()
        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/api/v1/auth/login", LoginRequest("majitel@flos.cz", "spatne")).status)
        val login = client.postJson("/api/v1/auth/login", LoginRequest("MAJITEL@flos.cz", "tajneheslo1"))
        assertEquals(HttpStatusCode.OK, login.status)
        val me = client.get("/api/v1/me") { bearerAuth(token) }.body<MeResponse>()
        assertEquals("OWNER", me.user.role)

        client.postJson("/api/v1/products", ProductDto(name = "Espresso", price = 5900, vatPercent = 12), token)
        val other = client.register("jiny@firma.cz")
        val otherProducts = client.get("/api/v1/products") { bearerAuth(other) }.body<List<ProductDto>>()
        assertTrue("another company must not see these products", otherProducts.isEmpty())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/products").status)
    }

    @Test fun oneLoginManagesSeveralCompanies() = run { client ->
        val token = client.register()
        client.postJson("/api/v1/products", ProductDto(name = "Espresso", price = 5900, vatPercent = 12), token)
        val second = client.postJson("/api/v1/companies", NewCompanyRequest("Bistro Flos s.r.o.", ico = "12345678", dic = "cz12345678", vatPayer = true), token)
        assertEquals(HttpStatusCode.Created, second.status)
        val org = second.body<OrgDto>()
        assertEquals("CZ12345678", org.dic)

        val companies = client.get("/api/v1/companies") { bearerAuth(token) }.body<List<CompanyDto>>()
        assertEquals(2, companies.size)
        assertTrue(companies.single { it.current }.id != org.id)

        val me = client.postJson("/api/v1/companies/${org.id}/switch", mapOf<String, String>(), token).body<MeResponse>()
        assertEquals(org.id, me.org.id)
        assertEquals("OWNER", me.user.role)
        // The second company has its own catalogue and its own sale point.
        assertTrue(client.get("/api/v1/products") { bearerAuth(token) }.body<List<ProductDto>>().isEmpty())
        assertEquals(1, client.get("/api/v1/branches") { bearerAuth(token) }.body<List<BranchDto>>().size)

        // Another user's company cannot be opened.
        val other = client.register("jiny@firma.cz")
        val otherOrg = client.get("/api/v1/me") { bearerAuth(other) }.body<MeResponse>().org.id
        assertEquals(HttpStatusCode.NotFound, client.postJson("/api/v1/companies/$otherOrg/switch", mapOf<String, String>(), token).status)
    }

    @Test fun tillSyncStockRecipesReportsAndExports() = run { client ->
        val token = client.register()
        val branch = client.get("/api/v1/branches") { bearerAuth(token) }.body<List<BranchDto>>().single()
        client.put("/api/v1/branches/${branch.id}") { contentType(ContentType.Application.Json); bearerAuth(token); setBody(branch.copy(eetIdJednotky = 202)) }
        client.put("/api/v1/org") { contentType(ContentType.Application.Json); bearerAuth(token); setBody(OrgDto(name = "Kavárna Flos", ico = "12345678", dic = "CZ12345678", vatPayer = true)) }

        // Ingredients tracked in stock, and a cappuccino made from them.
        val coffee = client.postJson("/api/v1/products", ProductDto(name = "Káva zrnková", price = 0, unit = "kg", trackStock = true, sellable = false, minStock = 1.0, costPrice = 50000), token).body<ProductDto>()
        val milk = client.postJson("/api/v1/products", ProductDto(name = "Mléko", price = 0, unit = "l", trackStock = true, sellable = false), token).body<ProductDto>()
        val capp = client.postJson("/api/v1/products", ProductDto(name = "Cappuccino", price = 7500, vatPercent = 12,
            recipe = listOf(RecipeItemDto(coffee.id, 0.018), RecipeItemDto(milk.id, 0.15))), token).body<ProductDto>()
        val beer = client.postJson("/api/v1/products", ProductDto(name = "Pivo", price = 5900, vatPercent = 21, trackStock = true), token).body<ProductDto>()
        assertEquals(2, capp.recipe.size)

        assertEquals(HttpStatusCode.OK, client.postJson("/api/v1/stock/movements", StockMovementRequest(branch.id, "RECEIPT",
            listOf(StockLineDto(coffee.id, 2.0, 48000), StockLineDto(milk.id, 10.0), StockLineDto(beer.id, 50.0))), token).status)

        // Register a till and pair it with the code.
        val device = client.postJson("/api/v1/devices", DeviceDto(branchId = branch.id, name = "Pokladna 1", eetIdPokl = "POKL1"), token).body<DeviceDto>()
        val pair = client.postJson("/api/v1/device/pair", PairRequest(device.pairingCode!!, "0.2.0")).body<PairResponse>()
        assertEquals(202, pair.config.branch.eetIdJednotky)
        assertEquals("POKL1", pair.config.eetIdPokl)
        assertEquals(HttpStatusCode.NotFound, client.postJson("/api/v1/device/pair", PairRequest(device.pairingCode!!)).status)
        val dt = pair.token

        val catalog = client.get("/api/v1/device/catalog") { bearerAuth(dt) }.body<CatalogResponse>()
        assertEquals(4, catalog.products.size)
        // A device can't use the back office API.
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/products") { bearerAuth(dt) }.status)

        val now = System.currentTimeMillis()
        val sales = listOf(
            SaleDto(number = "2027-000001", createdAt = now, total = 15000, payment = "CASH", cashier = "Eva", eetStatus = "CONFIRMED",
                eetPok = "a1b2c3d4-0000-4abc-8def-0123456789ab-01", items = listOf(SaleItemDto(capp.id, "Cappuccino", 7500, 12, 2.0))),
            SaleDto(number = "2027-000002", createdAt = now, total = 11800, payment = "CARD", cashier = "Eva", eetStatus = "PENDING",
                items = listOf(SaleItemDto(beer.id, "Pivo", 5900, 21, 2.0))),
        )
        val up = client.postJson("/api/v1/device/sales", SalesUpload(sales), dt).body<UploadResult>()
        assertEquals(2, up.accepted)
        // Uploading again is harmless; the second upload cancels the beer sale.
        val again = client.postJson("/api/v1/device/sales", SalesUpload(listOf(sales[0], sales[1].copy(cancelled = true, stornoNumber = "2027-000003"))), dt).body<UploadResult>()
        assertEquals(0, again.accepted)
        assertEquals(2, again.updated)

        val stock = client.get("/api/v1/stock?branchId=${branch.id}") { bearerAuth(token) }.body<List<StockLevelDto>>().associateBy { it.productId }
        assertEquals(2.0 - 0.036, stock.getValue(coffee.id).qty, 1e-9)
        assertEquals(10.0 - 0.3, stock.getValue(milk.id).qty, 1e-9)
        assertEquals(50.0, stock.getValue(beer.id).qty, 1e-9) // sold 2, then storno returned them
        assertTrue(!stock.getValue(coffee.id).low)

        // Inventory count sets the level; transfer to a second branch.
        val b2 = client.postJson("/api/v1/branches", BranchDto(name = "Pobočka 2"), token).body<BranchDto>()
        client.postJson("/api/v1/stock/movements", StockMovementRequest(branch.id, "INVENTORY", listOf(StockLineDto(beer.id, 40.0))), token)
        client.postJson("/api/v1/stock/movements", StockMovementRequest(branch.id, "TRANSFER", listOf(StockLineDto(beer.id, 15.0)), toBranchId = b2.id), token)
        val all = client.get("/api/v1/stock") { bearerAuth(token) }.body<List<StockLevelDto>>().filter { it.productId == beer.id }.associateBy { it.branchId }
        assertEquals(25.0, all.getValue(branch.id).qty, 1e-9)
        assertEquals(15.0, all.getValue(b2.id).qty, 1e-9)

        val today = LocalDate.now(Reports.ZONE)
        val report = client.get("/api/v1/reports/summary?from=$today&to=$today") { bearerAuth(token) }.body<SummaryReport>()
        assertEquals(15000L, report.revenue)
        assertEquals(1, report.receipts)
        assertEquals(1, report.cancelled)
        assertEquals("Cappuccino", report.topProducts.first().name)
        assertEquals(15000L, report.byVat.sumOf { it.gross })

        val csv = client.get("/api/v1/export/sales.csv?from=$today") { bearerAuth(token) }.bodyAsText()
        assertTrue(csv.contains("2027-000001"))
        assertTrue(csv.lines().first().contains("Číslo"))
        val pohoda = client.get("/api/v1/export/pohoda.xml?from=$today") { bearerAuth(token) }.bodyAsText()
        assertTrue(pohoda.contains("<pro:prodejkaType>saleVoucher</pro:prodejkaType>"))
        assertTrue(pohoda.contains("<pro:rateVAT>low</pro:rateVAT>"))
        assertTrue(!pohoda.contains("2027-000002")) // cancelled sale left out
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pohoda.byteInputStream())

        val eet = client.get("/api/v1/eet?from=$today") { bearerAuth(token) }.body<EetOverview>()
        assertEquals(1, eet.confirmed)
        assertEquals(1, eet.pending)

        // Half a beer: stock, reports and exports keep the fraction.
        val half = SaleDto(number = "2027-000004", createdAt = now, total = 2950, payment = "CASH", cashier = "Eva", eetStatus = "CONFIRMED",
            items = listOf(SaleItemDto(beer.id, "Pivo", 5900, 21, 0.5)))
        assertEquals(1, client.postJson("/api/v1/device/sales", SalesUpload(listOf(half)), dt).body<UploadResult>().accepted)
        val beerLeft = client.get("/api/v1/stock?branchId=${branch.id}") { bearerAuth(token) }.body<List<StockLevelDto>>().first { it.productId == beer.id }
        assertEquals(24.5, beerLeft.qty, 1e-9)
        val report2 = client.get("/api/v1/reports/summary?from=$today&to=$today") { bearerAuth(token) }.body<SummaryReport>()
        assertEquals(0.5, report2.topProducts.first { it.name == "Pivo" }.quantity, 1e-9)
        assertEquals(2950L, report2.topProducts.first { it.name == "Pivo" }.revenue)
        val items = client.get("/api/v1/export/items.csv?from=$today") { bearerAuth(token) }.bodyAsText()
        assertTrue(items, items.contains("2027-000004;Pivo;0,5;"))
        val pohoda2 = client.get("/api/v1/export/pohoda.xml?from=$today") { bearerAuth(token) }.bodyAsText()
        assertTrue(pohoda2.contains("<pro:quantity>0.5</pro:quantity>"))
    }

    @Test fun staffPinAttendanceAndApiKeys() = run { client ->
        val token = client.register()
        val branch = client.get("/api/v1/branches") { bearerAuth(token) }.body<List<BranchDto>>().single()
        val eva = client.postJson("/api/v1/users", UserWrite("Eva", role = "CASHIER", pin = "1234"), token).body<UserDto>()
        assertTrue(eva.hasPin)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/v1/users", UserWrite("Petr", role = "MANAGER"), token).status)

        val device = client.postJson("/api/v1/devices", DeviceDto(branchId = branch.id, name = "P1"), token).body<DeviceDto>()
        val dt = client.postJson("/api/v1/device/pair", PairRequest(device.pairingCode!!)).body<PairResponse>()
        assertNotNull(dt.config.staff.first { it.name == "Eva" }.pinHash)
        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/api/v1/device/clock", ClockRequest("9999"), dt.token).status)
        val t0 = System.currentTimeMillis()
        assertEquals("IN", client.postJson("/api/v1/device/clock", ClockRequest("1234", t0), dt.token).body<ClockResponse>().action)
        assertEquals("OUT", client.postJson("/api/v1/device/clock", ClockRequest("1234", t0 + 3_600_000), dt.token).body<ClockResponse>().action)
        val att = client.get("/api/v1/attendance") { bearerAuth(token) }.body<List<AttendanceDto>>().single()
        assertEquals(60L, att.minutes)

        val readKey = client.postJson("/api/v1/api-keys", ApiKeyDto(name = "Účetní", scope = "READ"), token).body<ApiKeyDto>()
        assertNotNull(readKey.key)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/products") { bearerAuth(readKey.key!!) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/api/v1/products", ProductDto(name = "X", price = 1), readKey.key).status)
        val writeKey = client.postJson("/api/v1/api-keys", ApiKeyDto(name = "E-shop", scope = "WRITE"), token).body<ApiKeyDto>()
        assertEquals(HttpStatusCode.Created, client.postJson("/api/v1/products", ProductDto(name = "X", price = 100), writeKey.key).status)
        // API keys can't manage users or other keys.
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/api-keys") { bearerAuth(writeKey.key!!) }.status)
        val listed = client.get("/api/v1/api-keys") { bearerAuth(token) }.body<List<ApiKeyDto>>()
        assertTrue(listed.all { it.key == null })
        assertNull(listed.first().lastUsedAt?.takeIf { it < 0 })
    }

    @Test fun webAdminIsServed() = run { client ->
        val html = client.get("/").bodyAsText()
        assertTrue(html.contains("Flos"))
        assertEquals("ok", client.get("/health").bodyAsText())
    }

    @Test fun publishesQrMenu() = run { client ->
        val token = client.register()
        val branch = client.get("/api/v1/branches") { bearerAuth(token) }.body<List<BranchDto>>().single()
        val device = client.postJson("/api/v1/devices", DeviceDto(branchId = branch.id, name = "P1"), token).body<DeviceDto>()
        val dt = client.postJson("/api/v1/device/pair", PairRequest(device.pairingCode!!)).body<PairResponse>()
        val menu = cz.flos.core.MenuData("Bistro <Flos>", categories = listOf(cz.flos.core.MenuCategory("Nápoje", listOf(cz.flos.core.MenuItem("Espresso", 5900)))))
        val path = client.put("/api/v1/device/menu") { bearerAuth(dt.token); contentType(ContentType.Application.Json); setBody(menu) }.body<MenuPublished>().path
        assertTrue(path.startsWith("/menu/"))
        val page = client.get(path)
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertTrue(html.contains("Bistro &lt;Flos&gt;"))
        assertTrue(html.contains("59,00 Kč"))
        assertTrue(page.headers["Content-Security-Policy"]!!.contains("default-src 'none'"))
        val again = client.put("/api/v1/device/menu") { bearerAuth(dt.token); contentType(ContentType.Application.Json); setBody(menu.copy(name = "Bistro")) }.body<MenuPublished>().path
        assertEquals(path, again)
        assertEquals(HttpStatusCode.NotFound, client.get("/menu/nic").status)
        assertEquals(HttpStatusCode.Unauthorized, client.put("/api/v1/device/menu") { contentType(ContentType.Application.Json); setBody(menu) }.status)
    }

    @Test fun guestOrdersFromTheTable() = run { client ->
        GuestThrottle.clear()
        val token = client.register()
        val branch = client.get("/api/v1/branches") { bearerAuth(token) }.body<List<BranchDto>>().single()
        val device = client.postJson("/api/v1/devices", DeviceDto(branchId = branch.id, name = "P1"), token).body<DeviceDto>()
        val dt = client.postJson("/api/v1/device/pair", PairRequest(device.pairingCode!!)).body<PairResponse>()
        val pizza = cz.flos.core.MenuItem("Margherita", 18900, id = 7, options = "Velikost: Malá, Velká +60\nPřísady*: Sýr +20, Šunka +30")
        val menu = cz.flos.core.MenuData("Pizza Fresca", categories = listOf(cz.flos.core.MenuCategory("Pizza", listOf(pizza))), ordering = true)
        // Links need a published menu first.
        assertEquals(HttpStatusCode.Conflict, client.postJson("/api/v1/device/table-links", cz.flos.core.TableLinkRequest(listOf("5")), dt.token).status)
        client.put("/api/v1/device/menu") { bearerAuth(dt.token); contentType(ContentType.Application.Json); setBody(menu) }
        val links = client.postJson("/api/v1/device/table-links", cz.flos.core.TableLinkRequest(listOf("5", "Terasa 1")), dt.token).body<cz.flos.core.TableLinkList>().links
        assertEquals(listOf("5", "Terasa 1"), links.map { it.table })
        val link = links.first().path
        val slug = link.substringAfter("/menu/").substringBefore("?")
        val key = link.substringAfter("k=")
        assertTrue(links[1].path.contains("t=Terasa%201"))

        // The table's QR opens the menu with ordering; without the key it is the plain menu.
        val page = client.get(link)
        assertTrue(page.bodyAsText().contains("flos-order"))
        assertTrue(page.headers["Content-Security-Policy"]!!.contains("script-src 'self'"))
        assertTrue(!client.get("/menu/$slug?t=5&k=spatne").bodyAsText().contains("flos-order"))
        assertTrue(!client.get("/menu/$slug").bodyAsText().contains("flos-order"))

        val line = cz.flos.core.GuestOrderLine(7, 2, listOf(listOf(1), listOf(0, 1)), "bez bazalky")
        // A wrong key, an unknown dish or a bad choice is refused.
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/menu/$slug/order", cz.flos.core.GuestOrder("5", "x", listOf(line))).status)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/menu/$slug/order", cz.flos.core.GuestOrder("6", key, listOf(line))).status)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/menu/$slug/order", cz.flos.core.GuestOrder("5", key, listOf(line.copy(id = 99)))).status)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/menu/$slug/order", cz.flos.core.GuestOrder("5", key, listOf(line.copy(choices = listOf(emptyList()))))).status)

        val sent = client.postJson("/menu/$slug/order", cz.flos.core.GuestOrder("5", key, listOf(line), "spěcháme"))
        assertEquals(HttpStatusCode.OK, sent.status)
        val id = sent.body<cz.flos.core.GuestOrderAccepted>().id
        assertEquals("NEW", client.get("/menu/$slug/order/$id?t=5&k=$key").body<GuestOrderStatus>().status)

        // The till fetches it once, then answers.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/device/table-orders").status)
        val waiting = client.get("/api/v1/device/table-orders") { bearerAuth(dt.token) }.body<cz.flos.core.TableOrderList>().orders
        assertEquals(1, waiting.size)
        assertEquals("5", waiting[0].table)
        assertEquals("bez bazalky", waiting[0].lines[0].note)
        assertEquals("spěcháme", waiting[0].note)
        assertTrue(client.get("/api/v1/device/table-orders") { bearerAuth(dt.token) }.body<cz.flos.core.TableOrderList>().orders.isEmpty())
        client.postJson("/api/v1/device/table-orders/$id/ack", cz.flos.core.TableOrderAck(true), dt.token)
        assertEquals("ACCEPTED", client.get("/menu/$slug/order/$id?t=5&k=$key").body<GuestOrderStatus>().status)

        // The web admin reads the orders from the tables with the dish names from the menu.
        val seen = client.get("/api/v1/table-orders") { bearerAuth(token) }.body<List<TableOrderView>>().single()
        assertEquals("ACCEPTED", seen.status)
        assertEquals("Margherita", seen.lines.single().name)
        assertEquals("Velká, Sýr, Šunka", seen.lines.single().choices)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/table-orders") { bearerAuth(dt.token) }.status)

        // Ordering switched off on the till: the same QR shows only the menu and orders are refused.
        client.put("/api/v1/device/menu") { bearerAuth(dt.token); contentType(ContentType.Application.Json); setBody(menu.copy(ordering = false)) }
        assertTrue(!client.get(link).bodyAsText().contains("flos-order"))
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/menu/$slug/order", cz.flos.core.GuestOrder("5", key, listOf(line))).status)
    }

    @Test fun tillSnapshotIsShownReadOnly() = run { client ->
        val token = client.register()
        val branch = client.get("/api/v1/branches") { bearerAuth(token) }.body<List<BranchDto>>().single()
        val device = client.postJson("/api/v1/devices", DeviceDto(branchId = branch.id, name = "P1"), token).body<DeviceDto>()
        val dt = client.postJson("/api/v1/device/pair", PairRequest(device.pairingCode!!)).body<PairResponse>()
        assertNull(client.get("/api/v1/till-live") { bearerAuth(token) }.body<List<TillLiveDevice>>().single().snapshot)

        val snap = cz.flos.core.backoffice.BoTillSnapshot(
            takenAt = System.currentTimeMillis() + 3_600_000, // a till clock ahead is cut to the server time
            openOrders = listOf(cz.flos.core.backoffice.BoOpenOrder(table = "5", total = 37800, items = listOf(cz.flos.core.backoffice.BoOrderLine("Margherita", 2.0, 18900)))),
            cash = cz.flos.core.backoffice.BoCashState(0, 100000, 37800, 0, 0, 0, 137800),
            customers = List(2500) { cz.flos.core.backoffice.BoCustomer("Host $it") },
            eetWaiting = 1,
        )
        suspend fun send(body: cz.flos.core.backoffice.BoTillSnapshot, t: String) =
            client.put("/api/v1/device/snapshot") { bearerAuth(t); contentType(ContentType.Application.Json); setBody(body) }.status
        assertEquals(HttpStatusCode.OK, send(snap, dt.token))
        assertEquals(HttpStatusCode.OK, send(snap.copy(eetWaiting = 0), dt.token)) // replaces the previous one
        assertEquals(HttpStatusCode.Forbidden, send(snap, token)) // only paired tills send

        val live = client.get("/api/v1/till-live") { bearerAuth(token) }.body<List<TillLiveDevice>>().single()
        val s = live.snapshot!!
        assertEquals("5", s.openOrders.single().table)
        assertEquals(137800L, s.cash!!.expected)
        assertEquals(0, s.eetWaiting)
        assertEquals(cz.flos.core.backoffice.BoTillSnapshot.MAX_CUSTOMERS, s.customers.size)
        assertTrue(s.takenAt <= System.currentTimeMillis())
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/till-live") { bearerAuth(dt.token) }.status)
    }

    @Test fun guestOrdersAreThrottledPerTable() = run { client ->
        GuestThrottle.clear()
        val token = client.register()
        val branch = client.get("/api/v1/branches") { bearerAuth(token) }.body<List<BranchDto>>().single()
        val device = client.postJson("/api/v1/devices", DeviceDto(branchId = branch.id, name = "P1"), token).body<DeviceDto>()
        val dt = client.postJson("/api/v1/device/pair", PairRequest(device.pairingCode!!)).body<PairResponse>()
        val menu = cz.flos.core.MenuData("Pizza Fresca", categories = listOf(cz.flos.core.MenuCategory("Nápoje", listOf(cz.flos.core.MenuItem("Voda", 3900, id = 1)))), ordering = true)
        client.put("/api/v1/device/menu") { bearerAuth(dt.token); contentType(ContentType.Application.Json); setBody(menu) }
        val link = client.postJson("/api/v1/device/table-links", cz.flos.core.TableLinkRequest(listOf("2")), dt.token).body<cz.flos.core.TableLinkList>().links.single().path
        val slug = link.substringAfter("/menu/").substringBefore("?")
        val key = link.substringAfter("k=")
        val order = cz.flos.core.GuestOrder("2", key, listOf(cz.flos.core.GuestOrderLine(1, 1)))
        repeat(6) { assertEquals(HttpStatusCode.OK, client.postJson("/menu/$slug/order", order).status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.postJson("/menu/$slug/order", order).status)
    }
}
