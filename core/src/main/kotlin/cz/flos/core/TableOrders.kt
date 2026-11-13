package cz.flos.core

import kotlinx.serialization.Serializable
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Ordering from the table: the guest scans the table's QR code, picks dishes on the menu page and
 * sends the order; the back office keeps it until the till fetches it and puts it on that table.
 *
 * The QR code carries the table name and a key signed by the back office, so nobody can send an
 * order for a table they are not sitting at (or from home). The back office and the till both check
 * every line against the published menu; the till always prices the order itself.
 */
@Serializable data class GuestOrderLine(
    /** Product id on the till ([MenuItem.id]). */
    val id: Long,
    val qty: Int,
    /** Per option group of the product, the indexes of the chosen choices. */
    val choices: List<List<Int>> = emptyList(),
    val note: String = "",
)

@Serializable data class GuestOrder(
    val table: String,
    val key: String,
    val lines: List<GuestOrderLine>,
    /** Note for the whole order ("we are in a hurry"). */
    val note: String = "",
)

/** An order waiting for the till, as the back office hands it over. */
@Serializable data class TableOrder(
    val id: Long,
    val table: String,
    val lines: List<GuestOrderLine>,
    val note: String = "",
    val createdAt: Long = 0,
)

@Serializable data class TableOrderList(val orders: List<TableOrder> = emptyList())
@Serializable data class TableOrderAck(val accepted: Boolean)
@Serializable data class TableLinkRequest(val tables: List<String>)
@Serializable data class TableLink(val table: String, val path: String)
@Serializable data class TableLinkList(val links: List<TableLink> = emptyList())
@Serializable data class GuestOrderAccepted(val id: Long, val status: String = "NEW")

object TableOrders {
    const val MAX_LINES = 40
    const val MAX_QTY = 30
    const val MAX_NOTE = 140
    const val MAX_TABLE = 40

    /** The key printed in a table's QR code: HMAC-SHA256 of the table name, 22 URL-safe characters. */
    fun key(secret: ByteArray, table: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val sig = mac.doFinal(("table:" + table.trim()).toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sig.copyOf(16))
    }

    /**
     * The table's QR code address on the restaurant's own website [site] (e.g. https://pizzafresca.cz/menu):
     * the website's page with the table and its key taken from the Flos link [flosLink] (…/menu/slug?t=…&k=…).
     * A blank or non-web [site] keeps the Flos link.
     */
    fun websiteLink(site: String, flosLink: String): String {
        val base = site.trim()
        if (!(base.startsWith("https://") || base.startsWith("http://")) || base.any { it <= ' ' }) return flosLink
        val query = flosLink.substringAfter('?', "").split('&').filter { it.startsWith("t=") || it.startsWith("k=") }
        if (query.size < 2) return flosLink
        val clean = base.substringBefore('#')
        return clean + (if ('?' in clean) "&" else "?") + query.joinToString("&")
    }

    /** Compares in constant time, so the key cannot be guessed character by character. */
    fun keyMatches(secret: ByteArray, table: String, key: String): Boolean =
        java.security.MessageDigest.isEqual(key(secret, table).toByteArray(), key.toByteArray())

    /** Why the order cannot be accepted for [menu], or null when every line is a real dish with valid choices. */
    fun problem(menu: MenuData, order: GuestOrder): String? {
        if (!menu.ordering) return "Objednávky z menu jsou vypnuté."
        if (order.table.isBlank() || order.table.length > MAX_TABLE) return "Neplatný stůl."
        if (order.lines.isEmpty()) return "Objednávka je prázdná."
        if (order.lines.size > MAX_LINES) return "Příliš mnoho položek."
        if (order.note.length > MAX_NOTE) return "Poznámka je příliš dlouhá."
        val items = menu.categories.flatMap { it.items }.filter { it.id > 0 }.associateBy { it.id }
        for (l in order.lines) {
            val item = items[l.id] ?: return "Položka už není v menu."
            if (l.qty !in 1..MAX_QTY) return "Neplatné množství."
            if (l.note.length > MAX_NOTE) return "Poznámka je příliš dlouhá."
            if (!choicesValid(ProductOptions.parse(item.options), l.choices)) return "Neplatná volba u položky ${item.name}."
        }
        return null
    }

    /** A variant group needs exactly one valid choice, an extras group any number of different valid ones. */
    fun choicesValid(groups: List<OptionGroup>, choices: List<List<Int>>): Boolean {
        if (choices.size > groups.size) return false
        return groups.withIndex().all { (gi, g) ->
            val c = choices.getOrNull(gi).orEmpty()
            c.all { it in g.choices.indices } && c.toSet().size == c.size && (if (g.multiple) true else c.size == 1)
        }
    }

    /** [GuestOrderLine.choices] as [ProductOptions.apply] wants them. */
    fun chosen(choices: List<List<Int>>): Map<Int, Set<Int>> =
        choices.withIndex().filter { it.value.isNotEmpty() }.associate { it.index to it.value.toSet() }

    /** Removes characters that could break a line name or a ticket, and trims to [MAX_NOTE]. */
    fun cleanNote(s: String): String = s.filter { it >= ' ' && it != '\u007f' }.replace(LineNote.SEP.trim(), "").trim().take(MAX_NOTE)
}
