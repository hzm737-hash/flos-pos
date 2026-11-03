package cz.flos.pos.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A payment that arrived on the business bank account (from a notification e-mail or the bank API). */
@Entity(tableName = "bank_payments", indices = [Index(value = ["sourceId"], unique = true), Index("saleId")])
data class BankPaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Id from the source so a payment is never counted twice ("imap:<uidvalidity>:<uid>", "fio:<id>"). */
    val sourceId: String,
    /** When Flos read it, epoch milliseconds. */
    val readAt: Long,
    /** When the bank booked it, if the source says. */
    val bookedAt: Long? = null,
    val amount: Long,
    /** Currency of [amount] (the account's currency). */
    val currency: String = "CZK",
    val variableSymbol: String? = null,
    val counterAccount: String? = null,
    val counterName: String? = null,
    val message: String? = null,
    /** The receipt it paid, or null while nobody assigned it. */
    val saleId: Long? = null,
    /** MATCHED, UNMATCHED or IGNORED (not a payment for a receipt, e.g. a refund from a supplier). */
    val status: String = BankPaymentStatus.UNMATCHED.name,
)

enum class BankPaymentStatus { MATCHED, UNMATCHED, IGNORED }

@Dao
interface BankDao {
    /** Returns -1 when the payment was read before. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(p: BankPaymentEntity): Long

    @Update
    suspend fun update(p: BankPaymentEntity)

    @Query("SELECT * FROM bank_payments WHERE status = 'UNMATCHED' ORDER BY readAt DESC")
    fun unmatched(): Flow<List<BankPaymentEntity>>

    @Query("SELECT * FROM bank_payments ORDER BY readAt DESC LIMIT :limit")
    fun recent(limit: Int = 100): Flow<List<BankPaymentEntity>>

    @Query("SELECT * FROM bank_payments WHERE saleId = :saleId ORDER BY readAt")
    suspend fun forSale(saleId: Long): List<BankPaymentEntity>

    /** Receipts paid by QR code or transfer whose money has not (fully) arrived. */
    @Query("SELECT * FROM sales WHERE status = 'PAID' AND cancelled = 0 AND bankStatus IN ('WAITING', 'UNDERPAID') ORDER BY createdAt DESC")
    suspend fun waitingSales(): List<SaleEntity>

    @Query("SELECT * FROM sales WHERE status = 'PAID' AND cancelled = 0 AND bankStatus IN ('WAITING', 'UNDERPAID') ORDER BY createdAt DESC")
    fun waitingSalesFlow(): Flow<List<SaleEntity>>

    /** Transfers whose money arrived in a period (possibly for receipts of earlier days). */
    @Query("SELECT * FROM sales WHERE bankStatus = 'CONFIRMED' AND cancelled = 0 AND bankConfirmedAt >= :from AND bankConfirmedAt < :to")
    suspend fun confirmedBetween(from: Long, to: Long): List<SaleEntity>

    /** Tips and paid sales of a period, for the monthly tax-free tips limit. */
    @Query("SELECT COALESCE(SUM(tip), 0) FROM sales WHERE status = 'PAID' AND cancelled = 0 AND createdAt >= :from AND createdAt < :to")
    suspend fun tipsBetween(from: Long, to: Long): Long

    @Query("SELECT COALESCE(SUM(received), 0) FROM sales WHERE status = 'PAID' AND cancelled = 0 AND createdAt >= :from AND createdAt < :to")
    suspend fun revenueBetween(from: Long, to: Long): Long
}
