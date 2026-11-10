package cz.flos.core

/**
 * Add-ons the owner sets up once in Settings and that are offered for whole categories
 * (or for every product), on top of a product's own variants and extras ([ProductOptions]).
 *
 * Stored as text, one group per line, in the [ProductOptions] syntax with an optional list of
 * category names in square brackets in front:
 *
 *     [Pizza, Burgery] Přísady*: Sýr +15, Slanina +25, Jalapeño +10
 *     Omáčka: Bez omáčky, Kečup, Majonéza +5
 *
 * A line without brackets applies to every product.
 */
data class AddonGroup(val group: OptionGroup, val categories: List<String> = emptyList()) {
    val forAll: Boolean get() = categories.isEmpty()
}

object Addons {
    private val PREFIX = Regex("""^\s*\[([^\]]*)]\s*""")

    fun parse(text: String?): List<AddonGroup> = text.orEmpty().lines().mapNotNull { line ->
        val m = PREFIX.find(line)
        val cats = m?.groupValues?.get(1)?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val rest = if (m != null) line.substring(m.range.last + 1) else line
        ProductOptions.parse(rest).firstOrNull()?.let { AddonGroup(it, cats) }
    }

    fun format(list: List<AddonGroup>): String = list.joinToString("\n") { a ->
        (if (a.categories.isEmpty()) "" else "[" + a.categories.joinToString(", ") + "] ") + ProductOptions.format(listOf(a.group))
    }

    /** Groups offered for a product in [category] (its name; null when it has none). */
    fun forCategory(list: List<AddonGroup>, category: String?): List<OptionGroup> {
        val key = category?.let(::key)
        return list.filter { a -> a.forAll || (key != null && a.categories.any { key(it) == key }) }.map { it.group }
    }

    private fun key(s: String) = Text.stripDiacritics(s.trim()).lowercase()
}

/**
 * A note for the kitchen on one cart line ("pálivé, bez cibule"). It travels as part of the line's
 * name, after [SEP], so it shows on the kitchen screen, the order ticket and the receipt
 * without any change to how lines are stored.
 */
object LineNote {
    const val SEP = " · "

    /** Product name (with variants) and note of a line name. */
    fun split(name: String): Pair<String, String> {
        val i = name.lastIndexOf(SEP)
        return if (i < 0) name to "" else name.substring(0, i) to name.substring(i + SEP.length)
    }

    fun join(base: String, note: String): String {
        val n = note.trim().trim(',').trim()
        return if (n.isEmpty()) base else base + SEP + n
    }

    /** Ready-made notes from Settings, separated by commas or new lines. */
    fun quickNotes(text: String?): List<String> =
        text.orEmpty().split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** Adds or removes a ready-made note in a free note text. */
    fun toggle(note: String, quick: String): String {
        val parts = note.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val next = if (parts.any { it.equals(quick, ignoreCase = true) }) parts.filterNot { it.equals(quick, ignoreCase = true) } else parts + quick
        return next.joinToString(", ")
    }
}
