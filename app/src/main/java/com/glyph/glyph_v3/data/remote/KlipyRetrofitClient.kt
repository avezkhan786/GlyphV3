package com.glyph.glyph_v3.data.remote

import com.glyph.glyph_v3.BuildConfig
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Singleton Retrofit client for the Klipy API.
 *
 * Base URL: https://api.klipy.com/api/v1/{KLIPY_API_KEY}/
 * The API key is read from BuildConfig (set in build.gradle.kts) so it's
 * never hardcoded in the UI layer.
 */
object KlipyRetrofitClient {

    private const val API_VERSION = "v1"

    private val baseUrl: String by lazy {
        "${BuildConfig.KLIPY_BASE_URL}/api/$API_VERSION/${BuildConfig.KLIPY_API_KEY}/"
    }

    private val gson by lazy {
        GsonBuilder().create()
    }

    private val okHttpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)

        // Add logging only in debug builds
        if (BuildConfig.DEBUG) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            builder.addInterceptor(logging)
        }

        builder.build()
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    val service: KlipyApiService by lazy {
        retrofit.create(KlipyApiService::class.java)
    }
}
