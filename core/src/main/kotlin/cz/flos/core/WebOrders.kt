package cz.flos.core

import kotlinx.serialization.Serializable

/**
 * Orders from the restaurant's own website (delivery or pick-up). The website sends the order to the
 * back office; a till fetches it, the staff accept it with a ready time or decline it, and the
 * customer's page shows the answer.
 *
 * The website knows its dishes by menu number and name; the till matches them to its products
 * ([WebOrders.match]) and prices the order itself, so a changed price on the website cannot lower
 * the bill. Prices on the website are whole crowns; the till works in haléře.
 */
@Serializable data class WebExtra(
    val name: String,
    /** Full price of the extra in Kč, as the website lists it. */
    val price: Long,
    /** 0 = on the whole pizza, 1 = only on the first half, 2 = only on the second half. */
    val half: Int = 0,
    /** Always charged in full, even on one half (burrata, a plate of oil). */
    val full: Boolean = false,
)

@Serializable data class WebOrderLine(
    /** Menu number on the website. */
    val number: Int,
    val name: String,
    /** Second half of a half & half pizza: its menu number and name, or null. */
    val half: Int? = null,
    val halfName: String = "",
    val extras: List<WebExtra> = emptyList(),
    val note: String = "",
    val qty: Int,
    /** Price of one piece in Kč as the website showed it; used only when the till has no such product. */
    val unitPrice: Long = 0,
)

@Serializable data class WebCustomer(val name: String, val phone: String, val street: String = "", val bell: String = "")

@Serializable data class WebOrder(
    /** [WebOrders.DELIVERY] or [WebOrders.PICKUP]. */
    val type: String,
    val lines: List<WebOrderLine>,
    val customer: WebCustomer? = null,
    /** Wished time "HH:mm", or "" for as soon as possible. */
    val time: String = "",
    /** "cash" or "card", paid on receipt. */
    val payment: String = "cash",
    val note: String = "",
    val lang: String = "cs",
    /** Delivery fee in Kč the website added (0 for pick-up). */
    val deliveryFee: Long = 0,
    /** Total in Kč the customer saw. */
    val total: Long = 0,
    /** The customer ticked the box agreeing to the terms and the privacy policy. */
    val agreedTerms: Boolean = false,
)

/** A waiting web order as the back office hands it to a till. */
@Serializable data class WebOrderInbox(val id: Long, val order: WebOrder, val createdAt: Long = 0)
@Serializable data class WebOrderList(val orders: List<WebOrderInbox> = emptyList())
@Serializable data class WebOrderAck(val accepted: Boolean, val readyMinutes: Int = 0)
@Serializable data class WebOrderAccepted(val id: Long, val token: String)
/** For the customer's page: NEW, ACCEPTED or REJECTED, and when it will be ready (epoch ms). */
@Serializable data class WebOrderStatus(val status: String, val readyAt: Long? = null)

object WebOrders {
    const val DELIVERY = "DELIVERY"
    const val PICKUP = "PICKUP"
    const val MAX_LINES = 40
    const val MAX_QTY = 20
    const val MAX_EXTRAS = 25
    const val MAX_NOTE = 200
    const val MAX_NAME = 60
    const val MAX_TEXT = 80
    val READY_MINUTES = listOf(15, 20, 30, 45, 60, 90)

    private val TIME = Regex("""^([01]\d|2[0-3]):[0-5]\d$""")

    /** Why the order cannot be taken, or null when it is complete and sane. Texts go to the customer, so in [WebOrder.lang]. */
    fun problem(o: WebOrder): String? {
        val cs = o.lang != "en"
        fun t(c: String, e: String) = if (cs) c else e
        if (o.type != DELIVERY && o.type != PICKUP) return t("Neplatný způsob převzetí.", "Invalid order type.")
        if (o.lines.isEmpty()) return t("Objednávka je prázdná.", "The order is empty.")
        if (o.lines.size > MAX_LINES) return t("Příliš mnoho položek.", "Too many items.")
        if (!o.agreedTerms) return t("Chybí souhlas s podmínkami.", "Please agree to the terms.")
        val c = o.customer ?: return t("Vyplňte jméno a telefon.", "Please fill in your name and phone.")
        if (c.name.isBlank() || c.name.length > MAX_NAME) return t("Vyplňte jméno.", "Please fill in your name.")
        if (Notify.phoneDigits(c.phone) == null) return t("Neplatné telefonní číslo.", "Invalid phone number.")
        if (o.type == DELIVERY && (c.street.trim().length < 4 || c.street.length > MAX_TEXT)) return t("Vyplňte adresu.", "Please fill in the address.")
        if (c.bell.length > MAX_TEXT) return t("Text u zvonku je příliš dlouhý.", "Doorbell text is too long.")
        if (o.time.isNotEmpty() && !TIME.matches(o.time)) return t("Neplatný čas.", "Invalid time.")
        if (o.payment != "cash" && o.payment != "card") return t("Neplatný způsob platby.", "Invalid payment method.")
        if (o.note.length > MAX_NOTE) return t("Poznámka je příliš dlouhá.", "The note is too long.")
        if (o.deliveryFee !in 0..1000 || o.total !in 0..1_000_000) return t("Neplatná cena.", "Invalid price.")
        for (l in o.lines) {
            if (l.qty !in 1..MAX_QTY) return t("Neplatné množství.", "Invalid quantity.")
            if (l.number !in 1..9999 || l.name.isBlank() || l.name.length > MAX_NAME) return t("Neplatná položka.", "Invalid item.")
            if (l.half != null && (l.half !in 1..9999 || l.halfName.isBlank() || l.halfName.length > MAX_NAME)) return t("Neplatná polovina pizzy.", "Invalid pizza half.")
            if (l.note.length > MAX_NOTE || l.unitPrice !in 0..100_000) return t("Neplatná položka.", "Invalid item.")
            if (l.extras.size > MAX_EXTRAS) return t("Příliš mnoho přísad.", "Too many toppings.")
            for (e in l.extras) {
                if (e.name.isBlank() || e.name.length > MAX_NAME || e.price !in 0..10_000 || e.half !in 0..2) return t("Neplatná přísada.", "Invalid topping.")
                if (e.half != 0 && l.half == null) return t("Přísada na polovinu jen u pizzy půl na půl.", "Half toppings need a half & half pizza.")
            }
        }
        return null
    }

    /**
     * Price of one piece in haléře. Half & half: half of each pizza's price. An extra on the whole pizza
     * costs its full price; on one half it costs half, unless it is [WebExtra.full].
     */
    fun unitPrice(first: Long, second: Long?, extras: List<WebExtra>): Long {
        var p = if (second == null) first else (first + second) / 2
        for (e in extras) {
            val full = e.price * 100
            p += if (e.half != 0 && !e.full) full / 2 else full
        }
        return p
    }

    /** "½ Diavola + ½ Margherita + Burrata, + Rukola (½ Margherita)" with the line's note after [LineNote.SEP]. */
    fun lineName(l: WebOrderLine, first: String = l.name, second: String = l.halfName): String {
        val base = if (l.half != null) "½ $first + ½ $second" else first
        val ex = l.extras.joinToString(", ") { e ->
            "+ " + e.name.trim() + when (e.half) { 1 -> " (½ $first)"; 2 -> " (½ $second)"; else -> "" }
        }
        val name = if (ex.isEmpty()) base else "$base $ex"
        return LineNote.join(name, cleanNote(l.note))
    }

    /** A till product the website's dish can be matched to. */
    data class Candidate(val id: Long, val name: String, val barcode: String?)

    /**
     * The till's product for menu [number] / [name]: a product whose code (barcode field) is the
     * number, else one with the same name (ignoring case and accents), else one named "<number> name"
     * or "<number>. name". Null when nothing fits, so the line keeps the website's price.
     */
    fun <T> match(number: Int, name: String, products: List<T>, candidate: (T) -> Candidate): T? {
        val list = products.map { it to candidate(it) }
        list.firstOrNull { it.second.barcode?.trim() == number.toString() }?.let { return it.first }
        val key = key(name)
        list.firstOrNull { key(it.second.name) == key }?.let { return it.first }
        return list.firstOrNull { (_, c) ->
            val m = Regex("""^\s*(\d+)\s*[.)\-]?\s+(.*)$""").find(c.name)
            m != null && m.groupValues[1].toIntOrNull() == number && key(m.groupValues[2]) == key
        }?.first
    }

    private fun key(s: String) = Text.stripDiacritics(s.trim()).lowercase().replace(Regex("""\s+"""), " ")

    /** Removes control characters and the line-note separator, and trims to [MAX_NOTE]. */
    fun cleanNote(s: String): String = s.filter { it >= ' ' && it != '\u007f' }.replace(LineNote.SEP.trim(), "").trim().take(MAX_NOTE)

    private fun clean(s: String, max: Int) = s.filter { it >= ' ' && it != '\u007f' }.trim().take(max)

    /** The order with every free text cleaned and trimmed, as it is stored. */
    fun cleaned(o: WebOrder): WebOrder = o.copy(
        customer = o.customer?.let { WebCustomer(clean(it.name, MAX_NAME), clean(it.phone, 20), clean(it.street, MAX_TEXT), clean(it.bell, MAX_TEXT)) },
        note = cleanNote(o.note),
        lines = o.lines.map { l ->
            l.copy(name = clean(l.name, MAX_NAME), halfName = clean(l.halfName, MAX_NAME), note = cleanNote(l.note),
                extras = l.extras.map { it.copy(name = clean(it.name, MAX_NAME)) })
        },
    )

    /** Heading and details for the kitchen ticket and the dialog on the till. */
    fun summary(id: Long, o: WebOrder): List<String> = buildList {
        add("WEB $id · " + (if (o.type == DELIVERY) "Rozvoz" else "Osobní odběr") + " · " + o.time.ifEmpty { "co nejdříve" })
        o.customer?.let { c ->
            add(c.name + ", " + c.phone)
            if (o.type == DELIVERY) add(c.street + (if (c.bell.isNotBlank()) " (" + c.bell + ")" else ""))
        }
        add("Platba: " + (if (o.payment == "card") "kartou" else "hotově") + " při převzetí")
        if (o.note.isNotBlank()) add("Pozn.: " + o.note)
    }
}
