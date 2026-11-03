package cz.flos.pos.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * The till's local database. Android and Windows build it with their own SQLite driver
 * (see FlosApp and desktop Main), using [FlosDatabase.MIGRATIONS].
 */
@Database(
    entities = [CategoryEntity::class, ProductEntity::class, SaleEntity::class, SaleItemEntity::class, EetRecordEntity::class, CashMovementEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class FlosDatabase : RoomDatabase() {
    abstract fun catalog(): CatalogDao
    abstract fun sales(): SalesDao
    abstract fun eet(): EetDao
    abstract fun cash(): CashDao

    companion object {
        const val FILE_NAME = "flos.db"
        /** 1 → 2: open orders (delivery, takeaway) with status, order number and note; icons and barcodes for products; cash drawer movements and shifts. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE sales ADD COLUMN status TEXT NOT NULL DEFAULT 'PAID'")
                connection.execSQL("ALTER TABLE sales ADD COLUMN orderNumber INTEGER")
                connection.execSQL("ALTER TABLE sales ADD COLUMN note TEXT NOT NULL DEFAULT ''")
                connection.execSQL("ALTER TABLE sales ADD COLUMN openedAt INTEGER")
                connection.execSQL("ALTER TABLE products ADD COLUMN icon TEXT")
                connection.execSQL("ALTER TABLE categories ADD COLUMN icon TEXT")
                connection.execSQL("ALTER TABLE products ADD COLUMN barcode TEXT")
                connection.execSQL("ALTER TABLE sales ADD COLUMN cancelledAt INTEGER")
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cash_movements` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, `amount` INTEGER NOT NULL, `note` TEXT NOT NULL, `cashier` TEXT NOT NULL, `expected` INTEGER)",
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_cash_movements_at` ON `cash_movements` (`at`)")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
    }
}
