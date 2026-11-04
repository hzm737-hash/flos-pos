package cz.flos.core

/**
 * Variants and extras of a product, written by the owner as plain text, one group per line:
 *
 *     Velikost: Malá, Střední +20, Velká +40
 *     Přílohy*: Sýr +15, Slanina +25, Bez cibule
 *
 * A group without "*" is a variant: exactly one choice (the first is preselected).
 * A group with "*" holds extras: any number of choices, none preselected.
 * Prices are in crowns and may have decimals ("+12,50") or be negative ("-10").
 */
data class OptionChoice(val name: String, val price: Long)

data class OptionGroup(val name: String, val multiple: Boolean, val choices: List<OptionChoice>)

object ProductOptions {
    private val PRICE = Regex("""\s([+\-−]\s?\d+(?:[.,]\d{1,2})?)\s*(?:Kč)?\s*$""", RegexOption.IGNORE_CASE)

    fun parse(text: String?): List<OptionGroup> = text.orEmpty().lines().mapNotNull { line ->
        val colon = line.indexOf(':')
        if (colon <= 0) return@mapNotNull null
        var name = line.substring(0, colon).trim()
        val multiple = name.endsWith("*")
        name = name.removeSuffix("*").trim()
        // A comma between digits is a decimal comma ("+12,50"), not a separator.
        val choices = line.substring(colon + 1).split(Regex("""(?<!\d),|,(?!\d)|;""")).mapNotNull { raw ->
            val c = " " + raw.trim()
            if (c.isBlank()) return@mapNotNull null
            val m = PRICE.find(c)
            val price = m?.groupValues?.get(1)?.replace(" ", "")?.replace('−', '-')?.replace(',', '.')?.toBigDecimalOrNull()
                ?.movePointRight(2)?.toLong() ?: 0L
            val label = (if (m != null) c.substring(0, m.range.first) else c).trim()
            if (label.isEmpty()) null else OptionChoice(label, price)
        }
        if (name.isEmpty() || choices.isEmpty()) null else OptionGroup(name, multiple, choices)
    }

    /** Writes groups back as text, the way [parse] reads it. */
    fun format(groups: List<OptionGroup>): String = groups.joinToString("\n") { g ->
        g.name + (if (g.multiple) "*" else "") + ": " + g.choices.joinToString(", ") { c ->
            c.name + if (c.price != 0L) " " + (if (c.price > 0) "+" else "-") + Money.format(kotlin.math.abs(c.price), "").replace(",00", "") else ""
        }
    }

    /**
     * Name and unit price of the cart line for the chosen options: "Pizza (Velká, Sýr)".
     * [chosen] holds, per group index, the indexes of the selected choices.
     */
    fun apply(baseName: String, basePrice: Long, groups: List<OptionGroup>, chosen: Map<Int, Set<Int>>): Pair<String, Long> {
        val picked = groups.flatMapIndexed { gi, g -> chosen[gi].orEmpty().sorted().mapNotNull { g.choices.getOrNull(it) } }
        val name = if (picked.isEmpty()) baseName else "$baseName (${picked.joinToString(", ") { it.name }})"
        return name to (basePrice + picked.sumOf { it.price }).coerceAtLeast(0)
    }

    /** The first choice of every variant group, nothing for extras. */
    fun defaults(groups: List<OptionGroup>): Map<Int, Set<Int>> =
        groups.withIndex().filter { !it.value.multiple }.associate { it.index to setOf(0) }
}
