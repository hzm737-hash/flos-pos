package cz.flos.pos.ui

import java.util.Locale

/** App languages. Czech is the default; receipts and EET data stay in Czech. */
enum class Language(val code: String, val label: String) {
    CS("cs", "Čeština"),
    EN("en", "English"),
}

/**
 * Tiny translation layer: the Czech text is the key, English comes from [En.strings].
 * Placeholders use String.format syntax (%s, %d).
 */
object I18n {
    @Volatile var language: Language = Language.CS

    fun tr(cs: String, vararg args: Any?): String {
        val text = if (language == Language.EN) En.strings[cs] ?: cs else cs
        return if (args.isEmpty()) text else String.format(Locale.ROOT, text, *args)
    }
}

fun tr(cs: String, vararg args: Any?): String = I18n.tr(cs, *args)
