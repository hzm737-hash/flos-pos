package cz.flos.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import javax.sql.DataSource

/** Thin JDBC layer: transactions, parameter binding and versioned schema migrations. */
class Db(val dataSource: DataSource) {

    /** Runs [block] in one transaction on the IO dispatcher. */
    suspend fun <T> tx(block: Connection.() -> T): T = withContext(Dispatchers.IO) { txBlocking(block) }

    fun <T> txBlocking(block: Connection.() -> T): T = dataSource.connection.use { c ->
        c.autoCommit = false
        try {
            val r = c.block()
            c.commit()
            r
        } catch (e: Throwable) {
            c.rollback()
            throw e
        }
    }

    /** Applies db/V<n>__*.sql files from the classpath that have not been applied yet. */
    fun migrate() = txBlocking {
        createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS schema_version (version INT PRIMARY KEY, applied_at BIGINT NOT NULL)") }
        val done = query("SELECT version FROM schema_version") { it.getInt(1) }.toSet()
        for (v in MIGRATIONS.indices) {
            val version = v + 1
            if (version in done) continue
            val sql = Db::class.java.getResource("/db/${MIGRATIONS[v]}")!!.readText()
            sql.split(';').map { stmt -> stmt.lines().filterNot { it.trim().startsWith("--") }.joinToString("\n").trim() }
                .filter { it.isNotEmpty() }
                .forEach { stmt -> createStatement().use { it.execute(stmt) } }
            update("INSERT INTO schema_version (version, applied_at) VALUES (?, ?)", version, System.currentTimeMillis())
        }
    }

    companion object {
        private val MIGRATIONS = listOf("V1__schema.sql", "V2__memberships.sql", "V3__fractional_quantity.sql", "V4__menus.sql", "V5__table_orders.sql", "V6__catalog_full.sql", "V7__till_settings.sql", "V8__till_snapshots.sql", "V9__app_releases.sql", "V10__device_deleted.sql", "V11__web_orders.sql", "V12__order_control.sql", "V13__web_preorders.sql")

        /**
         * DATABASE_URL like jdbc:postgresql://host:5432/flos (with DATABASE_USER / DATABASE_PASSWORD),
         * or an H2 URL for local testing.
         */
        fun connect(url: String, user: String? = null, password: String? = null): Db {
            val cfg = HikariConfig().apply {
                jdbcUrl = url
                user?.let { username = it }
                password?.let { this.password = it }
                maximumPoolSize = 10
            }
            return Db(HikariDataSource(cfg))
        }

        fun inMemory(name: String = "flos" + System.nanoTime()): Db =
            connect("jdbc:h2:mem:$name;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
    }
}

private fun PreparedStatement.bind(params: Array<out Any?>) {
    params.forEachIndexed { i, p ->
        when (p) {
            null -> setObject(i + 1, null)
            is Long -> setLong(i + 1, p)
            is Int -> setInt(i + 1, p)
            is Boolean -> setBoolean(i + 1, p)
            is String -> setString(i + 1, p)
            is ByteArray -> setBytes(i + 1, p)
            is Enum<*> -> setString(i + 1, p.name)
            else -> setObject(i + 1, p)
        }
    }
}

fun <T> Connection.query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { st ->
        st.bind(params)
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

fun <T> Connection.queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? = query(sql, *params, map = map).firstOrNull()

fun Connection.update(sql: String, vararg params: Any?): Int = prepareStatement(sql).use { st ->
    st.bind(params)
    st.executeUpdate()
}

/** INSERT returning the generated id. */
fun Connection.insert(sql: String, vararg params: Any?): Long = prepareStatement(sql, arrayOf("id")).use { st ->
    st.bind(params)
    st.executeUpdate()
    st.generatedKeys.use { rs -> rs.next(); rs.getLong(1) }
}

fun ResultSet.longOrNull(col: String): Long? = getLong(col).let { if (wasNull()) null else it }
fun ResultSet.intOrNull(col: String): Int? = getInt(col).let { if (wasNull()) null else it }

