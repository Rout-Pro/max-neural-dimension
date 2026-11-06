package com.max.neuraldimension.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String, // "user" or "assistant"
    val text: String,
    val ts: Long = System.currentTimeMillis(),
    val attachmentName: String? = null,
    val attachmentMime: String? = null,
    /** Path under filesDir holding the original bytes (not kept in Room). */
    val attachmentPath: String? = null
)

@Entity(tableName = "tasks")
data class TaskItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY createdAt DESC")
    fun conversations(): Flow<List<Conversation>>

    @Insert
    suspend fun insertConversation(c: Conversation): Long

    @Update
    suspend fun updateConversation(c: Conversation)

    @Delete
    suspend fun deleteConversation(c: Conversation)

    @Query("DELETE FROM messages WHERE conversationId = :cid")
    suspend fun deleteMessagesFor(cid: Long)

    @Query("DELETE FROM messages WHERE conversationId = :cid AND id > :mid")
    suspend fun deleteMessagesAfter(cid: Long, mid: Long)

    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY ts ASC")
    fun messages(cid: Long): Flow<List<Message>>

    @Insert
    suspend fun insertMessage(m: Message): Long

    @Query("UPDATE messages SET text = :text WHERE id = :id")
    suspend fun updateMessageText(id: Long, text: String)

    @Query("SELECT * FROM messages WHERE text LIKE '%' || :q || '%' ORDER BY ts DESC LIMIT 50")
    suspend fun search(q: String): List<Message>

    @Query("SELECT attachmentPath FROM messages WHERE attachmentPath IS NOT NULL")
    suspend fun allAttachmentPaths(): List<String?>
}

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val triggerAt: Long,
    val workId: String = "",
    val fired: Boolean = false
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun notes(): Flow<List<Note>>

    @Insert
    suspend fun insert(n: Note): Long

    @Delete
    suspend fun delete(n: Note)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY triggerAt ASC")
    fun reminders(): Flow<List<Reminder>>

    @Insert
    suspend fun insert(r: Reminder): Long

    @Update
    suspend fun update(r: Reminder)

    @Delete
    suspend fun delete(r: Reminder)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY done ASC, createdAt DESC")
    fun tasks(): Flow<List<TaskItem>>

    @Insert
    suspend fun insert(t: TaskItem): Long

    @Update
    suspend fun update(t: TaskItem)

    @Delete
    suspend fun delete(t: TaskItem)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN attachmentName TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN attachmentMime TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN attachmentPath TEXT")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, text TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS reminders (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, triggerAt INTEGER NOT NULL, workId TEXT NOT NULL DEFAULT '', fired INTEGER NOT NULL DEFAULT 0)")
    }
}

@Database(entities = [Conversation::class, Message::class, TaskItem::class, Note::class, Reminder::class], version = 3, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun taskDao(): TaskDao
    abstract fun noteDao(): NoteDao
    abstract fun reminderDao(): ReminderDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "max.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }
    }
}
