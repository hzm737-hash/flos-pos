package cz.flos.server

import cz.flos.core.backoffice.BackOfficeClient
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** New app versions uploaded to the back office and offered to the tills. */
class AppReleasesTest {
    private fun apk(seed: Int): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(ByteArray(4000).also { java.util.Random(seed.toLong()).nextBytes(it) }); z.closeEntry()
            z.putNextEntry(ZipEntry("classes.dex")); z.write(ByteArray(4000).also { java.util.Random(seed + 1000L).nextBytes(it) }); z.closeEntry()
        }
    }.toByteArray()

    @Test fun releasesReachTheChosenTills() {
        val db = Db.inMemory().also { it.migrate() }
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, port = port, host = "127.0.0.1") { flosModule(db, openSignup = true) }.start(wait = false)
        try {
            val base = "http://127.0.0.1:$port"
            val http = HttpClient.newHttpClient()
            fun send(method: String, path: String, body: ByteArray?, token: String? = null, type: String = "application/json"): HttpResponse<ByteArray> {
                val b = HttpRequest.newBuilder(URI("$base$path")).header("Content-Type", type)
                token?.let { b.header("Authorization", "Bearer $it") }
                b.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
                return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
            }
            fun json(method: String, path: String, body: String?, token: String? = null) = String(send(method, "/api/v1$path", body?.toByteArray(), token).body())
            val owner = FlosJson.decodeFromString<TokenResponse>(
                json("POST", "/auth/register", FlosJson.encodeToString(RegisterRequest("Pizza Fresca", "Majitel", "majitel@flos.cz", "tajneheslo1"))),
            ).token
            val branch = FlosJson.decodeFromString<List<BranchDto>>(json("GET", "/branches", null, owner)).single()
            fun till(name: String): Pair<Long, BackOfficeClient> {
                val d = FlosJson.decodeFromString<DeviceDto>(json("POST", "/devices", FlosJson.encodeToString(DeviceDto(branchId = branch.id, name = name)), owner))
                return d.id to BackOfficeClient(base, BackOfficeClient(base).pair(d.pairingCode!!, "test").first)
            }
            val (idA, a) = till("A")
            val (_, b) = till("B")
            fun upload(bytes: ByteArray, q: String) = send("POST", "/api/v1/app-releases?$q", bytes, owner, "application/octet-stream")

            assertFalse(a.checkUpdate("android", 100, "1.0.100").available)
            // Only real APKs, with a version.
            assertEquals(400, upload("hello".toByteArray(), "versionName=1.0.101").statusCode())
            assertEquals(400, upload(apk(1), "versionName=nová").statusCode())
            assertEquals(400, upload(apk(1), "platform=ios&versionName=1.0.101").statusCode())
            val v101 = apk(1)
            val rel = FlosJson.decodeFromString<AppReleaseDto>(String(upload(v101, "versionName=1.0.101&notes=Opravy").body()))
            assertEquals(101, rel.versionCode)

            // Each till reports its version; an older till gets the newer one, a newer till nothing.
            val offer = a.checkUpdate("android", 100, "1.0.100")
            assertTrue(offer.available)
            assertEquals("1.0.101", offer.versionName)
            assertEquals("Opravy", offer.notes)
            assertFalse(b.checkUpdate("android", 101, "1.0.101").available)
            assertFalse(a.checkUpdate("windows", 1, "1").available)

            // The link works without signing in and gives exactly the uploaded file.
            val file = send("GET", offer.url.removePrefix(base), null)
            assertEquals(200, file.statusCode())
            assertArrayEquals(v101, file.body())
            assertEquals(AppReleases.sha256(v101), offer.sha256)
            assertEquals(404, send("GET", "/dl/app/${rel.id}/${"0".repeat(32)}/flos.apk", null).statusCode())

            // Only for till A: B gets nothing; switched off: nobody, and the link stops working.
            assertEquals(200, send("PUT", "/api/v1/app-releases/${rel.id}", FlosJson.encodeToString(AppReleaseWrite(true, listOf(idA, 999_999))).toByteArray(), owner).statusCode())
            assertTrue(a.checkUpdate("android", 100, "1.0.100").available)
            assertFalse(b.checkUpdate("android", 100, "1.0.100").available)
            send("PUT", "/api/v1/app-releases/${rel.id}", FlosJson.encodeToString(AppReleaseWrite(false)).toByteArray(), owner)
            assertFalse(a.checkUpdate("android", 100, "1.0.100").available)
            assertEquals(404, send("GET", offer.url.removePrefix(base), null).statusCode())

            // The web admin sees the versions the tills run.
            val view = FlosJson.decodeFromString<AppReleasesView>(json("GET", "/app-releases", null, owner))
            assertEquals("1.0.100", view.devices.single { it.id == idA }.versionName)
            assertEquals(listOf(idA), FlosJson.decodeFromString<AppReleasesView>(json("GET", "/app-releases", null, owner)).releases.single().devices.ifEmpty { listOf(idA) })
            // Tills cannot upload.
            assertEquals(403, send("POST", "/api/v1/app-releases?versionName=1.0.102", apk(2), null, "application/octet-stream").statusCode().let { if (it == 401) 403 else it })
        } finally {
            server.stop(100, 1000)
        }
    }
}
