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
    entities = [CategoryEntity::class, ProductEntity::class, SaleEntity::class, SaleItemEntity::class, EetRecordEntity::class, CashMovementEntity::class, BankPaymentEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class FlosDatabase : RoomDatabase() {
    abstract fun catalog(): CatalogDao
    abstract fun sales(): SalesDao
    abstract fun eet(): EetDao
    abstract fun cash(): CashDao
    abstract fun bank(): BankDao

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

        /** 2 → 3: tips and bank transfer confirmation on sales; payments read from the bank. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE sales ADD COLUMN tip INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE sales ADD COLUMN bankStatus TEXT")
                connection.execSQL("ALTER TABLE sales ADD COLUMN bankPaid INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE sales ADD COLUMN bankConfirmedAt INTEGER")
                connection.execSQL("ALTER TABLE sales ADD COLUMN payCurrency TEXT")
                connection.execSQL("ALTER TABLE sales ADD COLUMN payAmount INTEGER")
                connection.execSQL("ALTER TABLE sales ADD COLUMN eurRate REAL")
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bank_payments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceId` TEXT NOT NULL, " +
                        "`readAt` INTEGER NOT NULL, `bookedAt` INTEGER, `amount` INTEGER NOT NULL, `currency` TEXT NOT NULL, `variableSymbol` TEXT, `counterAccount` TEXT, " +
                        "`counterName` TEXT, `message` TEXT, `saleId` INTEGER, `status` TEXT NOT NULL)",
                )
                connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bank_payments_sourceId` ON `bank_payments` (`sourceId`)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_bank_payments_saleId` ON `bank_payments` (`saleId`)")
            }
        }

        /** 3 → 4: refunds of single items, tables, product variants and extras. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE sales ADD COLUMN refundOf INTEGER")
                connection.execSQL("ALTER TABLE sales ADD COLUMN tableName TEXT")
                connection.execSQL("ALTER TABLE products ADD COLUMN options TEXT")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}
