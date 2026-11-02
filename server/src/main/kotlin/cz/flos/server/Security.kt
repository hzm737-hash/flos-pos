package cz.flos.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class ApiError(val status: HttpStatusCode, override val message: String) : RuntimeException(message)

enum class Role { OWNER, MANAGER, CASHIER }
enum class ApiScope { READ, WRITE }

/** Who is calling: a back-office user, a paired till, or an integration with an API key. */
sealed interface Caller {
    val orgId: Long
    data class User(val userId: Long, override val orgId: Long, val role: Role) : Caller
    data class Device(val deviceId: Long, override val orgId: Long, val branchId: Long) : Caller
    data class Key(val keyId: Long, override val orgId: Long, val scope: ApiScope) : Caller
}

object Passwords {
    private const val ITERATIONS = 210_000
    private val random = SecureRandom()

    /** PBKDF2-HMAC-SHA256, stored as "pbkdf2$iterations$salt$hash". */
    fun hash(secret: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val h = derive(secret, salt, ITERATIONS)
        val b64 = Base64.getEncoder()
        return "pbkdf2\$$ITERATIONS\$${b64.encodeToString(salt)}\$${b64.encodeToString(h)}"
    }

    fun verify(secret: String, stored: String?): Boolean {
        val parts = stored?.split('$') ?: return false
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val d = Base64.getDecoder()
        val expected = d.decode(parts[3])
        return MessageDigest.isEqual(expected, derive(secret, d.decode(parts[2]), parts[1].toInt()))
    }

    private fun derive(secret: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(secret.toCharArray(), salt, iterations, 256)).encoded
}

object Tokens {
    private val random = SecureRandom()

    /** A new random token with a type prefix: u_ (user session), d_ (device), k_ (API key). */
    fun new(prefix: String): String {
        val b = ByteArray(32).also(random::nextBytes)
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    /** Tokens are stored only as SHA-256 hashes. */
    fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    fun pairingCode(): String = (0 until 8).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[random.nextInt(32)] }.joinToString("")
}

const val SESSION_DAYS = 30L

/** Resolves the bearer token of a request to a [Caller], or throws 401. */
suspend fun ApplicationCall.caller(db: Db): Caller {
    val header = request.headers["Authorization"] ?: throw ApiError(HttpStatusCode.Unauthorized, "Chybí přihlášení")
    val token = header.removePrefix("Bearer ").trim()
    val hash = Tokens.hash(token)
    val now = System.currentTimeMillis()
    return db.tx {
        when {
            token.startsWith("u_") -> queryOne(
                "SELECT s.user_id, s.org_id, u.role FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ? AND s.expires_at > ? AND u.active",
                hash, now,
            ) { Caller.User(it.getLong(1), it.getLong(2), Role.valueOf(it.getString(3))) }
            token.startsWith("d_") -> queryOne(
                "SELECT id, org_id, branch_id FROM devices WHERE token_hash = ? AND active", hash,
            ) { Caller.Device(it.getLong(1), it.getLong(2), it.getLong(3)) }?.also {
                update("UPDATE devices SET last_seen_at = ? WHERE id = ?", now, it.deviceId)
            }
            token.startsWith("k_") -> queryOne(
                "SELECT id, org_id, scope FROM api_keys WHERE key_hash = ? AND NOT revoked", hash,
            ) { Caller.Key(it.getLong(1), it.getLong(2), ApiScope.valueOf(it.getString(3))) }?.also {
                update("UPDATE api_keys SET last_used_at = ? WHERE id = ?", now, it.keyId)
            }
            else -> null
        }
    } ?: throw ApiError(HttpStatusCode.Unauthorized, "Neplatné nebo prošlé přihlášení")
}

/** Back office read access: managers, owners, and any API key. */
suspend fun ApplicationCall.reader(db: Db): Caller = caller(db).also {
    if (it is Caller.Device || (it is Caller.User && it.role == Role.CASHIER)) throw ApiError(HttpStatusCode.Forbidden, "Nedostatečná oprávnění")
}

/** Back office write access: managers, owners, and WRITE API keys. */
suspend fun ApplicationCall.writer(db: Db): Caller = reader(db).also {
    if (it is Caller.Key && it.scope != ApiScope.WRITE) throw ApiError(HttpStatusCode.Forbidden, "API klíč má jen oprávnění ke čtení")
}

/** Owner-only actions: users, API keys, company settings. */
suspend fun ApplicationCall.owner(db: Db): Caller.User {
    val c = caller(db)
    if (c !is Caller.User || c.role != Role.OWNER) throw ApiError(HttpStatusCode.Forbidden, "Tuto akci může provést jen majitel")
    return c
}

suspend fun ApplicationCall.device(db: Db): Caller.Device =
    caller(db) as? Caller.Device ?: throw ApiError(HttpStatusCode.Forbidden, "Jen pro spárované pokladny")

fun Caller.userIdOrNull(): Long? = (this as? Caller.User)?.userId
