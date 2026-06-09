package com.glyph.glyph_v3.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.glyph.glyph_v3.data.local.entity.TranslationCache

/**
 * DAO for translation cache operations.
 * All queries run on Dispatchers.IO via the repository layer.
 */
@Dao
interface TranslationCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(cache: TranslationCache)

    @Query("SELECT * FROM translation_cache WHERE messageId = :messageId AND targetLanguage = :targetLanguage LIMIT 1")
    suspend fun get(messageId: String, targetLanguage: String): TranslationCache?

    @Query("SELECT * FROM translation_cache WHERE messageId = :messageId ORDER BY createdAt DESC")
    suspend fun getAllForMessage(messageId: String): List<TranslationCache>

    /**
     * Delete entries older than the given timestamp (for 7-day expiry).
     */
    @Query("DELETE FROM translation_cache WHERE createdAt < :olderThan")
    suspend fun deleteExpired(olderThan: Long): Int

    @Query("DELETE FROM translation_cache WHERE messageId = :messageId")
    suspend fun deleteForMessage(messageId: String): Int

    @Query("SELECT COUNT(*) FROM translation_cache")
    suspend fun count(): Int
}
