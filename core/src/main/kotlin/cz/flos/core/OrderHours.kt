package cz.flos.core

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When the back office takes orders from the tables (QR code) and from the website, set on the till
 * (Pokročilé nastavení) and kept by the back office, which turns away orders outside these times.
 *
 * Hours are one text per weekday, Monday first: "11:00-20:30", several times "11:00-14:00, 17:00-22:00",
 * past midnight "18:00-02:00"; an empty text means no orders that day.
 */
@Serializable data class OrderControl(
    /** Orders from the tables only at [qrHours]; off = any time (as before). */
    val qrHoursOn: Boolean = false,
    val qrHours: List<String> = OrderHours.DEFAULT_WEEK,
    /** Delivery and pick-up orders from the website only at [webHours]; off = any time (as before). */
    val webHoursOn: Boolean = false,
    val webHours: List<String> = OrderHours.DEFAULT_WEEK,
    /** "Stop orders" on the till: no orders from the tables until it is switched back. */
    val qrPaused: Boolean = false,
    /** "Stop orders" on the till: no orders from the website until it is switched back. */
    val webPaused: Boolean = false,
    /** What guests see while orders are stopped; empty = [OrderHours.DEFAULT_PAUSE]. */
    val pauseMessage: String = "",
    /** A table whose bill is still open may keep ordering after hours (the guests are still sitting there). */
    val openTablesAfterHours: Boolean = true,
)

/** The till's tables with an open bill, sent to the back office for [OrderControl.openTablesAfterHours]. */
@Serializable data class OpenTables(val tables: List<String> = emptyList())

/** Whether one kind of order is taken now, for the website and the guests' menu page. */
@Serializable data class OrderChannelStatus(
    val open: Boolean,
    /** "" (open), "paused" (stopped on the till) or "hours" (outside the ordering hours). */
    val reason: String = "",
    /** Text for the customer in Czech and in English; empty while open. */
    val message: String = "",
    val messageEn: String = "",
    /** The ordering hours, Monday first, as set on the till; empty = no limit. */
    val hours: List<String> = emptyList(),
    /** When orders open again (epoch millis), if known: the next start of the hours. Null while stopped on the till. */
    val opensAt: Long? = null,
)

/** GET /menu/<slug>/order-status: "qr" are orders from the tables, "web" delivery and pick-up. */
@Serializable data class OrderStatus(val qr: OrderChannelStatus, val web: OrderChannelStatus)

object OrderHours {
    val ZONE: ZoneId = ZoneId.of("Europe/Prague")
    val DEFAULT_WEEK: List<String> = List(7) { "11:00-22:00" }
    const val DEFAULT_PAUSE = "Momentálně máme hodně práce a nové objednávky nepřijímáme. Zkuste to prosím za chvíli."
    const val DEFAULT_PAUSE_EN = "We are very busy right now and cannot take new orders. Please try again a little later."
    const val MAX_MESSAGE = 300
    private const val DAY = 24 * 60

    private val DAYS_CS = listOf("v pondělí", "v úterý", "ve středu", "ve čtvrtek", "v pátek", "v sobotu", "v neděli")
    private val DAYS_EN = listOf("on Monday", "on Tuesday", "on Wednesday", "on Thursday", "on Friday", "on Saturday", "on Sunday")
    private val TIME = Regex("""^(\d{1,2})(?:[:.](\d{2}))?$""")

    private fun minutes(t: String): Int? {
        val m = TIME.matchEntire(t.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (min > 59 || h > 24 || (h == 24 && min > 0)) return null
        return h * 60 + min
    }

    /**
     * One day's times as minute ranges from that day's midnight, [from, to); a range past midnight ends
     * after 1440. Empty text = closed (empty list); null when the text cannot be read.
     */
    fun parseDay(text: String): List<IntRange>? {
        val parts = text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        return parts.map { p ->
            val ends = p.split('-', '–', '—').map { it.trim() }
            if (ends.size != 2) return null
            val from = minutes(ends[0]) ?: return null
            var to = minutes(ends[1]) ?: return null
            if (from == DAY) return null
            if (to <= from) to += DAY // e.g. 18:00-02:00
            from until to
        }
    }

    /** The first weekday (1 = Monday) whose text cannot be read, or null when all seven are fine. */
    fun problem(week: List<String>): String? {
        if (week.size != 7) return "Je potřeba 7 dní."
        week.forEachIndexed { i, d -> if (parseDay(d) == null) return "Nesprávný čas (${listOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne")[i]}): „$d“. Pište např. 11:00-20:30." }
        return null
    }

    private fun day(week: List<String>, dow: DayOfWeek): List<IntRange> = parseDay(week.getOrElse(dow.value - 1) { "" }).orEmpty()

    /** True when [at] falls into the hours (also the part of yesterday's range that runs past midnight). */
    fun isOpen(week: List<String>, at: ZonedDateTime): Boolean {
        val t = at.withZoneSameInstant(ZONE)
        val m = t.hour * 60 + t.minute
        if (day(week, t.dayOfWeek).any { m in it }) return true
        return day(week, t.dayOfWeek.minus(1)).any { m + DAY in it }
    }

    /** The next moment the hours start after [at], within a week; null when every day is empty. */
    fun nextOpening(week: List<String>, at: ZonedDateTime): ZonedDateTime? {
        val t = at.withZoneSameInstant(ZONE)
        val now = t.hour * 60 + t.minute
        for (d in 0..7) {
            val date = t.toLocalDate().plusDays(d.toLong())
            val starts = day(week, date.dayOfWeek).map { it.first }.filter { d > 0 || it > now }.sorted()
            starts.firstOrNull()?.let { return date.atStartOfDay(ZONE).plusMinutes(it.toLong()) }
        }
        return null
    }

    private fun clock(t: ZonedDateTime) = "%d:%02d".format(t.hour, t.minute)

    /** "dnes od 17:00", "zítra od 11:00", "v pondělí od 11:00" (and English) for the next opening. */
    fun whenText(next: ZonedDateTime, now: ZonedDateTime, en: Boolean): String {
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.withZoneSameInstant(ZONE).toLocalDate(), next.toLocalDate())
        val dayText = when (days) {
            0L -> if (en) "today" else "dnes"
            1L -> if (en) "tomorrow" else "zítra"
            else -> (if (en) DAYS_EN else DAYS_CS)[next.dayOfWeek.value - 1]
        }
        return if (en) "$dayText from ${clock(next)}" else "$dayText od ${clock(next)}"
    }

    /**
     * Whether orders of one kind are taken at [nowMillis]. [paused] and [hoursOn]/[week] come from
     * [OrderControl]; [afterHoursAllowed] lets a table with an open bill order after hours.
     */
    fun status(paused: Boolean, hoursOn: Boolean, week: List<String>, pauseMessage: String,
               nowMillis: Long = System.currentTimeMillis(), afterHoursAllowed: Boolean = false): OrderChannelStatus {
        val hours = if (hoursOn) week else emptyList()
        if (paused) {
            val custom = pauseMessage.trim().take(MAX_MESSAGE)
            return OrderChannelStatus(false, "paused", custom.ifEmpty { DEFAULT_PAUSE }, custom.ifEmpty { DEFAULT_PAUSE_EN }, hours)
        }
        if (!hoursOn || afterHoursAllowed) return OrderChannelStatus(true, hours = hours)
        val now = Instant.ofEpochMilli(nowMillis).atZone(ZONE)
        if (isOpen(week, now)) return OrderChannelStatus(true, hours = hours)
        val next = nextOpening(week, now)
        val cs = "Teď objednávky nepřijímáme." + (next?.let { " Objednat můžete " + whenText(it, now, false) + "." } ?: "")
        val en = "We are not taking orders right now." + (next?.let { " You can order " + whenText(it, now, true) + "." } ?: "")
        return OrderChannelStatus(false, "hours", cs, en, hours, next?.toInstant()?.toEpochMilli())
    }

    /** Orders from the tables; [table] with an open bill on a till may order after hours when allowed. */
    fun qrStatus(c: OrderControl, nowMillis: Long = System.currentTimeMillis(), tableHasOpenBill: Boolean = false) =
        status(c.qrPaused, c.qrHoursOn, c.qrHours, c.pauseMessage, nowMillis, c.openTablesAfterHours && tableHasOpenBill)

    /** Delivery and pick-up orders from the website. */
    fun webStatus(c: OrderControl, nowMillis: Long = System.currentTimeMillis()) =
        status(c.webPaused, c.webHoursOn, c.webHours, c.pauseMessage, nowMillis)

    /** The control as the back office keeps it: valid hours (unreadable ones refused), a short message. */
    fun cleaned(c: OrderControl): OrderControl = c.copy(
        qrHours = c.qrHours.map { it.trim().take(60) },
        webHours = c.webHours.map { it.trim().take(60) },
        pauseMessage = c.pauseMessage.trim().take(MAX_MESSAGE),
    )
}
