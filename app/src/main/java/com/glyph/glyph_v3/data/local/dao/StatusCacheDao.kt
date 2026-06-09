package com.glyph.glyph_v3.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.glyph.glyph_v3.data.local.entity.CachedStatus

@Dao
interface StatusCacheDao {

    @Query("SELECT * FROM cached_statuses WHERE statusId = :statusId LIMIT 1")
    suspend fun getByStatusId(statusId: String): CachedStatus?

    @Query("SELECT * FROM cached_statuses WHERE statusId IN (:statusIds)")
    suspend fun getByStatusIds(statusIds: List<String>): List<CachedStatus>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cachedStatus: CachedStatus)

    /** Return all entries whose expiresAt is in the past */
    @Query("SELECT * FROM cached_statuses WHERE expiresAt <= :now")
    suspend fun getExpired(now: Long): List<CachedStatus>

    @Query("DELETE FROM cached_statuses WHERE statusId = :statusId")
    suspend fun deleteByStatusId(statusId: String): Int

    @Query("DELETE FROM cached_statuses WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: Long): Int

    /** Total bytes of all cached files */
    @Query("SELECT COALESCE(SUM(fileSize), 0) FROM cached_statuses")
    suspend fun totalCacheSize(): Long

    @Query("SELECT * FROM cached_statuses ORDER BY cachedAt ASC LIMIT :limit")
    suspend fun getOldest(limit: Int): List<CachedStatus>

    /** Delete oldest entries until cache is under the given byte limit */
    @Query("DELETE FROM cached_statuses WHERE statusId IN (SELECT statusId FROM cached_statuses ORDER BY cachedAt ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int): Int

    @Query("SELECT COUNT(*) FROM cached_statuses")
    suspend fun count(): Int

    /** Get all cached status IDs for a given user */
    @Query("SELECT statusId FROM cached_statuses WHERE userId = :userId")
    suspend fun getStatusIdsForUser(userId: String): List<String>

    @Query("SELECT * FROM cached_statuses")
    suspend fun getAll(): List<CachedStatus>

    @Query("DELETE FROM cached_statuses WHERE statusId IN (:statusIds)")
    suspend fun deleteByStatusIds(statusIds: List<String>): Int
}
