package cz.flos.server

import cz.flos.core.backoffice.BackOfficeClient
import cz.flos.core.backoffice.BoDeviceCatalog
import cz.flos.core.backoffice.BoDeviceCategory
import cz.flos.core.backoffice.BoDeviceProduct
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/** The whole catalogue from the back office (photos, icons, variants) and changes made on a till, the later change winning. */
class CatalogSyncTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13, 1, 2, 3)

    @Test fun photosIconsOptionsAndTwoWaySync() {
        val db = Db.inMemory().also { it.migrate() }
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, port = port, host = "127.0.0.1") { flosModule(db, openSignup = true) }.start(wait = false)
        try {
            val base = "http://127.0.0.1:$port"
            val http = HttpClient.newHttpClient()
            fun send(method: String, path: String, body: String?, token: String? = null): HttpResponse<ByteArray> {
                val b = HttpRequest.newBuilder(URI("$base$path")).header("Content-Type", "application/json")
                token?.let { b.header("Authorization", "Bearer $it") }
                b.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
                return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
            }
            fun call(method: String, path: String, body: String?, token: String? = null) = String(send(method, "/api/v1$path", body, token).body())
            fun register(name: String, email: String) = FlosJson.decodeFromString<TokenResponse>(
                call("POST", "/auth/register", FlosJson.encodeToString(RegisterRequest(name, "Majitel", email, "tajneheslo1"))),
            ).token
            val owner = register("Kavárna Flos", "majitel@flos.cz")
            val other = register("Jiná firma", "jiny@flos.cz")

            // Photos: only real images, kept per company, shown publicly by their unguessable reference.
            val upload = { token: String, bytes: ByteArray ->
                send("POST", "/api/v1/images", FlosJson.encodeToString(ImageUpload("image/png", "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes))), token)
            }
            assertEquals(400, upload(owner, "not an image at all".toByteArray()).statusCode())
            val ref = FlosJson.decodeFromString<ImageRef>(String(upload(owner, png).body())).image
            val foreign = FlosJson.decodeFromString<ImageRef>(String(upload(other, png).body())).image
            val img = send("GET", "/img/$ref", null)
            assertEquals(200, img.statusCode())
            assertArrayEquals(png, img.body())
            assertEquals(404, send("GET", "/img/1-${"0".repeat(32)}", null).statusCode())
            assertEquals(404, send("GET", "/img/nonsense", null).statusCode())

            // A product with photo, icon, variants, price lists and open price; another company's photo is refused.
            val cat = FlosJson.decodeFromString<CategoryDto>(call("POST", "/categories", FlosJson.encodeToString(CategoryDto(name = "Káva", icon = "cafe")), owner))
            val full = ProductDto(categoryId = cat.id, name = "Latte", price = 6900, vatPercent = 12, options = "Mléko: Kravské, Ovesné +10",
                prices = "Bar=6500", openPrice = false, icon = "cafe", image = ref)
            assertEquals(400, send("POST", "/api/v1/products", FlosJson.encodeToString(full.copy(image = foreign)), owner).statusCode())
            val latte = FlosJson.decodeFromString<ProductDto>(call("POST", "/products", FlosJson.encodeToString(full), owner))

            val branch = FlosJson.decodeFromString<List<BranchDto>>(call("GET", "/branches", null, owner)).single()
            val device = FlosJson.decodeFromString<DeviceDto>(call("POST", "/devices", FlosJson.encodeToString(DeviceDto(branchId = branch.id, name = "Pokladna 1")), owner))
            val (token, _) = BackOfficeClient(base).pair(device.pairingCode!!, "test")
            val till = BackOfficeClient(base, token)
            val got = till.catalog(0)
            val p = got.products.single { it.name == "Latte" }
            assertEquals("Mléko: Kravské, Ovesné +10", p.options)
            assertEquals("Bar=6500", p.prices)
            assertEquals("cafe", p.icon)
            assertEquals(ref, p.image)
            assertEquals("cafe", got.categories.single { it.id == cat.id }.icon)
            assertArrayEquals(png, till.image(ref))
            assertNull(till.image("../etc/passwd"))

            // A category and a product made on the till get back office ids.
            val now = System.currentTimeMillis()
            val made = till.uploadCatalog(BoDeviceCatalog(categories = listOf(BoDeviceCategory(7, null, "Dezerty", 0xFF112233, icon = "cake", updatedAt = now))))
            val dezerty = made.categories.getValue(7)
            val sent = till.uploadCatalog(BoDeviceCatalog(products = listOf(BoDeviceProduct(9, null, dezerty, "Dort", 8900, 12, options = "Šlehačka +15", updatedAt = now))))
            val dort = sent.products.getValue(9)
            val web = FlosJson.decodeFromString<List<ProductDto>>(call("GET", "/products", null, owner)).single { it.id == dort }
            assertEquals(dezerty, web.categoryId)
            assertEquals("Šlehačka +15", web.options)

            // An older change from the till does not overwrite a newer one from the web, a newer one does.
            val edited = FlosJson.decodeFromString<ProductDto>(call("PUT", "/products/${latte.id}", FlosJson.encodeToString(latte.copy(price = 7200)), owner))
            assertEquals(7200L, edited.price)
            till.uploadCatalog(BoDeviceCatalog(products = listOf(BoDeviceProduct(1, latte.id, cat.id, "Latte", 5000, 12, updatedAt = edited.updatedAt - 1000))))
            val kept = FlosJson.decodeFromString<List<ProductDto>>(call("GET", "/products", null, owner)).single { it.id == latte.id }
            assertEquals(7200L, kept.price)
            assertEquals(ref, kept.image)
            till.uploadCatalog(BoDeviceCatalog(products = listOf(BoDeviceProduct(1, latte.id, cat.id, "Latte", 7500, 12, options = "Mléko: Ovesné", icon = "cafe",
                updatedAt = System.currentTimeMillis() + 1000))))
            val taken = FlosJson.decodeFromString<List<ProductDto>>(call("GET", "/products", null, owner)).single { it.id == latte.id }
            assertEquals(7500L, taken.price)
            assertEquals("Mléko: Ovesné", taken.options)
            assertEquals("the till does not send photos, the web's photo stays", ref, taken.image)

            // A category deleted on the till disappears in the back office and its products lose it.
            till.uploadCatalog(BoDeviceCatalog(categories = listOf(BoDeviceCategory(-dezerty, dezerty, "", 0, deleted = true, updatedAt = System.currentTimeMillis() + 1000))))
            val cats = FlosJson.decodeFromString<List<CategoryDto>>(call("GET", "/categories", null, owner))
            assertTrue(cats.none { it.id == dezerty })
            assertNull(FlosJson.decodeFromString<List<ProductDto>>(call("GET", "/products", null, owner)).single { it.id == dort }.categoryId)

            // A till cannot touch another company's items: they are added as new ones instead.
            val otherCat = FlosJson.decodeFromString<CategoryDto>(call("POST", "/categories", FlosJson.encodeToString(CategoryDto(name = "Cizí")), other))
            till.uploadCatalog(BoDeviceCatalog(categories = listOf(BoDeviceCategory(5, otherCat.id, "Přepsáno", 0, updatedAt = System.currentTimeMillis() + 5000))))
            assertEquals("Cizí", FlosJson.decodeFromString<List<CategoryDto>>(call("GET", "/categories", null, other)).single().name)
        } finally {
            server.stop(100, 1000)
        }
    }
}
