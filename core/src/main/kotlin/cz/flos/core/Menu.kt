package cz.flos.core

import kotlinx.serialization.Serializable

@Serializable data class MenuItem(val name: String, val price: Long, val note: String = "")
@Serializable data class MenuCategory(val name: String, val items: List<MenuItem>)
@Serializable data class MenuData(
    val name: String,
    val address: String = "",
    val contacts: List<String> = emptyList(),
    val hours: String = "",
    val categories: List<MenuCategory> = emptyList(),
    /** Footer, e.g. "Ceny včetně DPH" or allergens info. */
    val note: String = "",
)

/** The digital menu as one mobile-friendly HTML page (no scripts), for the QR code on the tables. */
object MenuPage {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    fun html(m: MenuData): String = buildString {
        append("<!doctype html><html lang=\"cs\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        append("<title>").append(esc(m.name)).append(" – menu</title><style>")
        append(":root{--bg:#fbf8f3;--fg:#1f1d1a;--muted:#6b665e;--line:#e6dfd3;--accent:#123c69}")
        append("@media(prefers-color-scheme:dark){:root{--bg:#141311;--fg:#f1ece4;--muted:#a59e93;--line:#2c2924;--accent:#7fb2e5}}")
        append("*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}")
        append("main{max-width:640px;margin:0 auto;padding:24px 16px 48px}h1{margin:0 0 4px;font-size:28px;color:var(--accent)}")
        append(".meta{color:var(--muted);font-size:14px;white-space:pre-line}nav{display:flex;gap:8px;overflow-x:auto;padding:16px 0;position:sticky;top:0;background:var(--bg)}")
        append("nav a{flex:none;padding:6px 12px;border:1px solid var(--line);border-radius:999px;color:var(--fg);text-decoration:none;font-size:14px}")
        append("h2{margin:28px 0 8px;font-size:20px}.item{display:flex;gap:12px;padding:10px 0;border-bottom:1px solid var(--line)}")
        append(".item .n{flex:1}.item .p{font-weight:600;white-space:nowrap}.note{display:block;color:var(--muted);font-size:14px}footer{margin-top:32px;color:var(--muted);font-size:13px}")
        append("</style></head><body><main><h1>").append(esc(m.name)).append("</h1>")
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
                append("<div class=\"item\"><span class=\"n\">").append(esc(it.name))
                if (it.note.isNotBlank()) append("<span class=\"note\">").append(esc(it.note)).append("</span>")
                append("</span><span class=\"p\">").append(esc(Money.format(it.price))).append("</span></div>")
            }
        }
        append("<footer>").append(esc(m.note.ifBlank { "Ceny jsou uvedeny včetně DPH." })).append("</footer></main></body></html>")
    }

    /** A poster for the tables, printed on the receipt printer: the name, a QR code and the link. */
    fun poster(name: String, url: String, text: String = "Naskenujte menu"): List<ReceiptBlock> = listOf(
        ReceiptBlock.Line(name, Align.CENTER, bold = true, large = true),
        ReceiptBlock.Line(text, Align.CENTER, bold = true),
        ReceiptBlock.Qr(url),
        ReceiptBlock.Line(url, Align.CENTER),
        ReceiptBlock.Feed(3),
    )
}
