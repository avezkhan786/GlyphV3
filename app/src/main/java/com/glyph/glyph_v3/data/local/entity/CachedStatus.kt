package com.glyph.glyph_v3.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for locally cached status media.
 *
 * Stores metadata about a downloaded status file so subsequent views
 * load from disk instead of re-fetching from Firebase Storage.
 */
@Entity(
    tableName = "cached_statuses",
    indices = [
        Index(value = ["expiresAt"]),
        Index(value = ["userId"])
    ]
)
data class CachedStatus(
    /** Same as the Firestore status document ID */
    @PrimaryKey val statusId: String,
    val userId: String,
    /** "TEXT", "IMAGE", or "VIDEO" */
    val type: String,
    /** Original remote URL (Firebase Storage) */
    val remoteUrl: String,
    /** Absolute path to the locally cached file */
    val localPath: String,
    /** Thumbnail local path (for videos) */
    val localThumbnailPath: String = "",
    /** Firestore expiresAt timestamp — used for cleanup */
    val expiresAt: Long,
    /** When the file was downloaded */
    val cachedAt: Long = System.currentTimeMillis(),
    /** File size in bytes */
    val fileSize: Long = 0L
)
