package cz.flos.core

import cz.flos.core.backoffice.BackOfficeClient
import org.junit.Assert.assertEquals
import org.junit.Test

class SecurityTest {
    @Test fun backOfficeTokenNeverGoesOverPlainHttpOnTheInternet() {
        assertEquals("https://flos.gnocco.cz", BackOfficeClient.normalize("http://flos.gnocco.cz/"))
        assertEquals("https://flos.gnocco.cz", BackOfficeClient.normalize("flos.gnocco.cz"))
        assertEquals("https://flos.gnocco.cz", BackOfficeClient.normalize("https://flos.gnocco.cz/api/v1"))
        // Inside the shop's own network plain http still works, as before.
        assertEquals("http://192.168.1.10:8080", BackOfficeClient.normalize("http://192.168.1.10:8080"))
        assertEquals("http://10.0.0.5", BackOfficeClient.normalize("http://10.0.0.5"))
        assertEquals("http://localhost:8080", BackOfficeClient.normalize("http://localhost:8080"))
        assertEquals("http://kasa.local:8080", BackOfficeClient.normalize("http://kasa.local:8080"))
        assertEquals("https://172.40.1.1", BackOfficeClient.normalize("http://172.40.1.1"))
    }
}
