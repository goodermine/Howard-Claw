package au.howardagent.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val conversationId: String,
    val role: String,
    val content: String,
    val model: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val task: String,
    val result: String,
    val tool: String,
    val success: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val filename: String,
    val downloaded: Boolean = false,
    val active: Boolean = false
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY timestamp ASC")
    fun getMessages(convId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("DELETE FROM messages WHERE conversationId = :convId")
    suspend fun clearConversation(convId: String)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentTasks(limit: Int): Flow<List<TaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity)
}

@Dao
interface ModelDao {
    @Query("SELECT * FROM models WHERE active = 1 LIMIT 1")
    suspend fun getActiveModel(): ModelEntity?

    @Query("SELECT * FROM models")
    fun getAllModels(): Flow<List<ModelEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: ModelEntity)

    @Query("UPDATE models SET active = 0")
    suspend fun deactivateAll()

    @Query("UPDATE models SET active = 1 WHERE id = :id")
    suspend fun activate(id: String)
}

@Database(
    entities = [MessageEntity::class, TaskEntity::class, ModelEntity::class],
    version = 1,
    exportSchema = false
)
abstract class HowardDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun taskDao(): TaskDao
    abstract fun modelDao(): ModelDao

    companion object {
        @Volatile
        private var INSTANCE: HowardDatabase? = null

        fun getInstance(context: Context): HowardDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    HowardDatabase::class.java,
                    "howard_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
