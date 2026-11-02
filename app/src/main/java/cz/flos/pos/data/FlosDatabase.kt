package cz.flos.pos.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
        fun create(context: Context): FlosDatabase =
            Room.databaseBuilder(context, FlosDatabase::class.java, "flos.db").build()
    }
}
