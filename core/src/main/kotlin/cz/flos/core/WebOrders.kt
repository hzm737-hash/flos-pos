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
    /** [WebOrders.DELIVERY], [WebOrders.PICKUP] or [WebOrders.TABLE] (a guest at a table, from the table's QR code). */
    val type: String,
    val lines: List<WebOrderLine>,
    val customer: WebCustomer? = null,
    /** Wished time "HH:mm", or "" for as soon as possible. */
    val time: String = "",
    /** Day of the order "yyyy-MM-dd" (Prague); "" = today. A later day is a pre-order and then needs [time]. */
    val date: String = "",
    /** "cash", "card" or "qr" (QR platba from the customer's bank app), paid on receipt. */
    val payment: String = "cash",
    val note: String = "",
    val lang: String = "cs",
    /** Delivery fee in Kč the website added (0 for pick-up). */
    val deliveryFee: Long = 0,
    /** Total in Kč the customer saw. */
    val total: Long = 0,
    /** The customer ticked the box agreeing to the terms and the privacy policy. */
    val agreedTerms: Boolean = false,
    /** For [WebOrders.TABLE]: the table and its signed key, both from the table's QR code (t= and k=). */
    val table: String = "",
    val tableKey: String = "",
)

/** A waiting web order as the back office hands it to a till. */
@Serializable data class WebOrderInbox(val id: Long, val order: WebOrder, val createdAt: Long = 0)
@Serializable data class WebOrderList(val orders: List<WebOrderInbox> = emptyList())
/**
 * The till's answer; a pre-order for a later day, or any clock time the staff typed in, is confirmed with
 * [readyAt] (epoch millis) instead of minutes. [message] is the staff's text for the customer: a note with
 * the accepted order, or the reason for a refusal ("" = none).
 */
@Serializable data class WebOrderAck(val accepted: Boolean, val readyMinutes: Int = 0, val readyAt: Long? = null, val message: String = "")
@Serializable data class WebOrderAccepted(val id: Long, val token: String)
/**
 * For the customer's page: NEW, ACCEPTED or REJECTED, when it will be ready (epoch ms) and the restaurant's
 * message to the customer (the reason of a refusal, or a note with the accepted order), or null.
 */
@Serializable data class WebOrderStatus(val status: String, val readyAt: Long? = null, val message: String? = null)

object WebOrders {
    const val DELIVERY = "DELIVERY"
    const val PICKUP = "PICKUP"
    /** Ordered at a table in the restaurant from the website's menu, opened by the table's QR code. */
    const val TABLE = "TABLE"
    const val MAX_LINES = 40
    const val MAX_QTY = 20
    const val MAX_EXTRAS = 25
    const val MAX_NOTE = 200
    const val MAX_NAME = 60
    const val MAX_TEXT = 80
    val READY_MINUTES = listOf(15, 20, 30, 45, 60, 90)

    private val TIME = Regex("""^([01]\d|2[0-3]):[0-5]\d$""")
    private val DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")
    /** How many days ahead a customer may order. */
    const val MAX_DAYS_AHEAD = 14
    val PAYMENTS = setOf("cash", "card", "qr")

    /** The order's day, or null for today / an unreadable date. */
    fun day(o: WebOrder): java.time.LocalDate? =
        if (DATE.matches(o.date)) runCatching { java.time.LocalDate.parse(o.date) }.getOrNull() else null

    /** True for an order for a later day than [today] (a pre-order). */
    fun isPreorder(o: WebOrder, today: java.time.LocalDate = java.time.LocalDate.now(OrderHours.ZONE)): Boolean =
        day(o)?.isAfter(today) == true

    private val DAY_CS = listOf("po", "út", "st", "čt", "pá", "so", "ne")
    private val DAY_EN = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** "po 16.11." / "Mon 16.11." for the order's day. */
    fun dayText(d: java.time.LocalDate, en: Boolean = false): String =
        (if (en) DAY_EN else DAY_CS)[d.dayOfWeek.value - 1] + " " + d.dayOfMonth + "." + d.monthValue + "."

    /** The pay-on-receipt method in Czech or English. */
    fun paymentText(payment: String, en: Boolean = false): String = when (payment) {
        "card" -> if (en) "by card" else "kartou"
        "qr" -> if (en) "by QR payment (bank app)" else "QR platbou"
        else -> if (en) "in cash" else "hotově"
    }

    /** When the customer wants it: "PŘEDOBJEDNÁVKA po 16.11. 18:30", "18:30" or "co nejdříve". */
    fun wishText(o: WebOrder, today: java.time.LocalDate = java.time.LocalDate.now(OrderHours.ZONE)): String {
        val d = day(o)
        return if (d != null && d.isAfter(today)) "PŘEDOBJEDNÁVKA " + dayText(d) + " " + o.time
        else o.time.ifEmpty { "co nejdříve" }
    }

    /** Why the order cannot be taken, or null when it is complete and sane. Texts go to the customer, so in [WebOrder.lang]. */
    fun problem(o: WebOrder): String? {
        val cs = o.lang != "en"
        fun t(c: String, e: String) = if (cs) c else e
        if (o.type != DELIVERY && o.type != PICKUP && o.type != TABLE) return t("Neplatný způsob převzetí.", "Invalid order type.")
        if (o.lines.isEmpty()) return t("Objednávka je prázdná.", "The order is empty.")
        if (o.lines.size > MAX_LINES) return t("Příliš mnoho položek.", "Too many items.")
        if (o.type == TABLE) {
            // At the table: no name, phone or terms; the table's signed key is checked by the back office.
            if (o.table.isBlank() || o.table.length > TableOrders.MAX_TABLE || o.tableKey.isBlank() || o.tableKey.length > 200)
                return t("Neplatný QR kód stolu. Naskenujte prosím kód na stole znovu.", "Invalid table QR code. Please scan the code on the table again.")
            if (o.note.length > MAX_NOTE || o.total !in 0..1_000_000) return t("Neplatná objednávka.", "Invalid order.")
            return lineProblem(o) { c, e -> t(c, e) }
        }
        if (!o.agreedTerms) return t("Chybí souhlas s podmínkami.", "Please agree to the terms.")
        val c = o.customer ?: return t("Vyplňte jméno a telefon.", "Please fill in your name and phone.")
        if (c.name.isBlank() || c.name.length > MAX_NAME) return t("Vyplňte jméno.", "Please fill in your name.")
        if (Notify.phoneDigits(c.phone) == null) return t("Neplatné telefonní číslo.", "Invalid phone number.")
        if (o.type == DELIVERY && (c.street.trim().length < 4 || c.street.length > MAX_TEXT)) return t("Vyplňte adresu.", "Please fill in the address.")
        if (c.bell.length > MAX_TEXT) return t("Text u zvonku je příliš dlouhý.", "Doorbell text is too long.")
        if (o.time.isNotEmpty() && !TIME.matches(o.time)) return t("Neplatný čas.", "Invalid time.")
        if (o.date.isNotEmpty()) {
            val d = day(o) ?: return t("Neplatné datum.", "Invalid date.")
            val today = java.time.LocalDate.now(OrderHours.ZONE)
            if (d.isBefore(today) || d.isAfter(today.plusDays(MAX_DAYS_AHEAD.toLong()))) return t("Neplatné datum.", "Invalid date.")
            if (d.isAfter(today) && o.time.isEmpty()) return t("Vyberte čas.", "Please choose a time.")
        }
        if (o.payment !in PAYMENTS) return t("Neplatný způsob platby.", "Invalid payment method.")
        if (o.note.length > MAX_NOTE) return t("Poznámka je příliš dlouhá.", "The note is too long.")
        if (o.deliveryFee !in 0..1000 || o.total !in 0..1_000_000) return t("Neplatná cena.", "Invalid price.")
        return lineProblem(o) { c, e -> t(c, e) }
    }

    private fun lineProblem(o: WebOrder, t: (String, String) -> String): String? {
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
        table = clean(o.table, TableOrders.MAX_TABLE),
        tableKey = o.tableKey.trim(),
        lines = o.lines.map { l ->
            l.copy(name = clean(l.name, MAX_NAME), halfName = clean(l.halfName, MAX_NAME), note = cleanNote(l.note),
                extras = l.extras.map { it.copy(name = clean(it.name, MAX_NAME)) })
        },
    )

    /**
     * Minutes from [nowMinute] (minutes since midnight) to the customer's wished time "HH:mm", or null when
     * they want it as soon as possible, or the time is past or more than 4 hours away.
     */
    fun wishMinutes(time: String, nowMinute: Int): Int? {
        if (!TIME.matches(time)) return null
        val (h, m) = time.split(':').map { it.toInt() }
        return (h * 60 + m - nowMinute).takeIf { it in 1..240 }
    }

    /** Longest message the staff can send the customer with the answer. */
    const val MAX_REPLY = 300

    /** The staff's message for the customer: one paragraph of plain text, at most [MAX_REPLY] characters. */
    fun cleanReply(text: String): String =
        text.replace(Regex("""[\u0000-\u001F\u007F]+"""), " ").replace(Regex(" {2,}"), " ").trim().take(MAX_REPLY).trim()

    /** Ready-made reasons for refusing an order, in Czech and English (picked by the order's language). */
    val REJECT_REASONS: List<Pair<String, String>> = listOf(
        "Tento čas nám bohužel nevyhovuje. Zvolte prosím jiný čas." to "Sorry, this time does not suit us. Please choose another time.",
        "Teď máme bohužel plno a objednávku nestihneme." to "Sorry, we are fully booked right now and cannot make your order in time.",
        "Na tuto adresu bohužel nerozvážíme." to "Sorry, we do not deliver to this address.",
        "Některé položky už dnes nemáme. Zavolejte nám prosím." to "Some items are sold out today. Please give us a call.",
    )

    /** Reads a clock time the staff typed: "19:30", "19.30", "1930", "9:05" or "19" (= 19:00); null when it is not a time. */
    fun parseClock(text: String): java.time.LocalTime? {
        val t = text.trim().replace('.', ':').replace(',', ':').replace(' ', ':')
        val m = Regex("""^(\d{1,2})(?::?(\d{2}))?$""").matchEntire(t) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        return if (h in 0..23 && min in 0..59) java.time.LocalTime.of(h, min) else null
    }

    /**
     * The moment for a clock time the staff typed, on [day] (Prague time), or null when it cannot be read,
     * is not after [now], or is further away than [MAX_DAYS_AHEAD] days.
     */
    fun readyAtFor(text: String, day: java.time.LocalDate, now: Long = System.currentTimeMillis()): Long? {
        val t = parseClock(text) ?: return null
        val at = day.atTime(t).atZone(OrderHours.ZONE).toInstant().toEpochMilli()
        return at.takeIf { it > now && it <= now + (MAX_DAYS_AHEAD + 1) * 24 * 3_600_000L }
    }

    /**
     * The confirmation the restaurant sends to the customer (WhatsApp), in the customer's language:
     * order number, delivery or pick-up, the confirmed time and the payment, and the staff's [reply] when set.
     */
    fun confirmation(id: Long, o: WebOrder, readyAt: String, shop: String, shopPhone: String, reply: String = ""): String {
        val name = o.customer?.name?.trim().orEmpty()
        val delivery = o.type == DELIVERY
        val en = o.lang == "en"
        return if (en) buildString {
            append(if (name.isEmpty()) "Hello" else "Hello $name").append(", we confirm your order no. WEB ").append(id).append(". ")
            append(if (delivery) "We will deliver it at about $readyAt" else "It will be ready for pick-up at $readyAt").append(". ")
            append("Payment ").append(paymentText(o.payment, true)).append(" on receipt. ")
            if (reply.isNotBlank()) append(reply.trim()).append(' ')
            append("Thank you! ").append(shop).append(if (shopPhone.isNotBlank()) ", tel. $shopPhone" else "")
        }.trim() else buildString {
            append(if (name.isEmpty()) "Dobrý den" else "Dobrý den $name").append(", potvrzujeme Vaši objednávku č. WEB ").append(id).append(". ")
            append(if (delivery) "Doručíme ji přibližně v $readyAt" else "Bude připravena k vyzvednutí v $readyAt").append(". ")
            append("Platba ").append(paymentText(o.payment)).append(" při převzetí. ")
            if (reply.isNotBlank()) append(reply.trim()).append(' ')
            append("Děkujeme! ").append(shop).append(if (shopPhone.isNotBlank()) ", tel. $shopPhone" else "")
        }.trim()
    }

    /** The message the restaurant sends when it cannot take the order (WhatsApp), with the [reason], in the customer's language. */
    fun rejection(id: Long, o: WebOrder, reason: String, shop: String, shopPhone: String): String {
        val name = o.customer?.name?.trim().orEmpty()
        return if (o.lang == "en") buildString {
            append(if (name.isEmpty()) "Hello" else "Hello $name").append(", unfortunately we cannot accept your order no. WEB ").append(id).append(". ")
            if (reason.isNotBlank()) append(reason.trim()).append(' ')
            append(shop).append(if (shopPhone.isNotBlank()) ", tel. $shopPhone" else "")
        }.trim() else buildString {
            append(if (name.isEmpty()) "Dobrý den" else "Dobrý den $name").append(", Vaši objednávku č. WEB ").append(id).append(" bohužel nemůžeme přijmout. ")
            if (reason.isNotBlank()) append(reason.trim()).append(' ')
            append(shop).append(if (shopPhone.isNotBlank()) ", tel. $shopPhone" else "")
        }.trim()
    }

    /** Starts of the ticket lines with the customer's personal details ([ticketNote]); removed again by [withoutCustomer]. */
    private val CUSTOMER_LINES = listOf("Zákazník: ", "Tel.: ", "Adresa: ")

    /**
     * The note saved with an accepted delivery / pick-up order and printed on its ticket, one item per line:
     * [heading], the customer's name, phone and (for delivery) address with the doorbell, the wished time,
     * the payment and the customer's note. It stays on the open order so every later print (the order
     * again, the pre-bill, the receipt) shows it; when the order is paid, [withoutCustomer] removes the
     * personal lines from what is stored.
     */
    fun ticketNote(heading: String, o: WebOrder, today: java.time.LocalDate = java.time.LocalDate.now(OrderHours.ZONE)): String = buildList {
        add(heading)
        o.customer?.let { c ->
            if (c.name.isNotBlank()) add("Zákazník: " + c.name.trim())
            if (c.phone.isNotBlank()) add("Tel.: " + c.phone.trim())
            if (o.type == DELIVERY && c.street.isNotBlank())
                add("Adresa: " + c.street.trim() + (if (c.bell.isNotBlank()) " (zvonek: " + c.bell.trim() + ")" else ""))
        }
        add("Přeje si: " + wishText(o, today))
        add("Platba: " + paymentText(o.payment) + " při převzetí")
        if (o.note.isNotBlank()) add("Pozn.: " + o.note.trim())
    }.joinToString("\n")

    private val WEB_ID = Regex("""^WEB (\d{1,10}) · (Rozvoz|Osobní odběr|Delivery|Pick-up)""")

    /**
     * The variable symbol for paying a delivery / pick-up web order: its WEB number, read from the order's
     * note ([ticketNote] heading). Null for any other order, including a web order from a table.
     */
    fun variableSymbol(note: String): String? = WEB_ID.find(note)?.groupValues?.get(1)

    /** [note] without the lines with the customer's name, phone and address (see [ticketNote]); other notes unchanged. */
    fun withoutCustomer(note: String): String {
        if (!note.startsWith("WEB ") || '\n' !in note) return note
        return note.split('\n').filterNot { l -> CUSTOMER_LINES.any { l.startsWith(it) } }.joinToString("\n")
    }

    /** Heading and details for the kitchen ticket and the dialog on the till. */
    fun summary(id: Long, o: WebOrder): List<String> = if (o.type == TABLE) buildList {
        add("WEB $id · Stůl " + o.table)
        if (o.note.isNotBlank()) add("Pozn.: " + o.note)
    } else buildList {
        add("WEB $id · " + (if (o.type == DELIVERY) "Rozvoz" else "Osobní odběr") + " · " + wishText(o))
        o.customer?.let { c ->
            add(c.name + ", " + c.phone)
            if (o.type == DELIVERY) add(c.street + (if (c.bell.isNotBlank()) " (" + c.bell + ")" else ""))
        }
        add("Platba: " + paymentText(o.payment) + " při převzetí")
        if (o.note.isNotBlank()) add("Pozn.: " + o.note)
    }
}
