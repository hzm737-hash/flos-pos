package cz.flos.pos

import android.content.Context

/** [KeyValueStore] on SharedPreferences. Values written by older versions as booleans or ints are read as text. */
class PrefsStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("flos_settings", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.all[key]?.toString()

    override fun put(values: Map<String, String>) {
        val editor = prefs.edit()
        // A key may still hold an older boolean/int value; remove it first so the type can change.
        values.forEach { (k, v) -> editor.remove(k).putString(k, v) }
        editor.commit()
    }
}
