package com.glyph.glyph_v3.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Room entity to cache translation + TTS audio results.
 * Primary key: composite of messageId + targetLanguage.
 * Expires after 7 days (enforced by repository).
 */
@Entity(
    tableName = "translation_cache",
    primaryKeys = ["messageId", "targetLanguage"],
    indices = [Index(value = ["createdAt"])]
)
data class TranslationCache(
    val messageId: String,
    val targetLanguage: String,
    val originalText: String,
    val translatedText: String,
    val audioFilePath: String? = null,
    val audioUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
