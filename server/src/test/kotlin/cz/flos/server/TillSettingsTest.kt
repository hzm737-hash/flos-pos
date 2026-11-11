package cz.flos.server

import cz.flos.core.backoffice.BackOfficeClient
import cz.flos.core.backoffice.BoSettingChange
import cz.flos.core.backoffice.BoSettingsUpload
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Till settings in the back office: company-wide and per till, the later change winning, secrets never stored. */
class TillSettingsTest {
    @Test fun settingsTravelBothWaysAndSecretsStayOnTheTill() {
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
            fun till(name: String): Pair<Long, BackOfficeClient> {
                val d = FlosJson.decodeFromString<DeviceDto>(send("POST", "/devices", FlosJson.encodeToString(DeviceDto(branchId = branch.id, name = name)), owner).body())
                return d.id to BackOfficeClient(base, BackOfficeClient(base).pair(d.pairingCode!!, "test").first)
            }
            val (idA, a) = till("Pokladna A")
            val (idB, b) = till("Pokladna B")
            fun ch(v: String, t: Long = 0) = BoSettingChange(v, t)

            // The first till brings its settings; a secret it might send by mistake is not kept.
            val first = a.syncSettings(BoSettingsUpload(mapOf("businessName" to ch("Pizza Fresca"), "printerHost" to ch("10.0.0.5"), "fioToken" to ch("tajne"),
                "boToken" to ch("x")), 0, mapOf("eetCertificate" to true, "fioToken" to false)))
            assertEquals("Pizza Fresca", first.values.getValue("businessName").value)
            assertEquals("10.0.0.5", first.values.getValue("printerHost").value)
            assertFalse("fioToken" in first.values || "boToken" in first.values)

            // The second till takes the company's settings but keeps its own printer.
            val second = b.syncSettings(BoSettingsUpload(mapOf("businessName" to ch("Moje provozovna"), "printerHost" to ch("10.0.0.9")), 0))
            assertEquals("Pizza Fresca", second.values.getValue("businessName").value)
            assertEquals("10.0.0.9", second.values.getValue("printerHost").value)

            // The web admin sees both, and which secrets are set (never their values).
            val view = FlosJson.decodeFromString<TillSettingsView>(send("GET", "/till-settings", null, owner).body())
            assertEquals("Pizza Fresca", view.company.getValue("businessName").value)
            assertNull(view.company["printerHost"])
            assertEquals("10.0.0.5", view.devices.single { it.id == idA }.values.getValue("printerHost").value)
            assertEquals(listOf("eetCertificate"), view.devices.single { it.id == idA }.secrets)
            assertTrue(view.company.keys.none { it == "fioToken" })

            // Changing it on the web: secrets and a printer without a till are refused.
            fun put(w: TillSettingsWrite) = send("PUT", "/till-settings", FlosJson.encodeToString(w), owner).statusCode()
            assertEquals(400, put(TillSettingsWrite(null, mapOf("fioToken" to "x"))))
            assertEquals(400, put(TillSettingsWrite(null, mapOf("printerHost" to "10.0.0.7"))))
            assertEquals(404, put(TillSettingsWrite(999_999, mapOf("printerHost" to "10.0.0.7"))))
            val beforeWeb = System.currentTimeMillis() - 1
            Thread.sleep(5)
            assertEquals(200, put(TillSettingsWrite(null, mapOf("businessName" to "Pizza Fresca Praha", "advanced" to "QR_ORDERING"))))
            assertEquals(200, put(TillSettingsWrite(idB, mapOf("printerHost" to "10.0.0.7"))))

            // Both tills get it with their next sync; only what changed since then.
            val nextA = a.syncSettings(BoSettingsUpload(since = first.serverTime))
            assertEquals("Pizza Fresca Praha", nextA.values.getValue("businessName").value)
            assertEquals("QR_ORDERING", nextA.values.getValue("advanced").value)
            assertEquals("the other till's printer does not come here", "10.0.0.5", nextA.values["printerHost"]?.value ?: "10.0.0.5")
            assertEquals("10.0.0.7", b.syncSettings(BoSettingsUpload(since = second.serverTime)).values.getValue("printerHost").value)

            // An older change from a till loses to the web's, a newer one wins and reaches the other till.
            val stale = a.syncSettings(BoSettingsUpload(mapOf("businessName" to ch("Stará", beforeWeb)), nextA.serverTime))
            assertEquals("Pizza Fresca Praha", stale.values["businessName"]?.value ?: "Pizza Fresca Praha")
            val fresh = a.syncSettings(BoSettingsUpload(mapOf("businessName" to ch("Pizza Fresca Brno", System.currentTimeMillis() + 10)), stale.serverTime))
            assertEquals("Pizza Fresca Brno", fresh.values.getValue("businessName").value)
            assertEquals("Pizza Fresca Brno", b.syncSettings(BoSettingsUpload(since = 0)).values.getValue("businessName").value)

            // Only paired tills may use the till endpoint, and cashiers cannot read the settings.
            assertEquals(403, send("POST", "/device/settings", FlosJson.encodeToString(BoSettingsUpload()), owner).statusCode())
        } finally {
            server.stop(100, 1000)
        }
    }
}
