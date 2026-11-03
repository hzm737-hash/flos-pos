package cz.flos.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * Starts the Flos back office.
 *
 * Environment:
 *  - PORT (default 8080)
 *  - DATABASE_URL, e.g. jdbc:postgresql://localhost:5432/flos  (default: a local H2 file ./data/flos)
 *  - DATABASE_USER, DATABASE_PASSWORD
 *  - FLOS_OPEN_SIGNUP=true to let anyone register a new business (default: only the first sign-up)
 */
fun main() {
    val env = System.getenv()
    val db = Db.connect(
        env["DATABASE_URL"] ?: "jdbc:h2:file:./data/flos;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        env["DATABASE_USER"], env["DATABASE_PASSWORD"],
    )
    db.migrate()
    embeddedServer(Netty, port = env["PORT"]?.toIntOrNull() ?: 8080, host = "0.0.0.0") { flosModule(db, openSignup = env["FLOS_OPEN_SIGNUP"].equals("true", ignoreCase = true)) }.start(wait = true)
}
