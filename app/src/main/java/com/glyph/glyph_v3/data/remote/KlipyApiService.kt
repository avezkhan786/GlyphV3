package com.glyph.glyph_v3.data.remote

import com.glyph.glyph_v3.data.remote.dto.KlipyCategoriesResponse
import com.glyph.glyph_v3.data.remote.dto.KlipyMediaResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit API service for the Klipy API.
 *
 * Base URL pattern: https://api.klipy.com/api/v1/{secretKey}/
 * Prefixes:   gifs/, stickers/, emojis/
 *
 * Endpoints per media type:
 *   GET {prefix}/categories
 *   GET {prefix}/trending?page=&per_page=
 *   GET {prefix}/search?q=&page=&per_page=
 */
interface KlipyApiService {

    // ── GIF Endpoints ──────────────────────────────────────────────────

    @GET("gifs/categories")
    suspend fun getGifCategories(): Response<KlipyCategoriesResponse>

    @GET("gifs/trending")
    suspend fun getTrendingGifs(
        @Query("page") page: Int = 0,
        @Query("per_page") perPage: Int = 30
    ): Response<KlipyMediaResponse>

    @GET("gifs/search")
    suspend fun searchGifs(
        @Query("q") query: String,
        @Query("page") page: Int = 0,
        @Query("per_page") perPage: Int = 30
    ): Response<KlipyMediaResponse>

    // ── Sticker Endpoints ──────────────────────────────────────────────

    @GET("stickers/categories")
    suspend fun getStickerCategories(): Response<KlipyCategoriesResponse>

    @GET("stickers/trending")
    suspend fun getTrendingStickers(
        @Query("page") page: Int = 0,
        @Query("per_page") perPage: Int = 30
    ): Response<KlipyMediaResponse>

    @GET("stickers/search")
    suspend fun searchStickers(
        @Query("q") query: String,
        @Query("page") page: Int = 0,
        @Query("per_page") perPage: Int = 30
    ): Response<KlipyMediaResponse>

    // ── AI Emoji Endpoints ─────────────────────────────────────────────

    @GET("emojis/categories")
    suspend fun getEmojiCategories(): Response<KlipyCategoriesResponse>

    @GET("emojis/trending")
    suspend fun getTrendingEmojis(
        @Query("page") page: Int = 0,
        @Query("per_page") perPage: Int = 30
    ): Response<KlipyMediaResponse>

    @GET("emojis/search")
    suspend fun searchEmojis(
        @Query("q") query: String,
        @Query("page") page: Int = 0,
        @Query("per_page") perPage: Int = 30
    ): Response<KlipyMediaResponse>

    // ── Meme (Clips) Endpoints ─────────────────────────────────────────

    @GET("clips/categories")
    suspend fun getMemeCategories(): Response<KlipyCategoriesResponse>

    @GET("clips/trending")
    suspend fun getTrendingMemes(
        @Query("page") page: Int = 0,
        @Query("per_page") perPage: Int = 30
    ): Response<KlipyMediaResponse>

    @GET("clips/search")
    suspend fun searchMemes(
        @Query("q") query: String,
        @Query("page") page: Int = 0,
        @Query("per_page") perPage: Int = 30
    ): Response<KlipyMediaResponse>
}
