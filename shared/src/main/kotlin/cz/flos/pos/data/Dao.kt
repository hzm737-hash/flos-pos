package cz.flos.pos.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    fun categories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM products WHERE active = 1 ORDER BY sortOrder, name")
    fun products(): Flow<List<ProductEntity>>

    @Upsert
    suspend fun upsertCategory(c: CategoryEntity): Long

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategory(id: Long)

    @Query("UPDATE products SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)

    @Upsert
    suspend fun upsertProduct(p: ProductEntity): Long

    /** Products are hidden rather than deleted so old receipts keep their history. */
    @Query("UPDATE products SET active = 0 WHERE id = :id")
    suspend fun deactivateProduct(id: Long)

    @Query("SELECT COUNT(*) FROM products")
    suspend fun productCount(): Int
}

@Dao
interface SalesDao {
    @Insert
    suspend fun insertSale(s: SaleEntity): Long

    @Insert
    suspend fun insertItems(items: List<SaleItemEntity>)

    @Transaction
    suspend fun insert(sale: SaleEntity, items: List<SaleItemEntity>): Long {
        val id = insertSale(sale)
        insertItems(items.map { it.copy(saleId = id) })
        return id
    }

    @Update
    suspend fun update(s: SaleEntity)

    @Transaction
    @Query("SELECT * FROM sales ORDER BY createdAt DESC LIMIT :limit")
    fun recent(limit: Int = 300): Flow<List<SaleWithItems>>

    /** Paid (and stornoed) sales of a period, for reports. Open and cancelled orders are left out. */
    @Transaction
    @Query("SELECT * FROM sales WHERE status = 'PAID' AND createdAt >= :from AND createdAt < :to ORDER BY createdAt")
    suspend fun between(from: Long, to: Long): List<SaleWithItems>

    /** Everything in a period, newest first, for the history screen. Open orders are always included. */
    @Transaction
    @Query("SELECT * FROM sales WHERE (createdAt >= :from AND createdAt < :to) OR status = 'OPEN' ORDER BY createdAt DESC")
    fun history(from: Long, to: Long): Flow<List<SaleWithItems>>

    @Transaction
    @Query("SELECT * FROM sales WHERE status = 'OPEN' ORDER BY createdAt")
    fun openOrders(): Flow<List<SaleWithItems>>

    @Query("DELETE FROM sale_items WHERE saleId = :saleId")
    suspend fun deleteItems(saleId: Long)

    /** Replaces the items of an open order after it was changed in the cart. */
    @Transaction
    suspend fun replace(sale: SaleEntity, items: List<SaleItemEntity>) {
        update(sale)
        deleteItems(sale.id)
        insertItems(items.map { it.copy(id = 0, saleId = sale.id) })
    }

    @Transaction
    @Query("SELECT * FROM sales WHERE id = :id")
    suspend fun get(id: Long): SaleWithItems?
}
