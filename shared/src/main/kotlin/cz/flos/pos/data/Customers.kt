package cz.flos.pos.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A regular customer: contact, a personal discount and loyalty points. */
@Entity(tableName = "customers", indices = [Index("name"), Index("cardCode")])
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val email: String = "",
    val note: String = "",
    /** Applied to the whole cart when the customer is chosen. */
    val discountPercent: Int = 0,
    val points: Long = 0,
    /** Loyalty card number or barcode. */
    val cardCode: String? = null,
    val createdAt: Long = 0,
    /** Birthday as typed ("15.3." or "15.3.1990"), see cz.flos.core.Birthdays (Pokročilé nastavení). */
    val birthday: String? = null,
)

data class CustomerTotals(val count: Int, val total: Long)

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers ORDER BY name")
    fun all(): Flow<List<CustomerEntity>>

    @Upsert
    suspend fun upsert(c: CustomerEntity): Long

    @Query("DELETE FROM customers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM customers WHERE id = :id")
    suspend fun get(id: Long): CustomerEntity?

    @Query("SELECT * FROM customers WHERE cardCode = :code LIMIT 1")
    suspend fun byCard(code: String): CustomerEntity?

    @Query("UPDATE customers SET points = MAX(0, points + :delta) WHERE id = :id")
    suspend fun addPoints(id: Long, delta: Long)

    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(received), 0) AS total FROM sales WHERE customerId = :id AND status = 'PAID' AND cancelled = 0")
    suspend fun totals(id: Long): CustomerTotals
}
