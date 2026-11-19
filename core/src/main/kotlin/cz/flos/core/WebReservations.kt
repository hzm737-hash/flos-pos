package cz.flos.core

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Table reservations from the restaurant's own website. The website sends the reservation to the back
 * office; a till fetches it, the staff confirm it (optionally with a table) or decline it with a reason,
 * and the customer's page shows the answer. A confirmed one is saved in the till's Rezervace.
 */

/**
 * What the website may offer, set on the till (Pokročilé nastavení → Rezervace z webu) and published with
 * the menu. Times are "HH:mm" in Prague time; [closedDays] are weekdays 1 (Monday) to 7 (Sunday).
 */
@Serializable data class ReservationRules(
    val maxGuests: Int = 8,
    val daysAhead: Int = 30,
    /** First and last time a table can be booked for. */
    val from: String = "11:00",
    val to: String = "21:00",
    /** The reservation must be at least this many minutes from now. */
    val noticeMinutes: Int = 60,
    val closedDays: List<Int> = emptyList(),
)

@Serializable data class WebReservation(
    val name: String,
    val phone: String,
    val email: String = "",
    val guests: Int,
    /** Day "yyyy-MM-dd" (Prague). */
    val date: String,
    /** Time "HH:mm" (Prague). */
    val time: String,
    val note: String = "",
    val lang: String = "cs",
    /** The customer ticked the box agreeing to the privacy policy. */
    val agreedTerms: Boolean = false,
)

/** A waiting web reservation as the back office hands it to a till. */
@Serializable data class WebReservationInbox(val id: Long, val reservation: WebReservation, val createdAt: Long = 0)
@Serializable data class WebReservationList(val reservations: List<WebReservationInbox> = emptyList())
/** The till's answer: [table] the staff chose ("" = none yet), [message] for the customer ("" = none). */
@Serializable data class WebReservationAck(val accepted: Boolean, val table: String = "", val message: String = "")
@Serializable data class WebReservationAccepted(val id: Long, val token: String)
/** For the customer's page: NEW, ACCEPTED or REJECTED, the time booked (epoch ms) and the restaurant's message, or null. */
@Serializable data class WebReservationStatus(val status: String, val at: Long? = null, val message: String? = null)
/** For the website's form: whether reservations are taken, and the rules to build the form from. */
@Serializable data class WebReservationInfo(val open: Boolean, val rules: ReservationRules? = null)

object WebReservations {
    const val MAX_NAME = 60
    const val MAX_NOTE = 200
    const val MAX_EMAIL = 80
    /** Highest "max guests" the owner can set. */
    const val GUESTS_LIMIT = 50
    const val DAYS_LIMIT = 180

    private val TIME = Regex("""^([01]\d|2[0-3]):[0-5]\d$""")
    private val DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val EMAIL = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")

    /** The rules with every value in a sane range (what the till publishes). */
    fun cleanRules(r: ReservationRules): ReservationRules = r.copy(
        maxGuests = r.maxGuests.coerceIn(1, GUESTS_LIMIT),
        daysAhead = r.daysAhead.coerceIn(0, DAYS_LIMIT),
        from = r.from.takeIf { TIME.matches(it) } ?: "11:00",
        to = r.to.takeIf { TIME.matches(it) } ?: "21:00",
        noticeMinutes = r.noticeMinutes.coerceIn(0, 7 * 24 * 60),
        closedDays = r.closedDays.filter { it in 1..7 }.distinct().sorted(),
    )

    /** The reservation's start in Prague time, or null when the date or time cannot be read. */
    fun start(r: WebReservation): LocalDateTime? {
        if (!DATE.matches(r.date) || !TIME.matches(r.time)) return null
        val d = runCatching { LocalDate.parse(r.date) }.getOrNull() ?: return null
        return d.atTime(LocalTime.parse(r.time))
    }

    /** The reservation's start as epoch millis, or null. */
    fun startMillis(r: WebReservation): Long? = start(r)?.atZone(OrderHours.ZONE)?.toInstant()?.toEpochMilli()

    private fun clean(s: String, max: Int) = s.filter { it >= ' ' && it != '\u007f' }.trim().take(max)

    /** The reservation with every free text cleaned and trimmed, as it is stored. */
    fun cleaned(r: WebReservation): WebReservation = r.copy(
        name = clean(r.name, MAX_NAME), phone = clean(r.phone, 20), email = clean(r.email, MAX_EMAIL),
        date = r.date.trim(), time = r.time.trim(), note = WebOrders.cleanNote(r.note).take(MAX_NOTE),
        lang = if (r.lang == "en") "en" else "cs",
    )

    /**
     * Why the reservation cannot be taken, or null when it fits the [rules]. Texts go to the customer, so in
     * [WebReservation.lang]. [now] is the current time in Prague.
     */
    fun problem(r: WebReservation, rules: ReservationRules, now: ZonedDateTime = ZonedDateTime.now(OrderHours.ZONE)): String? {
        val cs = r.lang != "en"
        fun t(c: String, e: String) = if (cs) c else e
        if (!r.agreedTerms) return t("Chybí souhlas se zpracováním údajů.", "Please agree to the privacy policy.")
        if (r.name.isBlank() || r.name.length > MAX_NAME) return t("Vyplňte jméno.", "Please fill in your name.")
        if (Notify.phoneDigits(r.phone) == null) return t("Neplatné telefonní číslo.", "Invalid phone number.")
        if (r.email.isNotEmpty() && (r.email.length > MAX_EMAIL || !EMAIL.matches(r.email))) return t("Neplatný e-mail.", "Invalid e-mail.")
        if (r.note.length > MAX_NOTE) return t("Poznámka je příliš dlouhá.", "The note is too long.")
        if (r.guests < 1) return t("Zadejte počet osob.", "Please enter the number of guests.")
        if (r.guests > rules.maxGuests) return t(
            "Online lze rezervovat nejvýš pro ${rules.maxGuests} osob. Pro větší skupinu nám prosím zavolejte.",
            "Online bookings are for at most ${rules.maxGuests} guests. For a larger group please call us.")
        val at = start(r) ?: return t("Neplatné datum nebo čas.", "Invalid date or time.")
        val today = now.toLocalDate()
        if (at.toLocalDate().isAfter(today.plusDays(rules.daysAhead.toLong()))) return t(
            "Rezervovat lze nejvýš ${rules.daysAhead} dní dopředu.", "Bookings can be made at most ${rules.daysAhead} days ahead.")
        if (at.atZone(OrderHours.ZONE).isBefore(now.plusMinutes(rules.noticeMinutes.toLong()))) return t(
            "Tento čas už rezervovat nelze. Zvolte prosím pozdější čas nebo nám zavolejte.",
            "This time can no longer be booked. Please choose a later time or call us.")
        if (at.dayOfWeek.value in rules.closedDays) return t("V tento den máme zavřeno.", "We are closed on that day.")
        val tm = at.toLocalTime()
        if (tm.isBefore(LocalTime.parse(rules.from)) || tm.isAfter(LocalTime.parse(rules.to))) return t(
            "Rezervovat lze od ${rules.from} do ${rules.to}.", "Bookings are possible from ${rules.from} to ${rules.to}.")
        return null
    }

    /** "po 16.11. 19:30" / "Mon 16.11. 19:30". */
    fun whenText(r: WebReservation, en: Boolean = r.lang == "en"): String =
        start(r)?.let { WebOrders.dayText(it.toLocalDate(), en) + " " + r.time } ?: (r.date + " " + r.time)

    /** The note kept with the reservation on the till: "WEB R12", the e-mail and the customer's note. */
    fun tillNote(id: Long, r: WebReservation): String =
        listOf("WEB R$id", r.email, r.note).filter { it.isNotBlank() }.joinToString(" · ")

    /** Ready-made reasons for declining a reservation, in Czech and English (picked by the customer's language). */
    val REJECT_REASONS: List<Pair<String, String>> = listOf(
        "V tento čas máme bohužel plno. Zvolte prosím jiný čas." to "Sorry, we are fully booked at this time. Please choose another time.",
        "V tento den máme bohužel zavřeno." to "Sorry, we are closed on that day.",
        "Pro tak velkou skupinu nám prosím zavolejte." to "For a group this size please give us a call.",
        "Tento den máme uzavřenou společnost." to "We have a private event on that day.",
    )

    /** WhatsApp text confirming the reservation. */
    fun confirmation(id: Long, r: WebReservation, table: String, business: String, phone: String, message: String = ""): String {
        val en = r.lang == "en"
        val parts = mutableListOf(
            if (en) "Hello ${r.name}, your table reservation for ${r.guests} at $business is confirmed: ${whenText(r, true)}."
            else "Dobrý den ${r.name}, potvrzujeme rezervaci pro ${r.guests} os. v $business: ${whenText(r, false)}.",
        )
        if (table.isNotBlank()) parts += if (en) "Table $table." else "Stůl $table."
        if (message.isNotBlank()) parts += message
        parts += if (en) "Reservation R$id. We look forward to seeing you." else "Rezervace R$id. Těšíme se na Vás."
        if (phone.isNotBlank()) parts += (if (en) "Phone: " else "Tel.: ") + phone
        return parts.joinToString(" ")
    }

    /** WhatsApp text declining the reservation. */
    fun rejection(id: Long, r: WebReservation, reason: String, business: String, phone: String): String {
        val en = r.lang == "en"
        val parts = mutableListOf(
            if (en) "Hello ${r.name}, sorry, we cannot confirm your reservation R$id for ${whenText(r, true)} at $business."
            else "Dobrý den ${r.name}, Vaši rezervaci R$id na ${whenText(r, false)} v $business bohužel nemůžeme potvrdit.",
        )
        if (reason.isNotBlank()) parts += reason
        if (phone.isNotBlank()) parts += (if (en) "Please call us: " else "Zavolejte nám prosím: ") + phone
        return parts.joinToString(" ")
    }
}
