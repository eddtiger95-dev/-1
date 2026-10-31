package app.asiascanner

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

object Status {
    const val PENDING = "PENDING"
    const val DONE = "DONE"
    const val ERROR = "ERROR"
    const val SKIPPED = "SKIPPED"
}

@Entity(tableName = "items")
data class Item(
    @PrimaryKey @ColumnInfo(name = "rowIdx") val row: Int,
    val name: String,
    val qrPath: String?,
    val qrText: String?,
    val status: String,
    val price: Double?,
    val appPrice: Double?,
    val error: String?
)

@Dao
interface ItemDao {
    @Query("SELECT * FROM items ORDER BY rowIdx")
    fun observeAll(): Flow<List<Item>>

    @Query("SELECT * FROM items ORDER BY rowIdx")
    suspend fun all(): List<Item>

    @Query("SELECT * FROM items WHERE status = 'PENDING' ORDER BY rowIdx LIMIT 1")
    suspend fun nextPending(): Item?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<Item>)

    @Update
    suspend fun update(item: Item)

    @Query("UPDATE items SET status = 'PENDING', error = NULL WHERE status = 'ERROR'")
    suspend fun resetErrors()

    @Query("DELETE FROM items")
    suspend fun clear()
}

@Database(entities = [Item::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): ItemDao

    companion object {
        @Volatile
        private var inst: AppDb? = null

        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "asia.db")
                .build().also { inst = it }
        }
    }
}
