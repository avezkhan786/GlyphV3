package com.glyph.glyph_v3.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository that wraps [KlipyApiService] calls, maps DTOs to domain models,
 * and provides simple in-memory caching.
 */
class KlipyRepository(
    private val api: KlipyApiService = KlipyRetrofitClient.service
) {
    companion object {
        private const val TAG = "KlipyRepo"
    }

    // ── In-memory cache ──────────────────────────────────────────────

    private var cachedTrendingGifs: List<KlipyMediaItem>? = null
    private var cachedTrendingStickers: List<KlipyMediaItem>? = null
    private var cachedTrendingEmojis: List<KlipyMediaItem>? = null
    private var cachedTrendingMemes: List<KlipyMediaItem>? = null

    private var gifPage = 1
    private var stickerPage = 1
    private var emojiPage = 1
    private var memePage = 1

    private var gifHasMore = true
    private var stickerHasMore = true
    private var emojiHasMore = true
    private var memeHasMore = true

    // ── GIFs ─────────────────────────────────────────────────────────

    suspend fun getTrendingGifs(forceRefresh: Boolean = false): Result<List<KlipyMediaItem>> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh && cachedTrendingGifs != null) {
                return@withContext Result.success(cachedTrendingGifs!!)
            }
            gifPage = 1
            gifHasMore = true
            runCatching {
                val response = api.getTrendingGifs(page = gifPage, perPage = 50)
                if (response.isSuccessful) {
                    val items = response.body()?.data?.items
                        ?.mapNotNull { KlipyMediaItem.fromDto(it) } ?: emptyList()
                    gifHasMore = response.body()?.data?.hasNext == true
                    cachedTrendingGifs = items
                    items
                } else {
                    throw Exception("HTTP ${response.code()}: ${response.message()}")
                }
            }.onFailure { Log.e(TAG, "getTrendingGifs failed", it) }
        }

    suspend fun loadMoreTrendingGifs(): Result<List<KlipyMediaItem>> =
        withContext(Dispatchers.IO) {
            if (!gifHasMore) return@withContext Result.success(cachedTrendingGifs ?: emptyList())
            gifPage++
            runCatching {
                val response = api.getTrendingGifs(page = gifPage, perPage = 50)
                if (response.isSuccessful) {
                    val newItems = response.body()?.data?.items
                        ?.mapNotNull { KlipyMediaItem.fromDto(it) } ?: emptyList()
                    gifHasMore = response.body()?.data?.hasNext == true
                    val merged = (cachedTrendingGifs ?: emptyList()) + newItems
                    cachedTrendingGifs = merged
                    merged
                } else throw Exception("HTTP ${response.code()}")
            }.onFailure { Log.e(TAG, "loadMoreTrendingGifs failed", it) }
        }

    suspend fun searchGifs(query: String, page: Int = 0): Result<List<KlipyMediaItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = api.searchGifs(query = query, page = page, perPage = 50)
                if (response.isSuccessful) {
                    response.body()?.data?.items
                        ?.mapNotNull { KlipyMediaItem.fromDto(it) } ?: emptyList()
                } else throw Exception("HTTP ${response.code()}")
            }.onFailure { Log.e(TAG, "searchGifs failed", it) }
        }

    // ── Stickers ─────────────────────────────────────────────────────

    suspend fun getTrendingStickers(forceRefresh: Boolean = false): Result<List<KlipyMediaItem>> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh && cachedTrendingStickers != null) {
                return@withContext Result.success(cachedTrendingStickers!!)
            }
            stickerPage = 1
            stickerHasMore = true
            runCatching {
                val response = api.getTrendingStickers(page = stickerPage, perPage = 50)
                if (response.isSuccessful) {
                    val items = response.body()?.data?.items
                        ?.mapNotNull { KlipyMediaItem.fromDto(it) } ?: emptyList()
                    stickerHasMore = response.body()?.data?.hasNext == true
                    cachedTrendingStickers = items
                    items
                } else throw Exception("HTTP ${response.code()}")
            }.onFailure { Log.e(TAG, "getTrendingStickers failed", it) }
        }

    suspend fun loadMoreTrendingStickers(): Result<List<KlipyMediaItem>> =
        withContext(Dispatchers.IO) {
            if (!stickerHasMore) return@withContext Result.success(cachedTrendingStickers ?: emptyList())
            stickerPage++
            runCatching {
                val response = api.getTrendingStickers(page = stickerPage, perPage = 50)
                if (response.isSuccessful) {
                    val newItems = response.body()?.data?.items
                        ?.mapNotNull { KlipyMediaItem.fromDto(it) } ?: emptyList()
                    stickerHasMore = response.body()?.data?.hasNext == true
                    val merged = (cachedTrendingStickers ?: emptyList()) + newItems
                    cachedTrendingStickers = merged
                    merged
                } else throw Exception("HTTP ${response.code()}")
            }.onFailure { Log.e(TAG, "loadMoreTrendingStickers failed", it) }
        }

    suspend fun searchStickers(query: String, page: Int = 0): Result<List<KlipyMediaItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = api.searchStickers(query = query, page = page, perPage = 50)
                if (response.isSuccessful) {
                    response.body()?.data?.items
                        ?.mapNotNull { KlipyMediaItem.fromDto(it) } ?: emptyList()
                } else throw Exception("HTTP ${response.code()}")
            }.onFailure { Log.e(TAG, "searchStickers failed", it) }
        }

    // ── AI Emojis ────────────────────────────────────────────────────

    suspend fun getTrendingEmojis(forceRefresh: Boolean = false): Result<List<KlipyMediaItem>> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh && cachedTrendingEmojis != null) {
                return@withContext Result.success(cachedTrendingEmojis!!)
            }
            emojiPage = 1
            emojiHasMore = true
            runCatching {
                val response = api.getTrendingEmojis(page = emojiPage, perPage = 50)
                if (response.isSuccessful) {
                    val items = response.body()?.data?.items
                        ?.mapNotNull { KlipyMediaItem.fromDto(it) } ?: emptyList()
                    emojiHasMore = response.body()?.data?.hasNext == true
                    cachedTrendingEmojis = items
                    items
                } else throw Exception("HTTP ${response.code()}")
            }.onFailure { Log.e(TAG, "getTrendingEmojis failed", it) }
        }

    suspend fun loadMoreTrendingEmojis(): Result<List<KlipyMediaItem>> =
        withContext(Dispatchers.IO) {
            if (!emojiHasMore) return@withContext Result.success(cachedTrendingEmojis ?: emptyList())
            emojiPage++
            runCatching {
                val response = api.getTrendingEmojis(page = emojiPage, perPage = 50)
                if (response.isSuccessful) {
                    val newItems = response.body()?.data?.items
                        ?.mapNotNull { KlipyMediaItem.fromDto(it) } ?: emptyList()
                    emojiHasMore = response.body()?.data?.hasNext == true
                    val merged = (cachedTrendingEmojis ?: emptyList()) + newItems
                    cachedTrendingEmojis = merged
                    merged
                } else throw Exception("HTTP ${response.code()}")
            }.onFailure { Log.e(TAG, "loadMoreTrendingEmojis failed", it) }
        }

    suspend fun searchEmojis(query: String, page: Int = 0): Result<List<KlipyMediaItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = api.searchEmojis(query = query, page = page, perPage = 50)
                if (response.isSuccessful) {
                    response.body()?.data?.items
                        ?.mapNotNull { KlipyMediaItem.fromDto(it) } ?: emptyList()
                } else throw Exception("HTTP ${response.code()}")
            }.onFailure { Log.e(TAG, "searchEmojis failed", it) }
        }

    // ── Memes ────────────────────────────────────────────────────────

    suspend fun getTrendingMemes(forceRefresh: Boolean = false): Result<List<KlipyMediaItem>> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh && cachedTrendingMemes != null) {
                return@withContext Result.success(cachedTrendingMemes!!)
            }
            memePage = 1
            memeHasMore = true
            runCatching {
                val response = api.getTrendingMemes(page = memePage, perPage = 50)
                if (response.isSuccessful) {
                    val items = response.body()?.data?.items
                        ?.mapNotNull { KlipyMediaItem.fromDto(it) } ?: emptyList()
                    memeHasMore = response.body()?.data?.hasNext == true
                    cachedTrendingMemes = items
                    items
                } else throw Exception("HTTP ${response.code()}")
            }.onFailure { Log.e(TAG, "getTrendingMemes failed", it) }
        }

    suspend fun loadMoreTrendingMemes(): Result<List<KlipyMediaItem>> =
        withContext(Dispatchers.IO) {
            if (!memeHasMore) return@withContext Result.success(cachedTrendingMemes ?: emptyList())
            memePage++
            runCatching {
                val response = api.getTrendingMemes(page = memePage, perPage = 50)
                if (response.isSuccessful) {
                    val newItems = response.body()?.data?.items
                        ?.mapNotNull { KlipyMediaItem.fromDto(it) } ?: emptyList()
                    memeHasMore = response.body()?.data?.hasNext == true
                    val merged = (cachedTrendingMemes ?: emptyList()) + newItems
                    cachedTrendingMemes = merged
                    merged
                } else throw Exception("HTTP ${response.code()}")
            }.onFailure { Log.e(TAG, "loadMoreTrendingMemes failed", it) }
        }

    suspend fun searchMemes(query: String, page: Int = 0): Result<List<KlipyMediaItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = api.searchMemes(query = query, page = page, perPage = 50)
                if (response.isSuccessful) {
                    response.body()?.data?.items
                        ?.mapNotNull { KlipyMediaItem.fromDto(it) } ?: emptyList()
                } else throw Exception("HTTP ${response.code()}")
            }.onFailure { Log.e(TAG, "searchMemes failed", it) }
        }
}
