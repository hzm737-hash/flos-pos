package cz.flos.server

import io.ktor.client.request.head
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Test

/** Google Play checks the privacy policy URL with a HEAD request; it must answer 200 like GET. */
class HeadRequestTest {
    @Test fun privacyPageAnswersHead() = testApplication {
        val db = Db.inMemory().also { it.migrate() }
        application { flosModule(db) }
        assertEquals(HttpStatusCode.OK, client.head("/privacy.html").status)
        assertEquals(HttpStatusCode.OK, client.head("/").status)
        assertEquals(HttpStatusCode.OK, client.head("/health").status)
    }
}
