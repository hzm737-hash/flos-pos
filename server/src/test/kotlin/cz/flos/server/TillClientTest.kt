package cz.flos.server

import cz.flos.core.backoffice.BackOfficeClient
import cz.flos.core.backoffice.BackOfficeException
import cz.flos.core.backoffice.BoSale
import cz.flos.core.backoffice.BoSaleItem
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** The till's own HTTP client (core) against the real back office running on a local port. */
class TillClientTest {
    @Test fun tillPairsSyncsCatalogUploadsSalesAndChecksPins() {
        val db = Db.inMemory().also { it.migrate() }
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, port = port, host = "127.0.0.1") { flosModule(db, openSignup = true) }.start(wait = false)
        try {
            val base = "http://127.0.0.1:$port"
            val http = HttpClient.newHttpClient()
            fun call(method: String, path: String, body: String?, token: String? = null): String {
                val b = HttpRequest.newBuilder(URI("$base/api/v1$path")).header("Content-Type", "application/json")
                token?.let { b.header("Authorization", "Bearer $it") }
                b.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
                return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).body()
            }
            val owner = FlosJson.decodeFromString<TokenResponse>(
                call("POST", "/auth/register", FlosJson.encodeToString(RegisterRequest("Kavárna Flos", "Hazem", "majitel@flos.cz", "tajneheslo1")), null),
            ).token
            val branch = FlosJson.decodeFromString<List<BranchDto>>(call("GET", "/branches", null, owner)).single()
            call("POST", "/products", FlosJson.encodeToString(ProductDto(name = "Espresso", price = 5900, vatPercent = 12, barcode = "859000")), owner)
            call("POST", "/users", FlosJson.encodeToString(UserWrite("Eva", role = "CASHIER", pin = "1234")), owner)
            val device = FlosJson.decodeFromString<DeviceDto>(call("POST", "/devices", FlosJson.encodeToString(DeviceDto(branchId = branch.id, name = "Pokladna 1", eetIdPokl = "POKL7")), owner))

            // Pairing with a wrong code fails with the server's message; the right code gives a token.
            val wrong = runCatching { BackOfficeClient("127.0.0.1:$port".let { "http://$it/" }).pair("XXXXXXXX", "test") }.exceptionOrNull()
            assertTrue(wrong is BackOfficeException && wrong.status == 404)
            val (token, config) = BackOfficeClient(base).pair(device.pairingCode!!.lowercase(), "test")
            assertEquals("POKL7", config.eetIdPokl)
            assertEquals("Kavárna Flos", config.org.name)
            val eva = config.staff.single { it.name == "Eva" }
            assertTrue(BackOfficeClient.verifyPin("1234", eva.pinHash))
            assertFalse(BackOfficeClient.verifyPin("9999", eva.pinHash))
            assertEquals(config.staff, BackOfficeClient.staffFromJson(BackOfficeClient.staffToJson(config.staff)))

            val client = BackOfficeClient("$base/api/v1/", token)
            val catalog = client.catalog(0)
            val espresso = catalog.products.single { it.name == "Espresso" }
            assertEquals(5900L, espresso.price)
            assertEquals("859000", espresso.barcode)
            assertTrue(client.catalog(catalog.serverTime).products.isEmpty())

            val sale = BoSale("2027-000001", System.currentTimeMillis(), 11800, 11800, "CARD", "Eva",
                items = listOf(BoSaleItem(espresso.id, "Espresso", 5900, 12, 2)), eetStatus = "CONFIRMED")
            assertEquals(1, client.uploadSales(listOf(sale)).accepted)
            val refund = BoSale("2027-000002", System.currentTimeMillis(), -5900, -5900, "CARD", "Eva",
                items = listOf(BoSaleItem(espresso.id, "Espresso", 5900, 12, -1)))
            val again = client.uploadSales(listOf(sale, refund))
            assertEquals(1, again.accepted)
            assertEquals(1, again.updated)

            assertEquals("IN", client.clock("1234").action)
            assertEquals("OUT", client.clock("1234").action)
            assertEquals(401, (runCatching { client.clock("0000") }.exceptionOrNull() as BackOfficeException).status)
        } finally {
            server.stop(100, 1000)
        }
    }
}
