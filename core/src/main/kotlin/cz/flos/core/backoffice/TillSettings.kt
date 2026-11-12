package cz.flos.core.backoffice

import kotlinx.serialization.Serializable

/**
 * Which till settings the back office may see and change. The till and the server both use these lists,
 * so a secret never leaves the device even if one side is out of date.
 */
object TillSettingKeys {
    /** Never sent: passwords, tokens and the till's own bookkeeping. The back office only learns whether they are set. */
    val SECRET = setOf("eetCertPassword", "imapPassword", "fioToken")
    val LOCAL = setOf("boUrl", "boToken", "boDeviceName", "boCatalogSince", "boDeletedCategories", "boStaff", "boLastSync",
        // Who is serving right now changes all day; it is not a setting.
        "cashier")

    /** Kept per till (printer, screen language, address of the branch); everything else is the same for the whole company. */
    val DEVICE = setOf(
        "language", "paperWidth", "charset", "printerAddress", "printerName", "autoPrint", "printerType",
        "printerHost", "printerPort", "openDrawer", "printerNativeQr", "customerDisplay", "eetIdPokl", "eetIdJednotky", "address1", "address2",
    )

    private val NAME = Regex("^[A-Za-z][A-Za-z0-9_]{0,39}$")
    const val MAX_VALUE = 20_000

    /** True for a setting that may travel between the till and the back office. */
    fun synced(key: String): Boolean = NAME.matches(key) && key !in SECRET && key !in LOCAL && !key.startsWith("bo")
}

/** One setting changed on the till: its value and when it was changed (0 = not changed here, just what the till has). */
@Serializable data class BoSettingChange(val value: String, val changedAt: Long = 0)

@Serializable data class BoSettingsUpload(
    val changes: Map<String, BoSettingChange> = emptyMap(),
    /** Server time of the previous answer; 0 = send everything. */
    val since: Long = 0,
    /** Which secrets are set on the till (e.g. "eetCertificate" → true), shown in the back office without their values. */
    val secrets: Map<String, Boolean> = emptyMap(),
)

/** The settings the till should use, changed in the back office (or on another till) since the last call. */
@Serializable data class BoSettingsResult(val serverTime: Long, val values: Map<String, BoSettingChange> = emptyMap())
