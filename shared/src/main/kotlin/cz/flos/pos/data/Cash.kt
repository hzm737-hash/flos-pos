package cz.flos.pos.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Money put into or taken out of the cash drawer outside of sales. A shift runs from one
 * CLOSE to the next; FLOAT is the change money left in the drawer at the start.
 */
@Entity(tableName = "cash_movements", indices = [Index("at")])
data class CashMovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Epoch milliseconds. */
    val at: Long,
    val kind: String,
    /** Haléře; for CLOSE the cash counted in the drawer. */
    val amount: Long,
    val note: String = "",
    val cashier: String = "",
    /** For CLOSE: the amount the till expected, so the difference can be shown later. */
    val expected: Long? = null,
) {
    val cashKind: CashKind get() = CashKind.entries.firstOrNull { it.name == kind } ?: CashKind.IN
}

enum class CashKind(val label: String) {
    FLOAT("Počáteční stav"),
    IN("Vklad"),
    OUT("Výběr"),
    CLOSE("Uzavření směny"),
}

@Dao
interface CashDao {
    @Insert
    suspend fun insert(m: CashMovementEntity): Long

    /** Time of the last shift close, or 0 when no shift was closed yet. */
    @Query("SELECT COALESCE(MAX(at), 0) FROM cash_movements WHERE kind = 'CLOSE'")
    fun lastClose(): Flow<Long>

    @Query("SELECT * FROM cash_movements WHERE at > :since AND kind != 'CLOSE' ORDER BY at")
    fun since(since: Long): Flow<List<CashMovementEntity>>

    @Query("SELECT * FROM cash_movements WHERE kind = 'CLOSE' ORDER BY at DESC LIMIT :limit")
    fun closes(limit: Int = 30): Flow<List<CashMovementEntity>>
}

/** Cash in the drawer during the current shift. All amounts in haléře. */
data class CashState(
    val since: Long,
    val float: Long,
    val sales: Long,
    val refunds: Long,
    val cashIn: Long,
    val cashOut: Long,
) {
    val expected: Long get() = float + sales - refunds + cashIn - cashOut
}
