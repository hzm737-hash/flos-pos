package cz.flos.server

import cz.flos.core.backoffice.BackOfficeClient
import cz.flos.core.backoffice.BoSettingsUpload
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Deleting a till in the back office: it disappears and is locked out, other companies cannot touch it. */
class DeviceDeleteTest {
    @Test fun deletedTillIsGoneAndLockedOut() {
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
            fun register(name: String, email: String) = FlosJson.decodeFromString<TokenResponse>(
                send("POST", "/auth/register", FlosJson.encodeToString(RegisterRequest(name, "Majitel", email, "tajneheslo1"))).body(),
            ).token
            val owner = register("Pizza Fresca", "majitel@flos.cz")
            val other = register("Jiná firma", "jiny@flos.cz")
            val branch = FlosJson.decodeFromString<List<BranchDto>>(send("GET", "/branches", null, owner).body()).single()
            fun newDevice(name: String) = FlosJson.decodeFromString<DeviceDto>(
                send("POST", "/devices", FlosJson.encodeToString(DeviceDto(branchId = branch.id, name = name)), owner).body())
            val a = newDevice("Pokladna A")
            val b = newDevice("Pokladna B")
            val till = BackOfficeClient(base, BackOfficeClient(base).pair(a.pairingCode!!, "test").first)
            till.syncSettings(BoSettingsUpload())

            // Another company cannot delete it.
            assertEquals(404, send("DELETE", "/devices/${a.id}", null, other).statusCode())
            assertEquals(200, send("DELETE", "/devices/${a.id}", null, owner).statusCode())
            assertEquals(404, send("DELETE", "/devices/${a.id}", null, owner).statusCode())

            val left = FlosJson.decodeFromString<List<DeviceDto>>(send("GET", "/devices", null, owner).body())
            assertEquals(listOf(b.id), left.map { it.id })
            assertTrue("the deleted till can no longer sync", runCatching { till.syncSettings(BoSettingsUpload()) }.isFailure)
            assertEquals(404, send("POST", "/devices/${a.id}/pair", null, owner).statusCode())
            assertEquals(404, send("PUT", "/devices/${a.id}", FlosJson.encodeToString(a.copy(active = true)), owner).statusCode())
            assertTrue(FlosJson.decodeFromString<TillSettingsView>(send("GET", "/till-settings", null, owner).body()).devices.none { it.id == a.id })
        } finally {
            server.stop(100, 1000)
        }
    }
}
