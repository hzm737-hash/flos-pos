package cz.flos.pos.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** What a note belongs to. */
enum class NoteTarget { GENERAL, CUSTOMER, PRODUCT }

/**
 * A note, optionally with an attached document (contract, invoice, photo). The file itself
 * lives in the app's folder under attachments/, named by [fileName].
 */
@Entity(tableName = "notes", indices = [Index(value = ["target", "targetId"])])
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val target: String = NoteTarget.GENERAL.name,
    val targetId: Long? = null,
    val text: String,
    val createdAt: Long,
    val author: String = "",
    /** Stored file name of the attachment, or null. */
    val fileName: String? = null,
    /** Name shown to the user, e.g. "Smlouva.pdf". */
    val fileTitle: String? = null,
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE target = :target AND ((:targetId IS NULL AND targetId IS NULL) OR targetId = :targetId) ORDER BY createdAt DESC")
    fun of(target: String, targetId: Long?): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun all(): Flow<List<NoteEntity>>

    @Upsert
    suspend fun upsert(n: NoteEntity): Long

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)
}
