package cz.flos.core

import java.time.LocalDate
import java.time.MonthDay

/** Customers' birthdays as typed by the cashier: "15.3.", "15. 3. 1990" or "1990-03-15". */
object Birthdays {
    private val CZ = Regex("""^\s*(\d{1,2})\s*\.\s*(\d{1,2})\s*\.?\s*(\d{4})?\s*$""")
    private val ISO = Regex("""^\s*(\d{4})-(\d{1,2})-(\d{1,2})\s*$""")

    fun parse(text: String?): MonthDay? {
        val t = text ?: return null
        val (d, m) = CZ.find(t)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            ?: ISO.find(t)?.let { it.groupValues[3].toInt() to it.groupValues[2].toInt() }
            ?: return null
        return runCatching { MonthDay.of(m, d) }.getOrNull()
    }

    /** Days until the next birthday (0 = today). 29 February counts as 28 February in other years. */
    fun daysUntil(birthday: MonthDay, today: LocalDate): Int {
        fun on(year: Int): LocalDate = if (birthday.isValidYear(year)) birthday.atYear(year) else LocalDate.of(year, 2, 28)
        val thisYear = on(today.year)
        val next = if (thisYear.isBefore(today)) on(today.year + 1) else thisYear
        return (next.toEpochDay() - today.toEpochDay()).toInt()
    }
}
