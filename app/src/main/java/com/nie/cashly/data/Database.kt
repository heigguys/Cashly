package com.nie.cashly.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import com.nie.cashly.core.Kind

@Entity(tableName = "entries", indices = [Index(value = ["platform", "orderId"], unique = true)])
data class Entry(
    @PrimaryKey val id: String = UUID.randomUUID().toString(), val amount: Long,
    val kind: Kind, val platform: String, val time: Long, val category: String = "其他",
    val party: String = "", val note: String = "", val orderId: String? = null,
    val timeEstimated: Boolean = false, val refundOf: String? = null,
    val created: Long = System.currentTimeMillis(), val updated: Long = System.currentTimeMillis()
)
@Entity(tableName = "sources", foreignKeys = [ForeignKey(entity = Entry::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE)], indices = [Index("entryId")])
data class Source(@PrimaryKey val key: String, val entryId: String, val label: String)
@Entity(tableName = "events")
data class Event(@PrimaryKey val key: String, val platform: String, val received: Long, val raw: String,
    val pending: Boolean, val evidence: String, val amount: Long? = null, val kind: Kind? = null, val party: String = "", val orderId: String? = null)
@Entity(tableName = "categories")
data class Category(@PrimaryKey val name: String)
@Entity(tableName = "notification_slots")
data class NotificationSlot(@PrimaryKey val key: String, val eventKey: String)
class Converters {
    @TypeConverter fun fromKind(kind: Kind?): String? = kind?.name
    @TypeConverter fun toKind(value: String?): Kind? = value?.let(Kind::valueOf)
}
@Dao
interface LedgerDao {
    @Query("SELECT * FROM notification_slots WHERE `key` = :key") suspend fun slot(key: String): NotificationSlot?
    @Query("SELECT * FROM notification_slots") suspend fun slots(): List<NotificationSlot>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun slot(slot: NotificationSlot)
    @Query("DELETE FROM notification_slots WHERE `key` = :key") suspend fun removeSlot(key: String)
    @Query("DELETE FROM notification_slots") suspend fun clearSlots()
    @Query("SELECT * FROM entries ORDER BY time DESC") fun observeEntries(): Flow<List<Entry>>
    @Query("SELECT * FROM events WHERE pending = 1 ORDER BY received DESC") fun observePending(): Flow<List<Event>>
    @Query("SELECT * FROM categories ORDER BY name") fun observeCategories(): Flow<List<Category>>
    @Query("SELECT * FROM entries") suspend fun entries(): List<Entry>
    @Query("SELECT * FROM sources") suspend fun sources(): List<Source>
    @Query("SELECT * FROM categories") suspend fun categories(): List<Category>
    @Query("SELECT * FROM events") suspend fun events(): List<Event>
    @Query("SELECT * FROM entries WHERE platform = :platform AND orderId = :order LIMIT 1") suspend fun byOrder(platform: String, order: String): Entry?
    @Query("SELECT * FROM sources WHERE `key` = :key") suspend fun source(key: String): Source?
    @Query("SELECT * FROM events WHERE `key` = :key") suspend fun event(key: String): Event?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(entry: Entry)
    @Update suspend fun update(entry: Entry)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun source(source: Source)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun event(event: Event)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun category(category: Category)
    @Query("DELETE FROM entries WHERE id = :id") suspend fun delete(id: String)
    @Query("UPDATE events SET pending = 0, raw = '' WHERE `key` = :key") suspend fun resolve(key: String)
    @Query("UPDATE entries SET category = :target WHERE category = :old") suspend fun migrateCategory(old: String, target: String)
    @Query("DELETE FROM categories WHERE name = :name") suspend fun deleteCategory(name: String)
    @Query("DELETE FROM events WHERE received < :cutoff") suspend fun prune(cutoff: Long)
    @Query("UPDATE events SET raw = ''") suspend fun clearRaw()
    @Query("DELETE FROM sources") suspend fun clearSources()
    @Query("DELETE FROM entries") suspend fun clearEntries()
    @Query("DELETE FROM events") suspend fun clearEvents()
    @Query("DELETE FROM categories") suspend fun clearCategories()
}
@Database(entities = [Entry::class, Source::class, Event::class, Category::class, NotificationSlot::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class CashlyDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao
    companion object {
        @Volatile private var instance: CashlyDatabase? = null
        fun get(context: Context): CashlyDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CashlyDatabase::class.java, "db_cashly").build().also { instance = it }
        }
    }
}
