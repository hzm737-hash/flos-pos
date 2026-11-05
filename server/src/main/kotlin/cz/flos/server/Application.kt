package cz.flos.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.http.withCharset
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.time.LocalDate

val FlosJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

/**
 * @param openSignup when false (the default for a live server), "Založit firmu" works only while the
 *   database has no users yet: the first person to sign up becomes the owner, and after that new
 *   people are added by the owner under Users. Set FLOS_OPEN_SIGNUP=true to allow anyone to sign up.
 */
fun Application.flosModule(db: Db, openSignup: Boolean = false) {
    install(ContentNegotiation) { json(FlosJson) }
    install(StatusPages) {
        exception<ApiError> { call, e -> call.respond(e.status, ErrorBody(e.message)) }
        exception<SerializationException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorBody("Neplatná data: ${e.message}")) }
        exception<io.ktor.server.plugins.BadRequestException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorBody("Neplatná data: ${e.cause?.message ?: e.message}")) }
        exception<Throwable> { call, e ->
            this@flosModule.environment.log.error("Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("Chyba serveru"))
        }
    }
    routing {
        staticResources("/", "static", index = "index.html")
        get("/health") { call.respondText("ok") }
        /** Public digital menu (QR menu); rendered here from the till's data, so no foreign HTML is ever served. */
        get("/menu/{slug}") {
            val slug = call.parameters["slug"].orEmpty()
            val data = db.tx { queryOne("SELECT data FROM menus WHERE slug = ?", slug) { it.getString(1) } }
            val menu = data?.let { runCatching { FlosJson.decodeFromString(cz.flos.core.MenuData.serializer(), it) }.getOrNull() }
                ?: return@get call.respondText("Menu nenalezeno", status = HttpStatusCode.NotFound)
            call.response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src data:")
            call.response.headers.append("Cache-Control", "public, max-age=60")
            call.respondText(cz.flos.core.MenuPage.html(menu), io.ktor.http.ContentType.Text.Html.withCharset(Charsets.UTF_8))
        }
        route("/api/v1") {
            authRoutes(db, openSignup)
            orgRoutes(db)
            catalogRoutes(db)
            stockRoutes(db)
            salesRoutes(db)
            deviceRoutes(db)
        }
    }
}

// ---------- helpers ----------

private fun ApplicationCall.longParam(name: String): Long =
    parameters[name]?.toLongOrNull() ?: throw ApiError(HttpStatusCode.BadRequest, "Chybný parametr $name")

/** ?from=yyyy-mm-dd&to=yyyy-mm-dd (inclusive), default today. Returns epoch millis [from, to). */
private fun ApplicationCall.range(): Pair<Long, Long> {
    val q = request.queryParameters
    val from = q["from"]?.let(LocalDate::parse) ?: LocalDate.now(Reports.ZONE)
    val to = q["to"]?.let(LocalDate::parse) ?: from
    return from.atStartOfDay(Reports.ZONE).toInstant().toEpochMilli() to to.plusDays(1).atStartOfDay(Reports.ZONE).toInstant().toEpochMilli()
}

private fun Connection.org(orgId: Long): OrgDto = queryOne("SELECT * FROM orgs WHERE id = ?", orgId) {
    OrgDto(it.getLong("id"), it.getString("name"), it.getString("ico"), it.getString("dic"), it.getBoolean("vat_payer"), it.getString("address"))
}!!

private fun Connection.userDto(id: Long): UserDto = queryOne("SELECT * FROM users WHERE id = ?", id) {
    UserDto(it.getLong("id"), it.getString("name"), it.getString("email"), it.getString("role"), it.getBoolean("active"), it.getString("pin_hash") != null)
}!!

private fun Connection.newSession(userId: Long, orgId: Long): String {
    val token = Tokens.new("u_")
    val now = System.currentTimeMillis()
    update("INSERT INTO sessions (token_hash, user_id, org_id, created_at, expires_at) VALUES (?,?,?,?,?)",
        Tokens.hash(token), userId, orgId, now, now + SESSION_DAYS * 86_400_000)
    return token
}

// ---------- auth ----------

private suspend fun Db.signupAllowed(openSignup: Boolean): Boolean =
    openSignup || tx { queryOne("SELECT COUNT(*) FROM users") { it.getLong(1) } ?: 0L } == 0L

private fun Route.authRoutes(db: Db, openSignup: Boolean) {
    get("/auth/signup") { call.respond(SignupStatus(db.signupAllowed(openSignup))) }
    post("/auth/register") {
        if (!db.signupAllowed(openSignup)) throw ApiError(HttpStatusCode.Forbidden, "Registrace je uzavřena. Nového uživatele přidá majitel v sekci Uživatelé.")
        val r = call.receive<RegisterRequest>()
        if (r.orgName.isBlank() || r.name.isBlank() || !r.email.contains('@')) throw ApiError(HttpStatusCode.BadRequest, "Vyplňte firmu, jméno a e-mail")
        if (r.password.length < 8) throw ApiError(HttpStatusCode.BadRequest, "Heslo musí mít alespoň 8 znaků")
        val res = db.tx {
            if (queryOne("SELECT id FROM users WHERE email = ?", r.email.lowercase().trim()) { it.getLong(1) } != null) {
                throw ApiError(HttpStatusCode.Conflict, "Účet s tímto e-mailem už existuje")
            }
            val now = System.currentTimeMillis()
            val org = insert("INSERT INTO orgs (name, created_at) VALUES (?, ?)", r.orgName.trim(), now)
            val user = insert("INSERT INTO users (org_id, email, name, role, password_hash, created_at) VALUES (?,?,?,?,?,?)",
                org, r.email.lowercase().trim(), r.name.trim(), Role.OWNER, Passwords.hash(r.password), now)
            insert("INSERT INTO branches (org_id, name) VALUES (?, ?)", org, "Hlavní provozovna")
            update("INSERT INTO memberships (user_id, org_id, role) VALUES (?,?,?)", user, org, Role.OWNER)
            TokenResponse(newSession(user, org), userDto(user))
        }
        call.respond(HttpStatusCode.Created, res)
    }
    post("/auth/login") {
        val r = call.receive<LoginRequest>()
        val res = db.tx {
            val u = queryOne("SELECT id, org_id, password_hash, role FROM users WHERE email = ? AND active", r.email.lowercase().trim()) {
                Triple(it.getLong(1), it.getLong(2), it.getString(3)) to it.getString(4)
            }
            if (u == null || !Passwords.verify(r.password, u.first.third) || u.second == Role.CASHIER.name) {
                throw ApiError(HttpStatusCode.Unauthorized, "Nesprávný e-mail nebo heslo")
            }
            TokenResponse(newSession(u.first.first, u.first.second), userDto(u.first.first))
        }
        call.respond(res)
    }
    post("/auth/logout") {
        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim() ?: ""
        db.tx { update("DELETE FROM sessions WHERE token_hash = ?", Tokens.hash(token)) }
        call.respond(Ok())
    }
    get("/me") {
        val c = call.caller(db) as? Caller.User ?: throw ApiError(HttpStatusCode.Forbidden, "Jen pro uživatele")
        call.respond(db.tx { MeResponse(userDto(c.userId).copy(role = c.role.name), org(c.orgId)) })
    }

    // ---- several companies (different IČO/DIČ) under one login ----
    get("/companies") {
        val c = call.caller(db) as? Caller.User ?: throw ApiError(HttpStatusCode.Forbidden, "Jen pro uživatele")
        call.respond(db.tx {
            query("SELECT o.id, o.name, o.ico, o.dic, m.role FROM memberships m JOIN orgs o ON o.id = m.org_id WHERE m.user_id = ? ORDER BY o.name", c.userId) {
                CompanyDto(it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getLong(1) == c.orgId)
            }
        })
    }
    post("/companies") {
        val c = call.caller(db) as? Caller.User ?: throw ApiError(HttpStatusCode.Forbidden, "Jen pro uživatele")
        if (c.role != Role.OWNER) throw ApiError(HttpStatusCode.Forbidden, "Novou firmu může založit jen majitel")
        val r = call.receive<NewCompanyRequest>()
        if (r.name.isBlank()) throw ApiError(HttpStatusCode.BadRequest, "Vyplňte název firmy")
        val org = db.tx {
            val org = insert("INSERT INTO orgs (name, ico, dic, vat_payer, address, created_at) VALUES (?,?,?,?,?,?)",
                r.name.trim(), r.ico.trim(), r.dic.trim().uppercase(), r.vatPayer, r.address.trim(), System.currentTimeMillis())
            update("INSERT INTO memberships (user_id, org_id, role) VALUES (?,?,?)", c.userId, org, Role.OWNER)
            insert("INSERT INTO branches (org_id, name) VALUES (?, ?)", org, "Hlavní provozovna")
            org(org)
        }
        call.respond(HttpStatusCode.Created, org)
    }
    /** Switches the current session to another company the user belongs to. */
    post("/companies/{id}/switch") {
        val c = call.caller(db) as? Caller.User ?: throw ApiError(HttpStatusCode.Forbidden, "Jen pro uživatele")
        val id = call.longParam("id")
        val token = call.request.headers["Authorization"]!!.removePrefix("Bearer ").trim()
        val res = db.tx {
            val role = queryOne("SELECT role FROM memberships WHERE user_id = ? AND org_id = ?", c.userId, id) { it.getString(1) }
                ?: throw ApiError(HttpStatusCode.NotFound, "Firma nenalezena")
            update("UPDATE sessions SET org_id = ? WHERE token_hash = ?", id, Tokens.hash(token))
            MeResponse(userDto(c.userId).copy(role = role), org(id))
        }
        call.respond(res)
    }
}

// ---------- organisation: company, branches, devices, staff, API keys ----------

private fun Route.orgRoutes(db: Db) {
    get("/org") { val c = call.reader(db); call.respond(db.tx { org(c.orgId) }) }
    put("/org") {
        val c = call.owner(db)
        val o = call.receive<OrgDto>()
        db.tx { update("UPDATE orgs SET name = ?, ico = ?, dic = ?, vat_payer = ?, address = ? WHERE id = ?", o.name, o.ico, o.dic, o.vatPayer, o.address, c.orgId) }
        call.respond(db.tx { org(c.orgId) })
    }

    get("/branches") {
        val c = call.reader(db)
        call.respond(db.tx {
            query("SELECT * FROM branches WHERE org_id = ? ORDER BY id", c.orgId) {
                BranchDto(it.getLong("id"), it.getString("name"), it.getString("address"), it.intOrNull("eet_id_jednotky"), it.getBoolean("active"))
            }
        })
    }
    post("/branches") {
        val c = call.writer(db)
        val b = call.receive<BranchDto>()
        val id = db.tx { insert("INSERT INTO branches (org_id, name, address, eet_id_jednotky) VALUES (?,?,?,?)", c.orgId, b.name, b.address, b.eetIdJednotky) }
        call.respond(HttpStatusCode.Created, b.copy(id = id))
    }
    put("/branches/{id}") {
        val c = call.writer(db)
        val b = call.receive<BranchDto>()
        val n = db.tx { update("UPDATE branches SET name = ?, address = ?, eet_id_jednotky = ?, active = ? WHERE id = ? AND org_id = ?", b.name, b.address, b.eetIdJednotky, b.active, call.longParam("id"), c.orgId) }
        if (n == 0) throw ApiError(HttpStatusCode.NotFound, "Provozovna nenalezena")
        call.respond(b.copy(id = call.longParam("id")))
    }

    get("/devices") {
        val c = call.reader(db)
        call.respond(db.tx {
            query("SELECT * FROM devices WHERE org_id = ? ORDER BY id", c.orgId) {
                DeviceDto(it.getLong("id"), it.getLong("branch_id"), it.getString("name"), it.getString("eet_id_pokl"),
                    if ((it.longOrNull("pairing_expires_at") ?: 0) > System.currentTimeMillis()) it.getString("pairing_code") else null,
                    it.longOrNull("paired_at"), it.longOrNull("last_seen_at"), it.getString("app_version"), it.getBoolean("active"))
            }
        })
    }
    post("/devices") {
        val c = call.writer(db)
        val d = call.receive<DeviceDto>()
        val code = Tokens.pairingCode()
        val id = db.tx {
            Stock.requireBranch(this, c.orgId, d.branchId)
            insert("INSERT INTO devices (org_id, branch_id, name, eet_id_pokl, pairing_code, pairing_expires_at) VALUES (?,?,?,?,?,?)",
                c.orgId, d.branchId, d.name, d.eetIdPokl, code, System.currentTimeMillis() + PAIRING_TTL)
        }
        call.respond(HttpStatusCode.Created, d.copy(id = id, pairingCode = code))
    }
    put("/devices/{id}") {
        val c = call.writer(db)
        val d = call.receive<DeviceDto>()
        val n = db.tx {
            Stock.requireBranch(this, c.orgId, d.branchId)
            update("UPDATE devices SET name = ?, branch_id = ?, eet_id_pokl = ?, active = ? WHERE id = ? AND org_id = ?", d.name, d.branchId, d.eetIdPokl, d.active, call.longParam("id"), c.orgId)
        }
        if (n == 0) throw ApiError(HttpStatusCode.NotFound, "Pokladna nenalezena")
        call.respond(d.copy(id = call.longParam("id")))
    }
    /** New pairing code, e.g. to move the till to a new phone. The old token stops working. */
    post("/devices/{id}/pair") {
        val c = call.writer(db)
        val code = Tokens.pairingCode()
        val n = db.tx { update("UPDATE devices SET pairing_code = ?, pairing_expires_at = ?, token_hash = NULL WHERE id = ? AND org_id = ?", code, System.currentTimeMillis() + PAIRING_TTL, call.longParam("id"), c.orgId) }
        if (n == 0) throw ApiError(HttpStatusCode.NotFound, "Pokladna nenalezena")
        call.respond(mapOf("pairingCode" to code))
    }

    get("/users") {
        val c = call.reader(db)
        call.respond(db.tx { query("SELECT id FROM users WHERE org_id = ? ORDER BY id", c.orgId) { it.getLong(1) }.map { userDto(it) } })
    }
    post("/users") {
        val c = call.owner(db)
        val u = call.receive<UserWrite>()
        val role = runCatching { Role.valueOf(u.role) }.getOrNull() ?: throw ApiError(HttpStatusCode.BadRequest, "Neznámá role")
        validateStaff(u, role, isNew = true)
        val id = db.tx {
            u.email?.takeIf { it.isNotBlank() }?.let { e ->
                if (queryOne("SELECT id FROM users WHERE email = ?", e.lowercase()) { it.getLong(1) } != null) throw ApiError(HttpStatusCode.Conflict, "E-mail už je použit")
            }
            val newId = insert("INSERT INTO users (org_id, email, name, role, password_hash, pin_hash, active, created_at) VALUES (?,?,?,?,?,?,?,?)",
                c.orgId, u.email?.lowercase()?.ifBlank { null }, u.name, role, u.password?.let(Passwords::hash), u.pin?.let(Passwords::hash), u.active, System.currentTimeMillis())
            update("INSERT INTO memberships (user_id, org_id, role) VALUES (?,?,?)", newId, c.orgId, role)
            newId
        }
        call.respond(HttpStatusCode.Created, db.tx { userDto(id) })
    }
    put("/users/{id}") {
        val c = call.owner(db)
        val u = call.receive<UserWrite>()
        val role = runCatching { Role.valueOf(u.role) }.getOrNull() ?: throw ApiError(HttpStatusCode.BadRequest, "Neznámá role")
        validateStaff(u, role, isNew = false)
        val id = call.longParam("id")
        db.tx {
            val n = update("UPDATE users SET name = ?, email = ?, role = ?, active = ? WHERE id = ? AND org_id = ?", u.name, u.email?.lowercase()?.ifBlank { null }, role, u.active, id, c.orgId)
            if (n == 0) throw ApiError(HttpStatusCode.NotFound, "Uživatel nenalezen")
            update("UPDATE memberships SET role = ? WHERE user_id = ? AND org_id = ?", role, id, c.orgId)
            u.password?.takeIf { it.isNotEmpty() }?.let { update("UPDATE users SET password_hash = ? WHERE id = ?", Passwords.hash(it), id) }
            u.pin?.takeIf { it.isNotEmpty() }?.let { update("UPDATE users SET pin_hash = ? WHERE id = ?", Passwords.hash(it), id) }
            if (!u.active) update("DELETE FROM sessions WHERE user_id = ?", id)
        }
        call.respond(db.tx { userDto(id) })
    }

    get("/api-keys") {
        val c = call.owner(db)
        call.respond(db.tx {
            query("SELECT * FROM api_keys WHERE org_id = ? ORDER BY id", c.orgId) {
                ApiKeyDto(it.getLong("id"), it.getString("name"), it.getString("prefix"), it.getString("scope"), it.getLong("created_at"), it.longOrNull("last_used_at"), it.getBoolean("revoked"))
            }
        })
    }
    post("/api-keys") {
        val c = call.owner(db)
        val k = call.receive<ApiKeyDto>()
        val scope = runCatching { ApiScope.valueOf(k.scope) }.getOrNull() ?: throw ApiError(HttpStatusCode.BadRequest, "Rozsah musí být READ nebo WRITE")
        val key = Tokens.new("k_")
        val now = System.currentTimeMillis()
        val id = db.tx { insert("INSERT INTO api_keys (org_id, name, prefix, key_hash, scope, created_at) VALUES (?,?,?,?,?,?)", c.orgId, k.name, key.take(8), Tokens.hash(key), scope, now) }
        // The full key is shown only once.
        call.respond(HttpStatusCode.Created, ApiKeyDto(id, k.name, key.take(8), scope.name, now, key = key))
    }
    delete("/api-keys/{id}") {
        val c = call.owner(db)
        db.tx { update("UPDATE api_keys SET revoked = TRUE WHERE id = ? AND org_id = ?", call.longParam("id"), c.orgId) }
        call.respond(Ok())
    }

    get("/attendance") {
        val c = call.reader(db)
        val (from, to) = call.range()
        call.respond(db.tx {
            query("SELECT a.*, u.name AS user_name FROM attendance a JOIN users u ON u.id = a.user_id WHERE a.org_id = ? AND a.clock_in >= ? AND a.clock_in < ? ORDER BY a.clock_in DESC", c.orgId, from, to) {
                val out = it.longOrNull("clock_out")
                AttendanceDto(it.getLong("id"), it.getLong("user_id"), it.getString("user_name"), it.getLong("branch_id"), it.getLong("clock_in"), out,
                    out?.let { o -> (o - it.getLong("clock_in")) / 60_000 })
            }
        })
    }
}

private fun validateStaff(u: UserWrite, role: Role, isNew: Boolean) {
    if (u.name.isBlank()) throw ApiError(HttpStatusCode.BadRequest, "Vyplňte jméno")
    if (role != Role.CASHIER && u.email.isNullOrBlank()) throw ApiError(HttpStatusCode.BadRequest, "Vedoucí a majitel potřebují e-mail pro přihlášení")
    if (isNew && role != Role.CASHIER && (u.password?.length ?: 0) < 8) throw ApiError(HttpStatusCode.BadRequest, "Heslo musí mít alespoň 8 znaků")
    u.pin?.takeIf { it.isNotEmpty() }?.let { if (it.length !in 4..8 || !it.all(Char::isDigit)) throw ApiError(HttpStatusCode.BadRequest, "PIN musí mít 4–8 číslic") }
}

private const val PAIRING_TTL = 24 * 3_600_000L

// ---------- catalogue ----------

private fun Connection.productDto(rs: java.sql.ResultSet, recipe: List<RecipeItemDto>) = ProductDto(
    id = rs.getLong("id"), categoryId = rs.longOrNull("category_id"), name = rs.getString("name"), price = rs.getLong("price"),
    vatPercent = rs.getInt("vat_percent"), sku = rs.getString("sku"), barcode = rs.getString("barcode"), unit = rs.getString("unit"),
    costPrice = rs.getLong("cost_price"), trackStock = rs.getBoolean("track_stock"), sellable = rs.getBoolean("sellable"),
    minStock = rs.getLong("min_stock_milli").fromMilli(), sortOrder = rs.getInt("sort_order"), active = rs.getBoolean("active"),
    updatedAt = rs.getLong("updated_at"), recipe = recipe,
)

fun Connection.products(orgId: Long, since: Long = 0): List<ProductDto> {
    val recipes = query(
        "SELECT r.product_id, r.ingredient_id, r.qty_milli FROM recipe_items r JOIN products p ON p.id = r.product_id WHERE p.org_id = ?", orgId,
    ) { it.getLong(1) to RecipeItemDto(it.getLong(2), it.getLong(3).fromMilli()) }.groupBy({ it.first }, { it.second })
    return query("SELECT * FROM products WHERE org_id = ? AND updated_at > ? ORDER BY sort_order, name", orgId, since) { productDto(it, emptyList()) }
        .map { it.copy(recipe = recipes[it.id].orEmpty()) }
}

fun Connection.categories(orgId: Long, since: Long = 0): List<CategoryDto> =
    query("SELECT * FROM categories WHERE org_id = ? AND updated_at > ? ORDER BY sort_order, name", orgId, since) {
        CategoryDto(it.getLong("id"), it.getString("name"), it.getLong("color"), it.getInt("sort_order"), it.getBoolean("deleted"), it.getLong("updated_at"))
    }

private fun Connection.saveRecipe(orgId: Long, productId: Long, recipe: List<RecipeItemDto>) {
    update("DELETE FROM recipe_items WHERE product_id = ?", productId)
    for (r in recipe) {
        if (r.ingredientId == productId) throw ApiError(HttpStatusCode.BadRequest, "Produkt nemůže být surovinou sám sobě")
        queryOne("SELECT id FROM products WHERE id = ? AND org_id = ?", r.ingredientId, orgId) { it.getLong(1) }
            ?: throw ApiError(HttpStatusCode.BadRequest, "Neznámá surovina ${r.ingredientId}")
        update("INSERT INTO recipe_items (product_id, ingredient_id, qty_milli) VALUES (?,?,?)", productId, r.ingredientId, r.qty.toMilli())
    }
}

private fun validateProduct(p: ProductDto) {
    if (p.name.isBlank()) throw ApiError(HttpStatusCode.BadRequest, "Vyplňte název")
    if (p.price < 0) throw ApiError(HttpStatusCode.BadRequest, "Cena nesmí být záporná")
    if (p.vatPercent !in listOf(0, 12, 21)) throw ApiError(HttpStatusCode.BadRequest, "Sazba DPH musí být 0, 12 nebo 21 %")
}

private fun Route.catalogRoutes(db: Db) {
    get("/categories") { val c = call.reader(db); call.respond(db.tx { categories(c.orgId).filterNot { it.deleted } }) }
    post("/categories") {
        val c = call.writer(db)
        val x = call.receive<CategoryDto>()
        val id = db.tx { insert("INSERT INTO categories (org_id, name, color, sort_order, updated_at) VALUES (?,?,?,?,?)", c.orgId, x.name, x.color, x.sortOrder, System.currentTimeMillis()) }
        call.respond(HttpStatusCode.Created, x.copy(id = id))
    }
    put("/categories/{id}") {
        val c = call.writer(db)
        val x = call.receive<CategoryDto>()
        val n = db.tx { update("UPDATE categories SET name = ?, color = ?, sort_order = ?, updated_at = ? WHERE id = ? AND org_id = ?", x.name, x.color, x.sortOrder, System.currentTimeMillis(), call.longParam("id"), c.orgId) }
        if (n == 0) throw ApiError(HttpStatusCode.NotFound, "Kategorie nenalezena")
        call.respond(x.copy(id = call.longParam("id")))
    }
    delete("/categories/{id}") {
        val c = call.writer(db)
        val now = System.currentTimeMillis()
        db.tx {
            update("UPDATE categories SET deleted = TRUE, updated_at = ? WHERE id = ? AND org_id = ?", now, call.longParam("id"), c.orgId)
            update("UPDATE products SET category_id = NULL, updated_at = ? WHERE category_id = ? AND org_id = ?", now, call.longParam("id"), c.orgId)
        }
        call.respond(Ok())
    }

    get("/products") { val c = call.reader(db); call.respond(db.tx { products(c.orgId).filter { it.active } }) }
    get("/products/{id}") {
        val c = call.reader(db)
        call.respond(db.tx { products(c.orgId).firstOrNull { it.id == call.longParam("id") } } ?: throw ApiError(HttpStatusCode.NotFound, "Produkt nenalezen"))
    }
    post("/products") {
        val c = call.writer(db)
        val p = call.receive<ProductDto>()
        validateProduct(p)
        val id = db.tx {
            val id = insert(
                """INSERT INTO products (org_id, category_id, name, price, vat_percent, sku, barcode, unit, cost_price, track_stock, sellable, min_stock_milli, sort_order, updated_at)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                c.orgId, p.categoryId, p.name, p.price, p.vatPercent, p.sku, p.barcode, p.unit, p.costPrice, p.trackStock, p.sellable, p.minStock.toMilli(), p.sortOrder, System.currentTimeMillis(),
            )
            saveRecipe(c.orgId, id, p.recipe)
            id
        }
        call.respond(HttpStatusCode.Created, db.tx { products(c.orgId).first { it.id == id } })
    }
    put("/products/{id}") {
        val c = call.writer(db)
        val p = call.receive<ProductDto>()
        validateProduct(p)
        val id = call.longParam("id")
        db.tx {
            val n = update(
                """UPDATE products SET category_id = ?, name = ?, price = ?, vat_percent = ?, sku = ?, barcode = ?, unit = ?, cost_price = ?, track_stock = ?,
                   sellable = ?, min_stock_milli = ?, sort_order = ?, active = ?, updated_at = ? WHERE id = ? AND org_id = ?""",
                p.categoryId, p.name, p.price, p.vatPercent, p.sku, p.barcode, p.unit, p.costPrice, p.trackStock, p.sellable, p.minStock.toMilli(),
                p.sortOrder, p.active, System.currentTimeMillis(), id, c.orgId,
            )
            if (n == 0) throw ApiError(HttpStatusCode.NotFound, "Produkt nenalezen")
            saveRecipe(c.orgId, id, p.recipe)
        }
        call.respond(db.tx { products(c.orgId).first { it.id == id } })
    }
    delete("/products/{id}") {
        val c = call.writer(db)
        db.tx { update("UPDATE products SET active = FALSE, updated_at = ? WHERE id = ? AND org_id = ?", System.currentTimeMillis(), call.longParam("id"), c.orgId) }
        call.respond(Ok())
    }
}

// ---------- stock ----------

private fun Route.stockRoutes(db: Db) {
    get("/stock") {
        val c = call.reader(db)
        val branch = call.request.queryParameters["branchId"]?.toLongOrNull()
        call.respond(db.tx {
            query(
                """SELECT s.branch_id, p.id, p.name, p.unit, s.qty_milli, p.min_stock_milli, p.cost_price FROM stock_levels s
                   JOIN products p ON p.id = s.product_id WHERE p.org_id = ? AND p.active""" + (if (branch != null) " AND s.branch_id = ?" else "") + " ORDER BY p.name",
                *listOfNotNull(c.orgId, branch).toTypedArray(),
            ) {
                val qty = it.getLong(5)
                StockLevelDto(it.getLong(1), it.getLong(2), it.getString(3), it.getString(4), qty.fromMilli(), it.getLong(6).fromMilli(),
                    qty < it.getLong(6), qty * it.getLong(7) / 1000)
            }
        })
    }
    post("/stock/movements") {
        val c = call.writer(db)
        val req = call.receive<StockMovementRequest>()
        db.tx { Stock.applyRequest(this, c.orgId, req, c.userIdOrNull()) }
        call.respond(Ok())
    }
    get("/stock/movements") {
        val c = call.reader(db)
        val (from, to) = call.range()
        val branch = call.request.queryParameters["branchId"]?.toLongOrNull()
        call.respond(db.tx {
            query(
                "SELECT m.*, p.name AS product_name FROM stock_movements m JOIN products p ON p.id = m.product_id WHERE m.org_id = ? AND m.created_at >= ? AND m.created_at < ?" +
                    (if (branch != null) " AND m.branch_id = ?" else "") + " ORDER BY m.id DESC LIMIT 2000",
                *listOfNotNull(c.orgId, from, to, branch).toTypedArray(),
            ) {
                StockMovementDto(it.getLong("id"), it.getLong("branch_id"), it.getLong("product_id"), it.getString("product_name"), it.getLong("qty_milli").fromMilli(),
                    it.getString("kind"), it.getString("note"), it.getString("ref"), it.getLong("created_at"))
            }
        })
    }
}

// ---------- sales, reports, exports, EET ----------

private fun Route.salesRoutes(db: Db) {
    get("/sales") {
        val c = call.reader(db)
        val (from, to) = call.range()
        val branch = call.request.queryParameters["branchId"]?.toLongOrNull()
        call.respond(db.tx { Sales.list(this, c.orgId, from, to, branch, limit = 5000) })
    }
    get("/reports/summary") {
        val c = call.reader(db)
        val (from, to) = call.range()
        val branch = call.request.queryParameters["branchId"]?.toLongOrNull()
        call.respond(db.tx { Reports.summary(this, c.orgId, from, to, branch) })
    }
    get("/export/sales.csv") {
        val c = call.reader(db)
        val (from, to) = call.range()
        val csv = db.tx {
            Reports.salesCsv(Sales.list(this, c.orgId, from, to, null), query("SELECT id, name FROM branches WHERE org_id = ?", c.orgId) { it.getLong(1) to it.getString(2) }.toMap())
        }
        call.response.header("Content-Disposition", "attachment; filename=\"flos-trzby.csv\"")
        call.respondText(csv, ContentType.parse("text/csv; charset=utf-8"))
    }
    get("/export/items.csv") {
        val c = call.reader(db)
        val (from, to) = call.range()
        val csv = db.tx { Reports.itemsCsv(Sales.list(this, c.orgId, from, to, null)) }
        call.response.header("Content-Disposition", "attachment; filename=\"flos-polozky.csv\"")
        call.respondText(csv, ContentType.parse("text/csv; charset=utf-8"))
    }
    get("/export/pohoda.xml") {
        val c = call.reader(db)
        val (from, to) = call.range()
        val xml = db.tx { Reports.pohodaXml(org(c.orgId), Sales.list(this, c.orgId, from, to, null)) }
        call.response.header("Content-Disposition", "attachment; filename=\"flos-pohoda.xml\"")
        call.respondText(xml, ContentType.parse("application/xml; charset=utf-8"))
    }
    get("/eet") {
        val c = call.reader(db)
        val (from, to) = call.range()
        call.respond(db.tx {
            val sales = Sales.list(this, c.orgId, from, to, null, withItems = false)
            EetOverview(
                confirmed = sales.count { it.eetStatus == "CONFIRMED" },
                pending = sales.count { it.eetStatus == "PENDING" },
                rejected = sales.count { it.eetStatus == "REJECTED" },
                other = sales.count { it.eetStatus !in listOf("CONFIRMED", "PENDING", "REJECTED") },
                problems = sales.filter { it.eetStatus == "PENDING" || it.eetStatus == "REJECTED" }.take(200),
            )
        })
    }
}

// ---------- tills ----------

private fun Route.deviceRoutes(db: Db) {
    post("/device/pair") {
        val r = call.receive<PairRequest>()
        val token = Tokens.new("d_")
        val res = db.tx {
            val id = queryOne("SELECT id FROM devices WHERE pairing_code = ? AND pairing_expires_at > ? AND active", r.code.trim().uppercase(), System.currentTimeMillis()) { it.getLong(1) }
                ?: throw ApiError(HttpStatusCode.NotFound, "Neplatný nebo prošlý párovací kód")
            update("UPDATE devices SET token_hash = ?, pairing_code = NULL, pairing_expires_at = NULL, paired_at = ?, app_version = ? WHERE id = ?",
                Tokens.hash(token), System.currentTimeMillis(), r.appVersion, id)
            deviceConfig(id)
        }
        call.respond(PairResponse(token, res))
    }
    get("/device/config") {
        val d = call.device(db)
        call.respond(db.tx { deviceConfig(d.deviceId) })
    }
    get("/device/catalog") {
        val d = call.device(db)
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0
        val now = System.currentTimeMillis()
        call.respond(db.tx { CatalogResponse(now, categories(d.orgId, since), products(d.orgId, since)) })
    }
    post("/device/sales") {
        val d = call.device(db)
        val up = call.receive<SalesUpload>()
        if (up.sales.size > 500) throw ApiError(HttpStatusCode.BadRequest, "Nejvýše 500 účtenek najednou")
        call.respond(db.tx { Sales.ingest(this, d, up.sales) })
    }
    get("/device/stock") {
        val d = call.device(db)
        call.respond(db.tx {
            query("SELECT product_id, qty_milli FROM stock_levels WHERE branch_id = ?", d.branchId) { DeviceStock(it.getLong(1), it.getLong(2).fromMilli()) }
        })
    }
    /** The till publishes the digital menu; the link stays the same for the company. */
    put("/device/menu") {
        val d = call.device(db)
        val menu = call.receive<cz.flos.core.MenuData>()
        if (menu.categories.sumOf { it.items.size } > 2000) throw ApiError(HttpStatusCode.BadRequest, "Menu je příliš velké")
        val json = FlosJson.encodeToString(cz.flos.core.MenuData.serializer(), menu)
        val slug = db.tx {
            val existing = queryOne("SELECT slug FROM menus WHERE org_id = ?", d.orgId) { it.getString(1) }
            if (existing != null) {
                update("UPDATE menus SET data = ?, updated_at = ? WHERE org_id = ?", json, System.currentTimeMillis(), d.orgId)
                existing
            } else {
                val slug = Tokens.new("").filter(Char::isLetterOrDigit).lowercase().take(12)
                update("INSERT INTO menus (org_id, slug, data, updated_at) VALUES (?,?,?,?)", d.orgId, slug, json, System.currentTimeMillis())
                slug
            }
        }
        call.respond(MenuPublished("/menu/$slug"))
    }
    /** Staff clock in/out on the till with their PIN. */
    post("/device/clock") {
        val d = call.device(db)
        val r = call.receive<ClockRequest>()
        val res = db.tx {
            val staff = query("SELECT id, name, pin_hash FROM users WHERE org_id = ? AND active AND pin_hash IS NOT NULL", d.orgId) {
                Triple(it.getLong(1), it.getString(2), it.getString(3))
            }.firstOrNull { Passwords.verify(r.pin, it.third) } ?: throw ApiError(HttpStatusCode.Unauthorized, "Neplatný PIN")
            val time = r.time ?: System.currentTimeMillis()
            val open = queryOne("SELECT id FROM attendance WHERE user_id = ? AND clock_out IS NULL ORDER BY clock_in DESC", staff.first) { it.getLong(1) }
            if (open != null) {
                update("UPDATE attendance SET clock_out = ? WHERE id = ?", time, open)
                ClockResponse(staff.first, staff.second, "OUT", time)
            } else {
                insert("INSERT INTO attendance (org_id, branch_id, user_id, clock_in, device_id) VALUES (?,?,?,?,?)", d.orgId, d.branchId, staff.first, time, d.deviceId)
                ClockResponse(staff.first, staff.second, "IN", time)
            }
        }
        call.respond(res)
    }
}

private fun Connection.deviceConfig(deviceId: Long): DeviceConfig {
    val d = queryOne("SELECT * FROM devices WHERE id = ?", deviceId) { Triple(it.getLong("org_id"), it.getLong("branch_id"), it.getString("name") to it.getString("eet_id_pokl")) }!!
    val branch = queryOne("SELECT * FROM branches WHERE id = ?", d.second) {
        BranchDto(it.getLong("id"), it.getString("name"), it.getString("address"), it.intOrNull("eet_id_jednotky"), it.getBoolean("active"))
    }!!
    val staff = query("SELECT id, name, role, pin_hash FROM users WHERE org_id = ? AND active ORDER BY name", d.first) {
        StaffDto(it.getLong(1), it.getString(2), it.getString(3), it.getString(4))
    }
    return DeviceConfig(deviceId, d.third.first, d.third.second, org(d.first), branch, staff)
}
