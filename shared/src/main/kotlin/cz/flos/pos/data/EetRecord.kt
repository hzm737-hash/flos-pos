package cz.flos.pos.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

enum class EetStatus {
    /** Not confirmed yet; will be (re)sent automatically. */
    PENDING,
    /** Accepted; [EetRecordEntity.pok] holds the confirmation code. */
    CONFIRMED,
    /** Rejected with an error that resending cannot fix (bad data, bad signature...). Needs attention. */
    REJECTED,
    /** Sent in verification mode only (not recorded). */
    VERIFIED,
}

/**
 * One sale (or storno) to be recorded in EET 2.0. Kept forever, so the till always has a
 * record of what was sent, when, and what the tax administration answered.
 */
@Entity(tableName = "eet_records", indices = [Index("saleId"), Index("status")])
data class EetRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleId: Long,
    val storno: Boolean = false,
    val eic: String,
    val idJednotky: Int,
    val idPokl: String,
    val poradCis: String,
    /** ISO 8601 with offset, exactly as sent in dat_trzby. */
    val datTrzby: String,
    /** Epoch millis of the sale, for the 48-hour deadline. */
    val saleTime: Long,
    val amount: Long,
    val status: EetStatus = EetStatus.PENDING,
    val pok: String? = null,
    val test: Boolean = false,
    val attempts: Int = 0,
    val lastAttemptAt: Long? = null,
    val lastUuid: String? = null,
    val lastError: String? = null,
    val warnings: String? = null,
)

@Dao
interface EetDao {
    @Insert
    suspend fun insert(r: EetRecordEntity): Long

    @Update
    suspend fun update(r: EetRecordEntity)

    @Query("SELECT * FROM eet_records WHERE id = :id")
    suspend fun get(id: Long): EetRecordEntity?

    @Query("SELECT * FROM eet_records WHERE status = 'PENDING' ORDER BY saleTime")
    suspend fun pending(): List<EetRecordEntity>

    /** Records that still need sending or attention, oldest first. */
    @Query("SELECT * FROM eet_records WHERE status IN ('PENDING', 'REJECTED') ORDER BY saleTime")
    fun open(): Flow<List<EetRecordEntity>>

    @Query("SELECT * FROM eet_records WHERE saleId = :saleId ORDER BY id")
    suspend fun forSale(saleId: Long): List<EetRecordEntity>

    @Query("SELECT * FROM eet_records ORDER BY id DESC LIMIT 500")
    fun recent(): Flow<List<EetRecordEntity>>
}
