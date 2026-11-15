package cz.flos.server

import cz.flos.core.backoffice.BackOfficeClient
import cz.flos.core.backoffice.BoSale
import cz.flos.core.backoffice.BoSaleItem
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** A till clears its test receipts: only its own go, the other till's receipts and the products stay. */
class TestDataResetTest {
    @Test fun clearsOnlyThisTillsReceipts() {
        val db = Db.inMemory().also { it.migrate() }
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, port = port, host = "127.0.0.1") { flosModule(db, openSignup = true) }.start(wait = false)
        try {
            val base = "http://127.0.0.1:$port"
            val http = HttpClient.newHttpClient()
            fun send(method: String, path: String, body: String?, token: String? = null): HttpResponse<String> {
                val b = HttpRequest.newBuilder(URI("$base/api/v1$path")).header("Content-Type", "application/json")
                token?.let { b.header("Authorization", "Bearer $it") }
                b.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
                return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
            }
            val owner = FlosJson.decodeFromString<TokenResponse>(
                send("POST", "/auth/register", FlosJson.encodeToString(RegisterRequest("Pizza Fresca", "Majitel", "majitel@flos.cz", "tajneheslo1"))).body(),
            ).token
            val branch = FlosJson.decodeFromString<List<BranchDto>>(send("GET", "/branches", null, owner).body()).single()
            fun newTill(name: String): BackOfficeClient {
                val d = FlosJson.decodeFromString<DeviceDto>(send("POST", "/devices", FlosJson.encodeToString(DeviceDto(branchId = branch.id, name = name)), owner).body())
                return BackOfficeClient(base, BackOfficeClient(base).pair(d.pairingCode!!, "test").first)
            }
            val a = newTill("Pokladna A")
            val b = newTill("Pokladna B")
            val now = System.currentTimeMillis()
            fun sale(n: String) = BoSale(n, now, 10000, 10000, "CASH", items = listOf(BoSaleItem(null, "Pizza", 10000, 12, 1.0)))
            a.uploadSales(listOf(sale("2026-000001"), sale("2026-000002")))
            b.uploadSales(listOf(sale("2026-000001")))
            fun salesCount() = FlosJson.decodeFromString<List<SaleDto>>(send("GET", "/sales", null, owner).body()).size
            assertEquals(3, salesCount())

            val r = a.resetTestData()
            assertEquals(2, r.sales)
            assertEquals(1, salesCount())
            // The same numbers can be used again after the reset.
            assertEquals(1, a.uploadSales(listOf(sale("2026-000001"))).accepted)
            assertEquals(2, salesCount())
            // Only a paired till may ask.
            assertEquals(403, send("POST", "/device/test-data/reset", "{}", owner).statusCode())
        } finally {
            server.stop(100, 1000)
        }
    }
}
