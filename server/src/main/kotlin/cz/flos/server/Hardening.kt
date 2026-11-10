package cz.flos.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.respond
import java.util.concurrent.ConcurrentHashMap

/**
 * Extra protection for the live back office, added on top of the existing routes:
 *  - security headers on every answer (HTTPS only, no framing, no content sniffing, strict CSP for the web admin);
 *  - API answers are never cached by the browser or a proxy;
 *  - very large request bodies are refused before they are read;
 *  - repeated wrong passwords, pairing codes and PINs are slowed down (see [Throttle]).
 */
fun Application.installHardening(maxBodyBytes: Long = MAX_BODY_BYTES) {
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        val h = call.response.headers
        h.append("Strict-Transport-Security", "max-age=31536000")
        h.append("X-Content-Type-Options", "nosniff")
        h.append("X-Frame-Options", "DENY")
        h.append("Referrer-Policy", "no-referrer")
        h.append("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()")
        h.append("Cross-Origin-Opener-Policy", "same-origin")
        if (path.startsWith("/api/")) {
            h.append("Cache-Control", "no-store")
        } else if (!path.startsWith("/menu/")) {
            // The web admin loads only its own files; the public menu sets its own, stricter policy.
            h.append(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
                    "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'",
            )
        }
        val length = call.request.headers["Content-Length"]?.toLongOrNull()
        if (length != null && length > maxBodyBytes) {
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorBody("Požadavek je příliš velký"))
            finish()
        }
    }
}

const val MAX_BODY_BYTES = 20L * 1024 * 1024

/** The caller's address; behind the Plesk proxy that is the last X-Forwarded-For entry, added by the proxy itself. */
fun ApplicationCall.clientIp(): String =
    request.headers["X-Forwarded-For"]?.split(',')?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        ?: request.local.remoteAddress

/**
 * Counts failed attempts per key (e-mail, address, till) and blocks the key for the rest of the
 * window once it has too many. A success clears the count. Kept in memory: a restart forgets it,
 * which is fine for slowing down guessing.
 */
class Throttle(private val now: () -> Long = System::currentTimeMillis) {
    private class Bucket(var start: Long, var failures: Int)
    private val buckets = ConcurrentHashMap<String, Bucket>()

    /** Throws 429 while [key] is blocked. */
    fun check(key: String, maxFailures: Int, windowMs: Long) {
        val b = buckets[key] ?: return
        val t = now()
        synchronized(b) {
            if (t - b.start > windowMs) { buckets.remove(key, b); return }
            if (b.failures >= maxFailures) {
                val minutes = ((b.start + windowMs - t) / 60_000 + 1)
                throw ApiError(HttpStatusCode.TooManyRequests, "Příliš mnoho pokusů. Zkuste to znovu za $minutes min.")
            }
        }
    }

    fun fail(key: String, windowMs: Long) {
        val t = now()
        val b = buckets.computeIfAbsent(key) { Bucket(t, 0) }
        synchronized(b) {
            if (t - b.start > windowMs) { b.start = t; b.failures = 0 }
            b.failures++
        }
        if (buckets.size > 100_000) cleanup(windowMs)
    }

    fun success(key: String) { buckets.remove(key) }

    private fun cleanup(windowMs: Long) {
        val t = now()
        buckets.entries.removeIf { t - it.value.start > windowMs }
    }

    companion object {
        const val WINDOW = 15 * 60_000L
        /** Wrong passwords for one e-mail before it is paused. */
        const val LOGIN_PER_ACCOUNT = 10
        /** Wrong passwords / pairing codes from one address (all accounts together). */
        const val PER_ADDRESS = 50
        /** Wrong PINs on one till. */
        const val PIN_PER_DEVICE = 10
    }
}

/** Runs [block]; an [ApiError] with status 401/404 counts as a failed attempt for all [keys]. */
suspend fun <T> Throttle.guard(keys: List<Pair<String, Int>>, block: suspend () -> T): T {
    keys.forEach { (k, max) -> check(k, max, Throttle.WINDOW) }
    return try {
        // A success clears the per-account count, but not the per-address one.
        block().also { keys.filterNot { it.first.startsWith("ip:") }.forEach { success(it.first) } }
    } catch (e: ApiError) {
        if (e.status == HttpStatusCode.Unauthorized || e.status == HttpStatusCode.NotFound) keys.forEach { fail(it.first, Throttle.WINDOW) }
        throw e
    }
}
