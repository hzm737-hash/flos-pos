package cz.flos.pos.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import cz.flos.core.Booking
import cz.flos.core.Cart
import cz.flos.core.CartText
import cz.flos.core.DocumentKind
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Open, turned into a sale, or cancelled. */
enum class DocStatus { OPEN, DONE, CANCELLED }

/**
 * A quotation, pro forma invoice or customer order. Kept apart from sales: it is not a tax
 * document and never goes to EET; it becomes a normal sale when loaded into the cart and paid.
 */
@Entity(tableName = "documents", indices = [Index("kind"), Index("createdAt")])
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [DocumentKind] name: QUOTE, PROFORMA or SALES_ORDER. */
    val kind: String,
    val number: String,
    val createdAt: Long,
    val customerId: Long? = null,
    val customerName: String = "",
    /** Cart lines, see [CartText]. */
    val items: String,
    val total: Long,
    val note: String = "",
    /** Quote valid until, pro forma due date, or the day of a customer order (epoch day). */
    val dueDay: Long? = null,
    val status: String = DocStatus.OPEN.name,
    val cashier: String = "",
) {
    val docKind: DocumentKind get() = runCatching { DocumentKind.valueOf(kind) }.getOrDefault(DocumentKind.QUOTE)
    val docStatus: DocStatus get() = runCatching { DocStatus.valueOf(status) }.getOrDefault(DocStatus.OPEN)
    val due: LocalDate? get() = dueDay?.let(LocalDate::ofEpochDay)
    fun cart(): Cart = CartText.decode(items)
}

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY createdAt DESC")
    fun all(): Flow<List<DocumentEntity>>

    @Query("SELECT number FROM documents WHERE kind = :kind")
    suspend fun numbers(kind: String): List<String>

    @Upsert
    suspend fun upsert(d: DocumentEntity): Long

    @Query("UPDATE documents SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun delete(id: Long)
}

/** Booked, guests arrived and sat down, did not come, or cancelled. */
enum class BookingStatus { BOOKED, SEATED, NO_SHOW, CANCELLED }

/** A table reservation. */
@Entity(tableName = "reservations", indices = [Index("at")])
data class ReservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val guests: Int = 2,
    /** Start time, epoch millis. */
    val at: Long,
    val minutes: Int = 120,
    val tableName: String? = null,
    val note: String = "",
    val status: String = BookingStatus.BOOKED.name,
    val createdAt: Long = 0,
) {
    val bookingStatus: BookingStatus get() = runCatching { BookingStatus.valueOf(status) }.getOrDefault(BookingStatus.BOOKED)
    val start: LocalDateTime get() = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault())
    /** Booked guests still hold the table; seated ones are on an open bill already. */
    fun booking(): Booking = Booking(id, tableName, start, minutes, active = bookingStatus == BookingStatus.BOOKED)
}

@Dao
interface ReservationDao {
    /** Reservations from [from] on (epoch millis), soonest first. */
    @Query("SELECT * FROM reservations WHERE at >= :from ORDER BY at")
    fun from(from: Long): Flow<List<ReservationEntity>>

    @Upsert
    suspend fun upsert(r: ReservationEntity): Long

    @Query("UPDATE reservations SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("DELETE FROM reservations WHERE id = :id")
    suspend fun delete(id: Long)
}
