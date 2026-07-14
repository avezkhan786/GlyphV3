package com.glyph.glyph_v3.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.glyph.glyph_v3.data.local.dao.AiMessageDao
import com.glyph.glyph_v3.data.local.dao.CallLogDao
import com.glyph.glyph_v3.data.local.dao.ChatDao
import com.glyph.glyph_v3.data.local.dao.DeletedMessageDao
import com.glyph.glyph_v3.data.local.dao.MessageDao
import com.glyph.glyph_v3.data.local.dao.StatusCacheDao
import com.glyph.glyph_v3.data.local.dao.TranslationCacheDao
import com.glyph.glyph_v3.data.local.entity.AiMessage
import com.glyph.glyph_v3.data.local.entity.CachedStatus
import com.glyph.glyph_v3.data.local.entity.LocalCallLog
import com.glyph.glyph_v3.data.local.entity.LocalChat
import com.glyph.glyph_v3.data.local.entity.LocalDeletedMessage
import com.glyph.glyph_v3.data.local.entity.LocalMessage
import com.glyph.glyph_v3.data.local.entity.TranslationCache

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [LocalMessage::class, LocalChat::class, LocalDeletedMessage::class, TranslationCache::class, AiMessage::class, CachedStatus::class, LocalCallLog::class], version = 37, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun chatDao(): ChatDao
    abstract fun deletedMessageDao(): DeletedMessageDao
    abstract fun translationCacheDao(): TranslationCacheDao
    abstract fun aiMessageDao(): AiMessageDao
    abstract fun statusCacheDao(): StatusCacheDao
    abstract fun callLogDao(): CallLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Clear translation cache to remove corrupted audio references
                database.execSQL("DELETE FROM translation_cache")
            }
        }

        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN documentCaption TEXT")
            }
        }

        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN pinnedUntil INTEGER")
            }
        }

        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN linkPreviewTitle TEXT")
                database.execSQL("ALTER TABLE messages ADD COLUMN linkPreviewDomain TEXT")
            }
        }

        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN linkPreviewDescription TEXT")
                database.execSQL("ALTER TABLE messages ADD COLUMN linkPreviewSiteName TEXT")
            }
        }

        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE INDEX IF NOT EXISTS index_messages_chatId_timestamp ON messages(chatId, timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_messages_chatId_isIncoming_timestamp ON messages(chatId, isIncoming, timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_messages_chatId_status ON messages(chatId, status)")
            }
        }

        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS cached_statuses (
                        statusId TEXT NOT NULL PRIMARY KEY,
                        userId TEXT NOT NULL,
                        type TEXT NOT NULL,
                        remoteUrl TEXT NOT NULL,
                        localPath TEXT NOT NULL,
                        localThumbnailPath TEXT NOT NULL DEFAULT '',
                        expiresAt INTEGER NOT NULL,
                        cachedAt INTEGER NOT NULL,
                        fileSize INTEGER NOT NULL DEFAULT 0
                    )"""
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_cached_statuses_expiresAt ON cached_statuses(expiresAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_cached_statuses_userId ON cached_statuses(userId)")
            }
        }

        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN statusId TEXT")
                database.execSQL("ALTER TABLE messages ADD COLUMN statusOwnerId TEXT")
                database.execSQL("ALTER TABLE messages ADD COLUMN statusThumbnailUrl TEXT")
                database.execSQL("ALTER TABLE messages ADD COLUMN statusType TEXT")
                database.execSQL("ALTER TABLE messages ADD COLUMN statusText TEXT")
                database.execSQL("ALTER TABLE messages ADD COLUMN statusBgColor INTEGER")
            }
        }

        private val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN serverTimestamp INTEGER")
            }
        }

        private val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN replyPreviewUrl TEXT")
            }
        }

        private val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS call_logs (
                        callId TEXT NOT NULL PRIMARY KEY,
                        callerId TEXT NOT NULL,
                        receiverId TEXT NOT NULL,
                        callerName TEXT NOT NULL,
                        callerAvatar TEXT NOT NULL,
                        receiverName TEXT NOT NULL,
                        receiverAvatar TEXT NOT NULL,
                        type TEXT NOT NULL,
                        status TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        answeredAt INTEGER NOT NULL,
                        endedAt INTEGER NOT NULL
                    )"""
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_call_logs_callerId ON call_logs(callerId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_call_logs_receiverId ON call_logs(receiverId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_call_logs_createdAt ON call_logs(createdAt)")
            }
        }

        private val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS call_logs_new (
                        callId TEXT NOT NULL PRIMARY KEY,
                        callerId TEXT NOT NULL,
                        receiverId TEXT NOT NULL,
                        callerName TEXT NOT NULL,
                        callerAvatar TEXT NOT NULL,
                        receiverName TEXT NOT NULL,
                        receiverAvatar TEXT NOT NULL,
                        type TEXT NOT NULL,
                        status TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        answeredAt INTEGER NOT NULL,
                        endedAt INTEGER NOT NULL
                    )"""
                )
                database.execSQL(
                    """INSERT OR REPLACE INTO call_logs_new (
                        callId,
                        callerId,
                        receiverId,
                        callerName,
                        callerAvatar,
                        receiverName,
                        receiverAvatar,
                        type,
                        status,
                        createdAt,
                        answeredAt,
                        endedAt
                    )
                    SELECT
                        callId,
                        callerId,
                        receiverId,
                        callerName,
                        callerAvatar,
                        receiverName,
                        receiverAvatar,
                        type,
                        status,
                        createdAt,
                        answeredAt,
                        endedAt
                    FROM call_logs"""
                )
                database.execSQL("DROP TABLE IF EXISTS call_logs")
                database.execSQL("ALTER TABLE call_logs_new RENAME TO call_logs")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_call_logs_callerId ON call_logs(callerId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_call_logs_receiverId ON call_logs(receiverId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_call_logs_createdAt ON call_logs(createdAt)")
            }
        }

        private val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // WhatsApp-style emoji reactions: JSON-encoded {userId: emoji} map.
                database.execSQL("ALTER TABLE messages ADD COLUMN reactionsJson TEXT")
            }
        }

        private val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN isForwarded INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Store peer phone numbers in call logs so device contact names can be resolved
        // synchronously without a network round-trip to Firestore.
        private val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE call_logs ADD COLUMN callerPhone TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE call_logs ADD COLUMN receiverPhone TEXT NOT NULL DEFAULT ''")
            }
        }

        // Add index on lastMessageTimestamp for sorted chat queries.
        // WAL mode is already the default in Room 2.8+ (JournalMode.AUTOMATIC).
        private val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_chats_lastMessageTimestamp ON chats(lastMessageTimestamp)")
            }
        }

        // Group chat support \u2014 strictly additive columns. 1:1 chats keep working with defaults.
        private val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // chats: group metadata
                database.execSQL("ALTER TABLE chats ADD COLUMN isGroup INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN groupName TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE chats ADD COLUMN groupIconUrl TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE chats ADD COLUMN groupDescription TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE chats ADD COLUMN createdBy TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE chats ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN participantsJson TEXT")
                database.execSQL("ALTER TABLE chats ADD COLUMN adminsJson TEXT")
                // messages: per-recipient receipts (groups only; null for 1:1)
                database.execSQL("ALTER TABLE messages ADD COLUMN deliveredToJson TEXT")
                database.execSQL("ALTER TABLE messages ADD COLUMN readByJson TEXT")
            }
        }

        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN isStarred INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE messages ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS ai_messages (
                        id TEXT NOT NULL PRIMARY KEY,
                        role TEXT NOT NULL,
                        text TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        mode TEXT NOT NULL,
                        sourcesJson TEXT,
                        isStreaming INTEGER NOT NULL DEFAULT 0
                    )"""
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_ai_messages_timestamp ON ai_messages(timestamp)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "glyph_database"
                )
                .addMigrations(MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                
                // Clear cached audio files
                try {
                    val audioDir = java.io.File(context.cacheDir, "translation_audio")
                    if (audioDir.exists()) {
                        audioDir.listFiles()?.forEach { it.delete() }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AppDatabase", "Failed to clear audio cache", e)
                }
                
                instance
            }
        }
    }
}
