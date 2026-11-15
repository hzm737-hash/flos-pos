package cz.flos.pos.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/** How much test data the till holds, shown before it is cleared. */
data class TestDataCounts(
    val sales: Int = 0,
    val openOrders: Int = 0,
    val cashMovements: Int = 0,
    val bankPayments: Int = 0,
    val documents: Int = 0,
    val reservations: Int = 0,
    /** Receipts recorded for real in EET (not test mode, not just verified): these must not be deleted. */
    val realEet: Int = 0,
)

/**
 * Clears what was sold while trying the till out (Pokročilé nastavení → Smazat testovací data):
 * receipts and orders, EET records, cash drawer shifts, payments read from the bank and the storno log.
 * Products, categories, customers, notes and every setting stay.
 */
@Dao
interface TestResetDao {
    @Query("SELECT COUNT(*) FROM sales WHERE status != 'OPEN'") suspend fun closedSales(): Int
    @Query("SELECT COUNT(*) FROM sales WHERE status = 'OPEN'") suspend fun openSales(): Int
    @Query("SELECT COUNT(*) FROM cash_movements") suspend fun cashCount(): Int
    @Query("SELECT COUNT(*) FROM bank_payments") suspend fun bankCount(): Int
    @Query("SELECT COUNT(*) FROM documents") suspend fun documentCount(): Int
    @Query("SELECT COUNT(*) FROM reservations") suspend fun reservationCount(): Int
    @Query("SELECT COUNT(*) FROM eet_records WHERE status = 'CONFIRMED' AND test = 0") suspend fun realEetCount(): Int

    @Query("DELETE FROM sale_items") suspend fun deleteSaleItems()
    @Query("DELETE FROM sales") suspend fun deleteSales()
    @Query("DELETE FROM eet_records") suspend fun deleteEet()
    @Query("DELETE FROM cash_movements") suspend fun deleteCash()
    @Query("DELETE FROM bank_payments") suspend fun deleteBank()
    @Query("DELETE FROM notes WHERE target = 'AUDIT'") suspend fun deleteAuditLog()
    @Query("DELETE FROM documents") suspend fun deleteDocuments()
    @Query("DELETE FROM reservations") suspend fun deleteReservations()
    @Query("UPDATE customers SET points = 0") suspend fun clearPoints()

    @Transaction
    suspend fun counts(): TestDataCounts = TestDataCounts(
        closedSales(), openSales(), cashCount(), bankCount(), documentCount(), reservationCount(), realEetCount(),
    )

    @Transaction
    suspend fun clear(documents: Boolean, reservations: Boolean, points: Boolean) {
        deleteSaleItems()
        deleteSales()
        deleteEet()
        deleteCash()
        deleteBank()
        deleteAuditLog()
        if (documents) deleteDocuments()
        if (reservations) deleteReservations()
        if (points) clearPoints()
    }
}
