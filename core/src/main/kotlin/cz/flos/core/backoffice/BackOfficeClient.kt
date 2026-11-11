package cz.flos.core.backoffice

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

// The till's side of the back office API (/api/v1/device/...). Field names match the server DTOs.

@Serializable data class BoOrg(val id: Long = 0, val name: String = "", val ico: String = "", val dic: String = "", val vatPayer: Boolean = false, val address: String = "")
@Serializable data class BoBranch(val id: Long = 0, val name: String = "", val address: String = "", val eetIdJednotky: Int? = null)
@Serializable data class BoMenuPublished(val path: String)
@Serializable data class BoOk(val ok: Boolean = true)
@Serializable data class BoStaff(val id: Long, val name: String, val role: String = "CASHIER", val pinHash: String? = null)
@Serializable data class BoConfig(
    val deviceId: Long, val deviceName: String = "", val eetIdPokl: String = "", val org: BoOrg = BoOrg(), val branch: BoBranch = BoBranch(),
    val staff: List<BoStaff> = emptyList(),
)
@Serializable data class BoCategory(val id: Long, val name: String, val color: Long = 0xFF2E7D6B, val sortOrder: Int = 0, val deleted: Boolean = false,
    val icon: String = "", val updatedAt: Long = 0)
@Serializable data class BoProduct(
    val id: Long, val categoryId: Long? = null, val name: String, val price: Long, val vatPercent: Int = 21, val barcode: String = "",
    val sellable: Boolean = true, val sortOrder: Int = 0, val active: Boolean = true,
    val options: String = "", val prices: String = "", val openPrice: Boolean = false, val icon: String = "", val image: String = "",
    val updatedAt: Long = 0,
)
/** A category or product created or changed on the till, sent to the back office. */
@Serializable data class BoDeviceCategory(val localId: Long, val remoteId: Long? = null, val name: String, val color: Long, val sortOrder: Int = 0,
    val icon: String = "", val deleted: Boolean = false, val updatedAt: Long = 0)
@Serializable data class BoDeviceProduct(
    val localId: Long, val remoteId: Long? = null, val categoryRemoteId: Long? = null, val name: String, val price: Long, val vatPercent: Int = 21,
    val barcode: String = "", val sortOrder: Int = 0, val active: Boolean = true, val options: String = "", val prices: String = "",
    val openPrice: Boolean = false, val icon: String = "", val updatedAt: Long = 0,
)
@Serializable data class BoDeviceCatalog(val categories: List<BoDeviceCategory> = emptyList(), val products: List<BoDeviceProduct> = emptyList())
@Serializable data class BoDeviceCatalogResult(val categories: Map<Long, Long> = emptyMap(), val products: Map<Long, Long> = emptyMap())
@Serializable data class BoCatalog(val serverTime: Long, val categories: List<BoCategory> = emptyList(), val products: List<BoProduct> = emptyList())
@Serializable data class BoSaleItem(val productId: Long? = null, val name: String, val unitPrice: Long, val vatPercent: Int, val quantity: Double, val discountPercent: Int = 0)
@Serializable data class BoSale(
    val number: String, val createdAt: Long, val total: Long, val received: Long, val payment: String, val cashier: String = "",
    val discountPercent: Int = 0, val cancelled: Boolean = false, val stornoNumber: String? = null, val eetStatus: String? = null,
    val eetPok: String? = null, val items: List<BoSaleItem> = emptyList(),
)
@Serializable data class BoUploadResult(val accepted: Int = 0, val updated: Int = 0, val serverTime: Long = 0)
@Serializable data class BoClock(val userId: Long, val name: String, val action: String, val time: Long)

@Serializable private data class PairRequest(val code: String, val appVersion: String)
@Serializable private data class PairResponse(val token: String, val config: BoConfig)
@Serializable private data class SalesUpload(val sales: List<BoSale>)
@Serializable private data class ClockRequest(val pin: String, val time: Long? = null)

class BackOfficeException(message: String, val status: Int = 0) : IOException(message)

/**
 * Talks to the Flos back office. [baseUrl] is what the owner types ("https://flos.example.cz");
 * [token] is the device token received when pairing.
 */
class BackOfficeClient(baseUrl: String, private val token: String? = null, private val timeoutMs: Int = 15_000) {
    private val base = normalize(baseUrl)
    private val api = base + "/api/v1"
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Pairs this till with the 8-character code from the back office (Tills → Pair). Returns the device token and configuration. */
    fun pair(code: String, appVersion: String): Pair<String, BoConfig> {
        val r = request("POST", "/device/pair", json.encodeToString(PairRequest.serializer(), PairRequest(code.trim().uppercase(), appVersion)), PairResponse.serializer(), auth = false)
        return r.token to r.config
    }

    fun config(): BoConfig = request("GET", "/device/config", null, BoConfig.serializer())

    /** Categories and products changed since [since] (server time of the previous call; 0 = everything). */
    fun catalog(since: Long): BoCatalog = request("GET", "/device/catalog?since=$since", null, BoCatalog.serializer())

    /** Sends categories and products created or changed on the till; returns their ids in the back office. */
    fun uploadCatalog(c: BoDeviceCatalog): BoDeviceCatalogResult =
        request("POST", "/device/catalog", json.encodeToString(BoDeviceCatalog.serializer(), c), BoDeviceCatalogResult.serializer())

    /** Sends the settings changed on this till and gets those changed in the back office; the later change wins. */
    fun syncSettings(up: BoSettingsUpload): BoSettingsResult =
        request("POST", "/device/settings", json.encodeToString(BoSettingsUpload.serializer(), up), BoSettingsResult.serializer())

    /** Sends what is going on at this till (open tables, cash, customers…) for the back office to show. */
    fun uploadSnapshot(s: BoTillSnapshot) {
        request("PUT", "/device/snapshot", json.encodeToString(BoTillSnapshot.serializer(), s), BoOk.serializer())
    }

    /** Tells the back office which version this till runs and asks for a newer one ([BoUpdateInfo.url] is absolute). */
    fun checkUpdate(platform: String, versionCode: Int, versionName: String): BoUpdateInfo {
        val q = "platform=" + java.net.URLEncoder.encode(platform, Charsets.UTF_8) + "&code=$versionCode&name=" + java.net.URLEncoder.encode(versionName, Charsets.UTF_8)
        val r = request("GET", "/device/update?$q", null, BoUpdateInfo.serializer())
        return if (r.url.startsWith("/")) r.copy(url = base + r.url) else r
    }

    /** A product photo ("<id>-<key>"), or null when it cannot be loaded. */
    fun image(ref: String): ByteArray? {
        if (!Regex("""^\d{1,18}-[0-9a-f]{32}$""").matches(ref)) return null
        val conn = URI("$base/img/$ref").toURL().openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = timeoutMs; conn.readTimeout = timeoutMs
            if (conn.responseCode != 200) null else conn.inputStream.use { it.readNBytes(2_000_000) }
        } catch (e: java.io.IOException) { null } finally { conn.disconnect() }
    }

    fun uploadSales(sales: List<BoSale>): BoUploadResult =
        request("POST", "/device/sales", json.encodeToString(SalesUpload.serializer(), SalesUpload(sales)), BoUploadResult.serializer())

    /** Clocks a staff member in or out with their PIN (attendance). */
    fun clock(pin: String): BoClock = request("POST", "/device/clock", json.encodeToString(ClockRequest.serializer(), ClockRequest(pin)), BoClock.serializer())

    /** Publishes the digital menu; returns its public address (e.g. https://flos.gnocco.cz/menu/ab12cd34ef). */
    fun publishMenu(menu: cz.flos.core.MenuData): String =
        base + request("PUT", "/device/menu", json.encodeToString(cz.flos.core.MenuData.serializer(), menu), BoMenuPublished.serializer()).path

    /** Addresses for the tables' QR codes (ordering from the table), signed by the back office. */
    fun tableLinks(tables: List<String>): List<cz.flos.core.TableLink> =
        request("POST", "/device/table-links", json.encodeToString(cz.flos.core.TableLinkRequest.serializer(), cz.flos.core.TableLinkRequest(tables)),
            cz.flos.core.TableLinkList.serializer()).links.map { it.copy(path = base + it.path) }

    /** Orders guests sent from the tables that no till has answered yet. */
    fun tableOrders(): List<cz.flos.core.TableOrder> =
        request("GET", "/device/table-orders", null, cz.flos.core.TableOrderList.serializer()).orders

    /**
     * Tells the guest's page whether the order was taken. False when the order was already answered
     * (by another till) or is gone, so it must not be put on the bill again.
     */
    fun ackTableOrder(id: Long, accepted: Boolean): Boolean =
        request("POST", "/device/table-orders/$id/ack", json.encodeToString(cz.flos.core.TableOrderAck.serializer(), cz.flos.core.TableOrderAck(accepted)), BoOk.serializer()).ok

    private fun <T> request(method: String, path: String, body: String?, serializer: KSerializer<T>, auth: Boolean = true): T {
        val conn = URI(api + path).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")
            if (auth) conn.setRequestProperty("Authorization", "Bearer " + (token ?: throw BackOfficeException("Pokladna není spárovaná")))
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                throw BackOfficeException(message ?: "HTTP $status", status)
            }
            return json.decodeFromString(serializer, text)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun normalize(url: String): String {
            var u = url.trim().trimEnd('/')
            if (u.endsWith("/api/v1")) u = u.removeSuffix("/api/v1")
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            // The device token must never travel unencrypted over the internet: plain http is kept
            // only for a back office inside the shop's own network (e.g. http://192.168.1.10:8080).
            if (u.startsWith("http://") && !isLocalHost(u.removePrefix("http://"))) u = "https://" + u.removePrefix("http://")
            return u
        }

        /** True for localhost, private IPv4 ranges and *.local names, i.e. a server inside the local network. */
        fun isLocalHost(hostAndRest: String): Boolean {
            val host = hostAndRest.substringBefore('/').substringBefore('?').let { h ->
                if (h.startsWith("[")) h.substringAfter('[').substringBefore(']') else h.substringBefore(':')
            }.lowercase()
            if (host == "localhost" || host.endsWith(".local") || host == "::1") return true
            val p = host.split('.').mapNotNull { it.toIntOrNull() }
            if (p.size != 4 || host.count { it == '.' } != 3) return false
            return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 169 && p[1] == 254)
        }

        /** Checks a staff PIN against the hash the back office sent ("pbkdf2$iterations$salt$hash"), without a network call. */
        fun verifyPin(pin: String, stored: String?): Boolean {
            val parts = stored?.split('$') ?: return false
            if (parts.size != 4 || parts[0] != "pbkdf2") return false
            val d = Base64.getDecoder()
            val salt = runCatching { d.decode(parts[2]) }.getOrNull() ?: return false
            val expected = runCatching { d.decode(parts[3]) }.getOrNull() ?: return false
            val iterations = parts[1].toIntOrNull() ?: return false
            val actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(pin.toCharArray(), salt, iterations, expected.size * 8)).encoded
            return MessageDigest.isEqual(expected, actual)
        }

        private val listJson = Json { ignoreUnknownKeys = true }
        fun staffToJson(staff: List<BoStaff>): String = listJson.encodeToString(ListSerializer(BoStaff.serializer()), staff)
        fun staffFromJson(text: String): List<BoStaff> =
            if (text.isBlank()) emptyList() else runCatching { listJson.decodeFromString(ListSerializer(BoStaff.serializer()), text) }.getOrDefault(emptyList())
    }
}
