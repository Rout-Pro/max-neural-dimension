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
    val ts: Long = System.currentTimeMillis()
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

    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY ts ASC")
    fun messages(cid: Long): Flow<List<Message>>

    @Insert
    suspend fun insertMessage(m: Message): Long

    @Query("UPDATE messages SET text = :text WHERE id = :id")
    suspend fun updateMessageText(id: Long, text: String)

    @Query("SELECT * FROM messages WHERE text LIKE '%' || :q || '%' ORDER BY ts DESC LIMIT 50")
    suspend fun search(q: String): List<Message>
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

@Database(entities = [Conversation::class, Message::class, TaskItem::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun taskDao(): TaskDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "max.db").build().also { instance = it }
        }
    }
}
