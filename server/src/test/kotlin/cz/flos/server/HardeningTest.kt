package cz.flos.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardeningTest {
    private fun run(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        val db = Db.inMemory().also { it.migrate() }
        application { flosModule(db, openSignup = true) }
        block(createClient { install(ContentNegotiation) { json(FlosJson) } })
    }

    private suspend fun HttpClient.login(email: String, password: String) = post("/api/v1/auth/login") {
        contentType(ContentType.Application.Json); setBody(LoginRequest(email, password))
    }

    private suspend fun HttpClient.register(): String = post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json); setBody(RegisterRequest("Kavárna", "Hazem", "majitel@flos.cz", "tajneheslo1"))
    }.body<TokenResponse>().token

    @Test fun securityHeaders() = run { client ->
        val page = client.get("/")
        assertNotNull(page.headers["Content-Security-Policy"])
        assertEquals("DENY", page.headers["X-Frame-Options"])
        assertEquals("nosniff", page.headers["X-Content-Type-Options"])
        assertNotNull(page.headers["Strict-Transport-Security"])
        assertEquals("no-store", client.get("/api/v1/auth/signup").headers["Cache-Control"])
    }

    @Test fun tooManyWrongPasswordsPauseTheAccount() = run { client ->
        client.register()
        repeat(Throttle.LOGIN_PER_ACCOUNT) { assertEquals(HttpStatusCode.Unauthorized, client.login("majitel@flos.cz", "spatne$it").status) }
        // Even the right password waits until the pause ends.
        assertEquals(HttpStatusCode.TooManyRequests, client.login("majitel@flos.cz", "tajneheslo1").status)
    }

    @Test fun rightPasswordClearsTheCount() = run { client ->
        client.register()
        repeat(Throttle.LOGIN_PER_ACCOUNT - 1) { client.login("majitel@flos.cz", "spatne") }
        assertEquals(HttpStatusCode.OK, client.login("majitel@flos.cz", "tajneheslo1").status)
        repeat(Throttle.LOGIN_PER_ACCOUNT - 1) { assertEquals(HttpStatusCode.Unauthorized, client.login("majitel@flos.cz", "spatne").status) }
    }

    @Test fun pairingCodeGuessingIsSlowedDown() = run { client ->
        repeat(Throttle.PER_ADDRESS) {
            client.post("/api/v1/device/pair") { contentType(ContentType.Application.Json); setBody(PairRequest("AAAAAAAA", "t")) }
        }
        val r = client.post("/api/v1/device/pair") { contentType(ContentType.Application.Json); setBody(PairRequest("AAAAAAAA", "t")) }
        assertEquals(HttpStatusCode.TooManyRequests, r.status)
    }

    @Test fun hugeBodiesAreRefused() = run { client ->
        val r = client.post("/api/v1/auth/login") { header("Content-Length", (MAX_BODY_BYTES + 1).toString()); contentType(ContentType.Application.Json); setBody("x".repeat(10)) }
        assertTrue(r.status == HttpStatusCode.PayloadTooLarge || r.status == HttpStatusCode.BadRequest)
    }

    @Test fun newPasswordSignsOutOtherSessions() = run { client ->
        val token = client.register()
        val other = client.login("majitel@flos.cz", "tajneheslo1").body<TokenResponse>().token
        val me = client.get("/api/v1/me") { bearerAuth(token) }.body<MeResponse>()
        client.put("/api/v1/users/${me.user.id}") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(UserWrite(name = "Hazem", email = "majitel@flos.cz", role = "OWNER", password = "noveheslo22"))
        }
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/me") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { bearerAuth(other) }.status)
    }
}
