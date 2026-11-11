package cz.flos.core.backoffice

import kotlinx.serialization.Serializable

/**
 * What is going on at a till right now, sent to the back office to be read there (never changed there):
 * open tables and orders, the cash drawer, customers, reservations, documents and the audit log.
 * Amounts in haléře, times in epoch milliseconds.
 */
@Serializable data class BoTillSnapshot(
    val takenAt: Long,
    val openOrders: List<BoOpenOrder> = emptyList(),
    val cash: BoCashState? = null,
    val shiftCloses: List<BoShiftClose> = emptyList(),
    val cashMoves: List<BoCashMove> = emptyList(),
    val customers: List<BoCustomer> = emptyList(),
    val reservations: List<BoReservation> = emptyList(),
    val documents: List<BoDocument> = emptyList(),
    val audit: List<BoAuditEntry> = emptyList(),
    /** Receipts still waiting to be sent to EET (or rejected). */
    val eetWaiting: Int = 0,
) {
    companion object {
        /** Limits so one snapshot stays small; the newest entries are kept. */
        const val MAX_CUSTOMERS = 2000
        const val MAX_LIST = 300
        const val MAX_BYTES = 2_000_000
    }
}

@Serializable data class BoOpenOrder(
    val number: Int? = null, val table: String? = null, val note: String = "", val openedAt: Long? = null,
    val total: Long = 0, val cashier: String = "", val serviceType: String? = null, val kitchenReady: Boolean = false,
    val items: List<BoOrderLine> = emptyList(),
)
@Serializable data class BoOrderLine(val name: String, val quantity: Double, val unitPrice: Long, val discountPercent: Int = 0)
@Serializable data class BoCashState(val since: Long, val float: Long, val sales: Long, val refunds: Long, val cashIn: Long, val cashOut: Long, val expected: Long)
@Serializable data class BoShiftClose(val at: Long, val counted: Long, val expected: Long? = null, val cashier: String = "")
@Serializable data class BoCashMove(val at: Long, val kind: String, val amount: Long, val note: String = "", val cashier: String = "")
@Serializable data class BoCustomer(
    val name: String, val phone: String = "", val email: String = "", val points: Long = 0, val discountPercent: Int = 0,
    val birthday: String? = null, val note: String = "", val cardCode: String? = null,
)
@Serializable data class BoReservation(
    val at: Long, val name: String, val phone: String = "", val guests: Int = 2, val minutes: Int = 120,
    val table: String? = null, val note: String = "", val status: String = "BOOKED",
)
@Serializable data class BoDocument(
    val kind: String, val number: String, val createdAt: Long, val customer: String = "", val total: Long = 0,
    val status: String = "OPEN", val dueDay: Long? = null, val note: String = "",
)
@Serializable data class BoAuditEntry(val at: Long, val text: String, val author: String = "")

/** A newer version of the till app waiting in the back office; [url] downloads it without signing in. */
@Serializable data class BoUpdateInfo(
    val available: Boolean = false, val versionCode: Int = 0, val versionName: String = "", val notes: String = "",
    val size: Long = 0, val sha256: String = "", val url: String = "",
)
