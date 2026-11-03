package cz.flos.desktop

import cz.flos.pos.KeyValueStore
import java.util.prefs.Preferences

/** Settings in the per-user Java preferences (on Windows: HKEY_CURRENT_USER\Software\JavaSoft\Prefs\cz\flos\pos). */
class RegistryStore : KeyValueStore {
    private val node = Preferences.userRoot().node("cz/flos/pos")

    override fun get(key: String): String? = node.get(key, null)

    @Synchronized
    override fun put(values: Map<String, String>) {
        values.forEach { (k, v) -> node.put(k, v) }
        node.flush()
    }
}
