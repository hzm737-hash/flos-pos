package cz.flos.core.backoffice

import kotlinx.serialization.Serializable

/**
 * What the back office deleted when a till cleared its test data (Pokročilé nastavení → Smazat testovací data):
 * that till's receipts, clock-ins and live picture, and the company's orders from the website and the tables.
 */
@Serializable
data class BoTestDataReset(
    val sales: Int = 0,
    val webOrders: Int = 0,
    val tableOrders: Int = 0,
    val attendance: Int = 0,
)
