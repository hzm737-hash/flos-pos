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
    entities = [CategoryEntity::class, ProductEntity::class, SaleEntity::class, SaleItemEntity::class, EetRecordEntity::class, CashMovementEntity::class, BankPaymentEntity::class, CustomerEntity::class,
        DocumentEntity::class, ReservationEntity::class],
    version = 8,
    exportSchema = false,
)
abstract class FlosDatabase : RoomDatabase() {
    abstract fun catalog(): CatalogDao
    abstract fun sales(): SalesDao
    abstract fun eet(): EetDao
    abstract fun cash(): CashDao
    abstract fun bank(): BankDao
    abstract fun customers(): CustomerDao
    abstract fun documents(): DocumentDao
    abstract fun reservations(): ReservationDao

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

        /** 4 → 5: back office sync, customers and loyalty, kitchen state of orders. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE sales ADD COLUMN uploaded INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE sales ADD COLUMN customerId INTEGER")
                connection.execSQL("ALTER TABLE sales ADD COLUMN kitchenReadyAt INTEGER")
                connection.execSQL("ALTER TABLE products ADD COLUMN remoteId INTEGER")
                connection.execSQL("ALTER TABLE categories ADD COLUMN remoteId INTEGER")
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `customers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`phone` TEXT NOT NULL, `email` TEXT NOT NULL, `note` TEXT NOT NULL, `discountPercent` INTEGER NOT NULL, " +
                        "`points` INTEGER NOT NULL, `cardCode` TEXT, `createdAt` INTEGER NOT NULL)",
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_customers_name` ON `customers` (`name`)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_customers_cardCode` ON `customers` (`cardCode`)")
            }
        }

        /**
         * 5 → 6: fractional item quantities (half portions). SQLite can't change a column type,
         * so sale_items is rebuilt with quantity as REAL and the rows copied over.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sale_items_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `saleId` INTEGER NOT NULL, " +
                        "`productId` INTEGER NOT NULL, `name` TEXT NOT NULL, `unitPrice` INTEGER NOT NULL, `vatPercent` INTEGER NOT NULL, " +
                        "`quantity` REAL NOT NULL, `discountPercent` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`saleId`) REFERENCES `sales`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                connection.execSQL(
                    "INSERT INTO `sale_items_new` (`id`, `saleId`, `productId`, `name`, `unitPrice`, `vatPercent`, `quantity`, `discountPercent`) " +
                        "SELECT `id`, `saleId`, `productId`, `name`, `unitPrice`, `vatPercent`, CAST(`quantity` AS REAL), `discountPercent` FROM `sale_items`",
                )
                connection.execSQL("DROP TABLE `sale_items`")
                connection.execSQL("ALTER TABLE `sale_items_new` RENAME TO `sale_items`")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_sale_items_saleId` ON `sale_items` (`saleId`)")
            }
        }

        /** 6 → 7: price lists on products; mixed payments and the service type (na místě, s sebou, rozvoz) on sales. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE products ADD COLUMN prices TEXT")
                connection.execSQL("ALTER TABLE sales ADD COLUMN paymentParts TEXT")
                connection.execSQL("ALTER TABLE sales ADD COLUMN cashPart INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE sales ADD COLUMN serviceType TEXT")
            }
        }

        /** 7 → 8: quotations, pro forma invoices and customer orders; table reservations. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `documents` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`number` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `customerId` INTEGER, `customerName` TEXT NOT NULL, " +
                        "`items` TEXT NOT NULL, `total` INTEGER NOT NULL, `note` TEXT NOT NULL, `dueDay` INTEGER, `status` TEXT NOT NULL, `cashier` TEXT NOT NULL)",
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_documents_kind` ON `documents` (`kind`)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_documents_createdAt` ON `documents` (`createdAt`)")
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reservations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`phone` TEXT NOT NULL, `guests` INTEGER NOT NULL, `at` INTEGER NOT NULL, `minutes` INTEGER NOT NULL, `tableName` TEXT, " +
                        "`note` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_reservations_at` ON `reservations` (`at`)")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
    }
}
