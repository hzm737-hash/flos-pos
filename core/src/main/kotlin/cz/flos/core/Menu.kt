package cz.flos.core

import kotlinx.serialization.Serializable

@Serializable data class MenuItem(
    val name: String,
    val price: Long,
    val note: String = "",
    /** Product id on the till, so a guest can order it (0 in menus published before ordering existed). */
    val id: Long = 0,
    /** Variants and extras in the [ProductOptions] text form, offered when ordering. */
    val options: String = "",
    /** Photo in the back office as "<id>-<key>" (shown from /img/...); "" = none. */
    val image: String = "",
)
@Serializable data class MenuCategory(val name: String, val items: List<MenuItem>)
@Serializable data class MenuData(
    val name: String,
    val address: String = "",
    val contacts: List<String> = emptyList(),
    val hours: String = "",
    val categories: List<MenuCategory> = emptyList(),
    /** Footer, e.g. "Ceny včetně DPH" or allergens info. */
    val note: String = "",
    /** Guests may order from the menu with a table's QR code (Pokročilé nastavení → Objednávky z QR). */
    val ordering: Boolean = false,
    /** The restaurant's website may send delivery and pick-up orders (Pokročilé nastavení → Objednávky z webu). */
    val webOrdering: Boolean = false,
)

/** The digital menu as one mobile-friendly HTML page (no scripts), for the QR code on the tables. */
object MenuPage {
    /** A photo reference from the back office; anything else is never put into the page. */
    private val IMAGE_REF = Regex("""^\d{1,18}-[0-9a-f]{32}$""")
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    /**
     * The page; with [table] and its [key] (from the table's QR code) and ordering switched on, every
     * dish gets a "+" button and the page sends the order with /menu-order.js.
     */
    fun html(m: MenuData, table: String? = null, key: String? = null, slug: String = ""): String = buildString {
        val ordering = m.ordering && !table.isNullOrBlank() && !key.isNullOrBlank()
        append("<!doctype html><html lang=\"cs\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        append("<title>").append(esc(m.name)).append(" – menu</title><style>")
        append(":root{--bg:#fbf8f3;--fg:#1f1d1a;--muted:#6b665e;--line:#e6dfd3;--accent:#123c69}")
        append("@media(prefers-color-scheme:dark){:root{--bg:#141311;--fg:#f1ece4;--muted:#a59e93;--line:#2c2924;--accent:#7fb2e5}}")
        append("*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}")
        append("main{max-width:640px;margin:0 auto;padding:24px 16px 48px}h1{margin:0 0 4px;font-size:28px;color:var(--accent)}")
        append(".meta{color:var(--muted);font-size:14px;white-space:pre-line}nav{display:flex;gap:8px;overflow-x:auto;padding:16px 0;position:sticky;top:0;background:var(--bg)}")
        append("nav a{flex:none;padding:6px 12px;border:1px solid var(--line);border-radius:999px;color:var(--fg);text-decoration:none;font-size:14px}")
        append("h2{margin:28px 0 8px;font-size:20px}.item{display:flex;gap:12px;padding:10px 0;border-bottom:1px solid var(--line)}")
        append(".item .n{flex:1}.item .p{font-weight:600;white-space:nowrap}.note{display:block;color:var(--muted);font-size:14px}.ph{flex:none;width:64px;height:64px;border-radius:10px;object-fit:cover}footer{margin-top:32px;color:var(--muted);font-size:13px}")
        if (ordering) append(ORDER_CSS)
        append("</style></head><body><main><h1>").append(esc(m.name)).append("</h1>")
        if (ordering) append("<div class=\"table\" id=\"table-banner\">").append(esc("Stůl " + table)).append("</div>")
        val meta = listOf(m.address, m.contacts.joinToString(" · "), m.hours).filter { it.isNotBlank() }
        if (meta.isNotEmpty()) append("<div class=\"meta\">").append(meta.joinToString("\n") { esc(it) }).append("</div>")
        val cats = m.categories.filter { it.items.isNotEmpty() }
        if (cats.size > 1) {
            append("<nav>")
            cats.forEachIndexed { i, c -> append("<a href=\"#c$i\">").append(esc(c.name)).append("</a>") }
            append("</nav>")
        }
        cats.forEachIndexed { i, c ->
            append("<h2 id=\"c$i\">").append(esc(c.name)).append("</h2>")
            c.items.forEach { it ->
                append("<div class=\"item\">")
                if (IMAGE_REF.matches(it.image)) append("<img class=\"ph\" alt=\"\" loading=\"lazy\" src=\"/img/").append(it.image).append("\">")
                append("<span class=\"n\">").append(esc(it.name))
                if (it.note.isNotBlank()) append("<span class=\"note\">").append(esc(it.note)).append("</span>")
                append("</span><span class=\"p\">").append(esc(Money.format(it.price))).append("</span>")
                if (ordering && it.id > 0) append("<button class=\"add\" type=\"button\" data-id=\"").append(it.id).append("\" aria-label=\"+\">+</button>")
                append("</div>")
            }
        }
        append("<footer>").append(esc(m.note.ifBlank { "Ceny jsou uvedeny včetně DPH." })).append("</footer></main>")
        if (ordering) {
            append("<div id=\"cart\" hidden></div><div id=\"sheet\" hidden></div>")
            // Data for the script; "<" is escaped so the text can never end the script element.
            append("<script type=\"application/json\" id=\"flos-order\">").append(orderJson(m, table!!, key!!, slug).replace("<", "\\u003c")).append("</script>")
            append("<script src=\"/menu-order.js\" defer></script>")
        }
        append("</body></html>")
    }

    @Serializable private data class OChoice(val n: String, val p: Long)
    @Serializable private data class OGroup(val n: String, val m: Boolean, val c: List<OChoice>)
    @Serializable private data class OItem(val n: String, val p: Long, val g: List<OGroup>)
    @Serializable private data class OData(val table: String, val key: String, val slug: String, val items: Map<String, OItem>)
    private val orderJsonFormat = kotlinx.serialization.json.Json { encodeDefaults = true }

    private fun orderJson(m: MenuData, table: String, key: String, slug: String): String {
        val items = m.categories.flatMap { it.items }.filter { it.id > 0 }.associate { i ->
            i.id.toString() to OItem(i.name, i.price, ProductOptions.parse(i.options).map { g -> OGroup(g.name, g.multiple, g.choices.map { OChoice(it.name, it.price) }) })
        }
        return orderJsonFormat.encodeToString(OData.serializer(), OData(table, key, slug, items))
    }

    private const val ORDER_CSS =
        ".table{display:inline-block;margin-top:10px;padding:4px 12px;border-radius:999px;background:var(--accent);color:var(--bg);font-weight:600}" +
        ".item{align-items:center}.add{flex:none;width:36px;height:36px;border-radius:50%;border:0;background:var(--accent);color:var(--bg);font-size:22px;line-height:1;cursor:pointer}" +
        "main{padding-bottom:110px}#cart{position:fixed;left:0;right:0;bottom:0;padding:12px 16px;background:var(--bg);border-top:1px solid var(--line);display:flex;gap:12px;align-items:center;justify-content:center}" +
        "#cart[hidden],#sheet[hidden]{display:none}#cart .sum{flex:1;max-width:460px}#cart button,.sheet button.main{padding:12px 18px;border:0;border-radius:12px;background:var(--accent);color:var(--bg);font-weight:600;font-size:16px;cursor:pointer}" +
        "#sheet{position:fixed;inset:0;background:rgba(0,0,0,.45);display:flex;align-items:flex-end;justify-content:center;z-index:9}" +
        ".sheet{background:var(--bg);width:100%;max-width:640px;max-height:88vh;overflow:auto;border-radius:18px 18px 0 0;padding:18px 16px 24px}" +
        ".sheet h3{margin:0 0 8px}.sheet label{display:flex;gap:10px;align-items:center;padding:8px 0;border-bottom:1px solid var(--line)}.sheet .g{margin:14px 0 4px;font-weight:600}" +
        ".sheet textarea,.sheet input[type=text]{width:100%;padding:10px;border:1px solid var(--line);border-radius:10px;background:var(--bg);color:var(--fg);font:inherit}" +
        ".sheet .row{display:flex;gap:10px;align-items:center;justify-content:space-between;margin-top:14px}.sheet .qty button{width:36px;height:36px;border-radius:50%;border:1px solid var(--line);background:none;color:var(--fg);font-size:18px}" +
        ".sheet .line{display:flex;gap:8px;padding:8px 0;border-bottom:1px solid var(--line)}.sheet .line .n{flex:1}.sheet .x{border:0;background:none;color:var(--muted);font-size:18px}" +
        ".sheet .link{border:0;background:none;color:var(--muted);text-decoration:underline;font:inherit;cursor:pointer}.msg{padding:12px;border-radius:12px;background:var(--line);margin-top:12px}"

    /** A poster for the tables, printed on the receipt printer: the name, a QR code and the link. */
    fun poster(name: String, url: String, text: String = "Naskenujte menu", table: String? = null): List<ReceiptBlock> = listOfNotNull(
        ReceiptBlock.Line(name, Align.CENTER, bold = true, large = true),
        table?.let { ReceiptBlock.Line("Stůl $it", Align.CENTER, bold = true, large = true) },
        ReceiptBlock.Line(text, Align.CENTER, bold = true),
        ReceiptBlock.Qr(url),
        ReceiptBlock.Line(url, Align.CENTER),
        ReceiptBlock.Feed(3),
    )
}
