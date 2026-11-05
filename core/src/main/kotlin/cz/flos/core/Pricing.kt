package cz.flos.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Prices beyond the product's own price, all written by the owner as plain text like the
 * product variants:
 *
 * Service types (one per line), with an optional surcharge or discount and price list:
 *
 *     Na místě
 *     S sebou: -5 %
 *     Rozvoz: +10 %, ceník Rozvoz
 *
 * Price lists on a product (one per line), the price in crowns:
 *
 *     Rozvoz: 89
 *     Zaměstnanci: 45,50
 *
 * Automatic discounts (happy hour, promotions), one rule per line; every condition is optional:
 *
 *     Happy hour: 20 %, 15:00-17:00, Po-Pá, kategorie Nápoje
 *     Akce pivo: 10 %, produkt Pivo 0,5 l
 *     Prosinec: 5 %, 1.12.2026-31.12.2026, služba S sebou
 *
 * When several rules fit an item, the biggest discount is used.
 */
data class ServiceType(val name: String, val percent: Double = 0.0, val priceList: String? = null)

data class DiscountRule(
    val name: String,
    val percent: Int,
    val from: LocalTime? = null,
    val to: LocalTime? = null,
    val days: Set<DayOfWeek> = emptySet(),
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val categories: Set<String> = emptySet(),
    val products: Set<String> = emptySet(),
    val services: Set<String> = emptySet(),
) {
    fun matches(product: String, category: String?, service: String?, at: LocalDateTime): Boolean {
        val d = at.toLocalDate()
        if (startDate != null && d.isBefore(startDate)) return false
        if (endDate != null && d.isAfter(endDate)) return false
        if (days.isNotEmpty() && at.dayOfWeek !in days) return false
        if (from != null && to != null) {
            val t = at.toLocalTime()
            val inside = if (from <= to) !t.isBefore(from) && t.isBefore(to) else !t.isBefore(from) || t.isBefore(to)
            if (!inside) return false
        }
        if (categories.isNotEmpty() && (category == null || Pricing.key(category) !in categories)) return false
        if (products.isNotEmpty() && Pricing.key(product) !in products) return false
        if (services.isNotEmpty() && (service == null || Pricing.key(service) !in services)) return false
        return true
    }
}

object Pricing {
    /** Compares names without case and diacritics. */
    fun key(s: String): String = Text.stripDiacritics(s).lowercase().trim().replace(Regex("\\s+"), " ")

    private val PERCENT = Regex("""^([+\-−]?\s?\d+(?:[.,]\d+)?)\s*%$""")
    private val TIME_RANGE = Regex("""^(\d{1,2})[:.](\d{2})\s*[-–]\s*(\d{1,2})[:.](\d{2})$""")
    private val DATE_RANGE = Regex("""^(\d{1,2}\.\s?\d{1,2}\.\s?\d{4})\s*[-–]\s*(\d{1,2}\.\s?\d{1,2}\.\s?\d{4})$""")
    private val DATE = DateTimeFormatter.ofPattern("d.M.yyyy")
    private val DAYS = listOf("po" to DayOfWeek.MONDAY, "ut" to DayOfWeek.TUESDAY, "st" to DayOfWeek.WEDNESDAY, "ct" to DayOfWeek.THURSDAY,
        "pa" to DayOfWeek.FRIDAY, "so" to DayOfWeek.SATURDAY, "ne" to DayOfWeek.SUNDAY)

    private fun percentOf(token: String): Double? =
        PERCENT.find(token.trim())?.groupValues?.get(1)?.replace(" ", "")?.replace('−', '-')?.replace(',', '.')?.toDoubleOrNull()

    /** Splits on commas that are not decimal commas ("45,50"). */
    private fun tokens(text: String) = text.split(Regex("""(?<!\d),|,(?!\d)|;""")).map { it.trim() }.filter { it.isNotEmpty() }

    fun parseServiceTypes(text: String?): List<ServiceType> = text.orEmpty().lines().mapNotNull { line ->
        val l = line.trim()
        if (l.isEmpty()) return@mapNotNull null
        val colon = l.indexOf(':')
        val name = (if (colon >= 0) l.substring(0, colon) else l).trim()
        if (name.isEmpty()) return@mapNotNull null
        var percent = 0.0
        var list: String? = null
        if (colon >= 0) tokens(l.substring(colon + 1)).forEach { t ->
            percentOf(t)?.let { percent = it }
            if (key(t).startsWith("cenik ")) list = t.substring(t.indexOf(' ') + 1).trim().ifEmpty { null }
        }
        ServiceType(name, percent, list)
    }.distinctBy { key(it.name) }

    /** A product's prices in other price lists, in haléře, keyed by [key] of the list name. */
    fun parsePrices(text: String?): Map<String, Long> = text.orEmpty().lines().mapNotNull { line ->
        val colon = line.lastIndexOf(':').takeIf { it > 0 } ?: line.lastIndexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
        val name = line.substring(0, colon).trim()
        val price = Money.parse(line.substring(colon + 1).replace("Kč", "").trim())
        if (name.isEmpty() || price == null || price < 0) null else key(name) to price
    }.toMap()

    /** Names of all price lists used by the products and service types, as written. */
    fun priceListNames(productPrices: List<String?>, services: List<ServiceType>): List<String> {
        val names = productPrices.flatMap { t -> t.orEmpty().lines().mapNotNull { l -> l.substringBefore(':').substringBefore('=').trim().takeIf { it.isNotEmpty() && l.contains(Regex("[:=]")) } } } +
            services.mapNotNull { it.priceList }
        return names.distinctBy { key(it) }
    }

    fun parseRules(text: String?): List<DiscountRule> = text.orEmpty().lines().mapNotNull { line ->
        val colon = line.indexOf(':')
        if (colon <= 0) return@mapNotNull null
        var rule = DiscountRule(name = line.substring(0, colon).trim(), percent = 0)
        tokens(line.substring(colon + 1)).forEach { t ->
            val k = key(t)
            percentOf(t)?.let { rule = rule.copy(percent = kotlin.math.abs(it).toInt().coerceIn(0, 100)); return@forEach }
            TIME_RANGE.find(t)?.let { m ->
                val (h1, m1, h2, m2) = m.destructured
                rule = rule.copy(from = LocalTime.of(h1.toInt() % 24, m1.toInt()), to = if (h2.toInt() >= 24) LocalTime.MAX else LocalTime.of(h2.toInt(), m2.toInt()))
                return@forEach
            }
            DATE_RANGE.find(t)?.let { m ->
                val a = runCatching { LocalDate.parse(m.groupValues[1].replace(" ", ""), DATE) }.getOrNull()
                val b = runCatching { LocalDate.parse(m.groupValues[2].replace(" ", ""), DATE) }.getOrNull()
                rule = rule.copy(startDate = a, endDate = b)
                return@forEach
            }
            days(k)?.let { rule = rule.copy(days = rule.days + it); return@forEach }
            when {
                k.startsWith("kategorie ") -> rule = rule.copy(categories = rule.categories + key(k.removePrefix("kategorie ")))
                k.startsWith("produkt ") -> rule = rule.copy(products = rule.products + key(k.removePrefix("produkt ")))
                k.startsWith("sluzba ") -> rule = rule.copy(services = rule.services + key(k.removePrefix("sluzba ")))
            }
        }
        rule.takeIf { it.percent > 0 && it.name.isNotEmpty() }
    }

    /** "Po-Pá", "So", "Ne" → the days, or null when the text is not a day. */
    private fun days(k: String): Set<DayOfWeek>? {
        fun day(s: String) = DAYS.firstOrNull { it.first == s.trim().take(2) && s.trim().length <= 3 }?.second
        val parts = k.split('-', '–')
        return when (parts.size) {
            1 -> day(parts[0])?.let { setOf(it) }
            2 -> {
                val a = day(parts[0]) ?: return null
                val b = day(parts[1]) ?: return null
                generateSequence(a) { it.plus(1) }.takeWhile { it != b }.toSet() + b
            }
            else -> null
        }
    }

    /**
     * Unit price of a product line: its price in [priceList] when it has one there, otherwise
     * [base]; then the service type's surcharge or discount, rounded to whole haléře.
     */
    fun unitPrice(base: Long, productPrices: String?, priceList: String?, service: ServiceType?): Long {
        val listed = priceList?.let { parsePrices(productPrices)[key(it)] } ?: base
        val pct = service?.percent ?: 0.0
        return if (pct == 0.0) listed else Math.round(listed * (1 + pct / 100.0)).coerceAtLeast(0)
    }

    /** The automatic discount for an item and the rule it comes from, or null. */
    fun discount(rules: List<DiscountRule>, product: String, category: String?, service: String?, at: LocalDateTime): DiscountRule? =
        rules.filter { it.matches(product, category, service, at) }.maxByOrNull { it.percent }
}
