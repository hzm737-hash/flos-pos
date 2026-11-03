package cz.flos.pos.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The till's local database. Android and Windows build it with their own SQLite driver
 * (see FlosApp and desktop Main), using [FlosDatabase.MIGRATIONS].
 */
@Database(
    entities = [CategoryEntity::class, ProductEntity::class, SaleEntity::class, SaleItemEntity::class, EetRecordEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class FlosDatabase : RoomDatabase() {
    abstract fun catalog(): CatalogDao
    abstract fun sales(): SalesDao
    abstract fun eet(): EetDao

    companion object {
        const val FILE_NAME = "flos.db"
        val MIGRATIONS: Array<androidx.room.migration.Migration> = arrayOf()
    }
}
