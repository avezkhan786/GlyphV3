package com.glyph.glyph_v3.ui.share

import android.util.Log
import android.util.Patterns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class LinkPreviewData(
    val url: String,
    val title: String,
    val domain: String,
    val thumbnailUrl: String? = null,
    val description: String? = null,
    val siteName: String? = null
)

object LinkPreviewResolver {
    private const val TAG = "LinkPreviewResolver"
    
    // Use a realistic WhatsApp-like User-Agent for better compatibility
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36 WhatsApp/2.23.24.76"
    
    private const val FALLBACK_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val USER_AGENTS = listOf(
        // Social networks / Messengers (sites usually serve rich meta tags to these without JS)
        "WhatsApp/2.23.24.76",
        "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
        "Twitterbot/1.0",
        "LinkedInBot/1.0 (Compatible; Mozilla/5.0; Apache-HttpClient/4.3.1)",
        "Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)",

        // Top Search Bots (rarely blocked, always serve full HTML)
        "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
        "Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)",

        // Modern Mobile Browsers
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_1_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Mobile/15E148 Safari/604.1"
    )

    fun extractFirstUrl(text: String?): String? {
        val candidate = text?.trim().orEmpty()
        if (candidate.isEmpty()) return null
        return Patterns.WEB_URL.matcher(candidate)
            .results()
            .map { it.group() }
            .findFirst()
            .orElse(null)
            ?.let(::normalizeUrl)
    }

    fun fallback(url: String): LinkPreviewData {
        val normalized = normalizeUrl(url)
        return LinkPreviewData(
            url = normalized,
            title = domainFromUrl(normalized).replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString()
            },
            domain = domainFromUrl(normalized),
            thumbnailUrl = null
        )
    }

    suspend fun resolve(url: String): LinkPreviewData = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(url)
        
        runCatching {
            when {
                isYouTubeUrl(normalized) -> {
                    resolveYouTube(normalized)
                }
                isInstagramUrl(normalized) || isTwitterUrl(normalized) -> {
                    // These sites often block scrapers, use generic with better handling
                    resolveSocialMedia(normalized)
                }
                else -> {
                    resolveGeneric(normalized)
                }
            }
        }.getOrElse { error ->
            Log.e(TAG, "Failed to resolve link preview: ${error.message}", error)
            fallback(normalized)
        }
    }
    
    private fun resolveSocialMedia(url: String): LinkPreviewData {
        // Social media sites often have good Open Graph tags
        // Try with mobile user agent which is usually less restricted
        return resolveGeneric(url)
    }

    private fun resolveYouTube(url: String): LinkPreviewData {
        try {
            val encoded = URLEncoder.encode(url, StandardCharsets.UTF_8.toString())
            val response = Jsoup.connect("https://www.youtube.com/oembed?url=$encoded&format=json")
                .ignoreContentType(true)
                .userAgent(USER_AGENT)
                .timeout(15_000)
                .execute()
            val json = JSONObject(response.body())
            val title = json.optString("title").ifBlank { fallback(url).title }
            val thumbnailUrl = json.optString("thumbnail_url").takeIf { it.isNotBlank() }
            
            
            return LinkPreviewData(
                url = url,
                title = title,
                domain = domainFromUrl(url),
                thumbnailUrl = thumbnailUrl
            )
        } catch (e: Exception) {
            Log.w(TAG, "YouTube oEmbed failed, falling back to generic: ${e.message}")
            // Fallback to generic resolver if oEmbed fails
            return resolveGeneric(url)
        }
    }

    private fun resolveGeneric(url: String): LinkPreviewData {
        // Try multiple user agents until success
        var lastError: Exception? = null
        
        // WhatsApp/Social UserAgents are the most likely to get clean HTML without JS requirements
        val allAgents = (USER_AGENTS + listOf(USER_AGENT, FALLBACK_USER_AGENT)).distinct()

        for ((attempt, userAgent) in allAgents.withIndex()) {
            try {
                
                val connection = Jsoup.connect(url)
                    .followRedirects(true)
                    .userAgent(userAgent)
                    .referrer("https://www.google.com")
                    .timeout(10_000)
                    .ignoreHttpErrors(true)
                    .maxBodySize(5 * 1024 * 1024)
                
                // Add standard headers to look like a real browser or crawler
                connection.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                connection.header("Accept-Language", "en-US,en;q=0.5")
                connection.header("Cache-Control", "no-cache")
                connection.header("Pragma", "no-cache")
                connection.header("Upgrade-Insecure-Requests", "1")

                val document = connection.get()

                // Check for generic bot protection / JS blocks
                val docText = document.text().lowercase()
                if (docText.contains("javascript is disabled") || 
                    docText.contains("enable cookies") ||
                    docText.contains("checking your browser") ||
                    docText.contains("unsupported browser")) {
                    Log.w(TAG, "Bot detection or JS requirement triggered for UA: $userAgent. Trying next...")
                    continue
                }

                // If document is basically empty or we got a block page, try next UA
                if (document.title().isEmpty() && document.select("meta").isEmpty()) {
                    Log.w(TAG, "Empty document received for agent $userAgent")
                    continue
                }

                // 1. Resolve Title
                val title = firstNonBlank(
                    document.select("meta[property=og:title]").attr("content"),
                    document.select("meta[name=og:title]").attr("content"),
                    document.select("meta[property=twitter:title]").attr("content"),
                    document.select("meta[name=twitter:title]").attr("content"),
                    document.select("meta[name=title]").attr("content"),
                    document.select("meta[property=title]").attr("content"),
                    document.title(),
                    document.select("h1").first()?.text(),
                    document.select("h2").first()?.text()
                ) ?: fallback(url).title

                // 2. Resolve Description
                val descriptionText = firstNonBlank(
                    document.select("meta[property=og:description]").attr("content"),
                    document.select("meta[name=og:description]").attr("content"),
                    document.select("meta[name=description]").attr("content"),
                    document.select("meta[property=twitter:description]").attr("content"),
                    document.select("meta[name=twitter:description]").attr("content")
                )

                // 3. Resolve Site Name
                val siteName = firstNonBlank(
                    document.select("meta[property=og:site_name]").attr("content"),
                    document.select("meta[name=og:site_name]").attr("content"),
                    document.select("meta[name=application-name]").attr("content")
                )

                // 4. Resolve Image
                val image = firstNonBlank(
                    document.select("meta[property=og:image:secure_url]").attr("content"),
                    document.select("meta[property=og:image]").attr("content"),
                    document.select("meta[property=og:image:url]").attr("content"),
                    document.select("meta[name=og:image]").attr("content"),
                    document.select("meta[property=twitter:image]").attr("content"),
                    document.select("meta[name=twitter:image]").attr("content"),
                    document.select("meta[property=twitter:image:src]").attr("content"),
                    document.select("meta[name=twitter:image:src]").attr("content"),
                    document.select("link[rel=image_src]").attr("href"),
                    document.select("link[rel=apple-touch-icon]").attr("href"),
                    document.select("link[rel=shortcut icon]").attr("href"),
                    document.select("link[rel=icon]").attr("href"),
                    // Amazon specific image meta
                    document.select("meta[name=twitter:image:alt]").attr("content") // Sometimes used in place of img
                )

                val resolvedUrl = document.location().ifBlank { url }
                
                
                return LinkPreviewData(
                    url = url,
                    title = title.trim().take(200),
                    domain = domainFromUrl(resolvedUrl),
                    thumbnailUrl = image?.let { resolveRelativeUrl(resolvedUrl, it) }?.take(2000),
                    description = descriptionText?.trim()?.take(300),
                    siteName = siteName?.trim()
                )
            } catch (e: Exception) {
                Log.w(TAG, "Attempt ${attempt + 1} failed: ${e.message}")
                lastError = e
            }
        }
        
        throw lastError ?: Exception("Failed to resolve link preview")
    }

    private fun isYouTubeUrl(url: String): Boolean {
        val domain = domainFromUrl(url).lowercase()
        return domain == "youtube.com" || 
               domain == "youtu.be" ||
               domain == "m.youtube.com" ||
               domain.endsWith(".youtube.com")
    }
    
    private fun isInstagramUrl(url: String): Boolean {
        val domain = domainFromUrl(url).lowercase()
        return domain == "instagram.com" ||
               domain.endsWith(".instagram.com")
    }
    
    private fun isTwitterUrl(url: String): Boolean {
        val domain = domainFromUrl(url).lowercase()
        return domain == "twitter.com" || 
               domain == "x.com" ||
               domain.endsWith(".twitter.com") ||
               domain.endsWith(".x.com")
    }

    private fun normalizeUrl(url: String): String {
        return if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
    }

    private fun domainFromUrl(url: String): String {
        return runCatching {
            val host = java.net.URI(normalizeUrl(url)).host.orEmpty().lowercase(Locale.getDefault())
            host.removePrefix("www.").ifBlank { "website" }
        }.getOrDefault("website")
    }

    private fun resolveRelativeUrl(baseUrl: String, value: String): String? {
        if (value.isBlank()) return null
        
        return runCatching {
            // If it's already an absolute URL, return it
            if (value.startsWith("http://") || value.startsWith("https://")) {
                return value
            }
            
            // Handle protocol-relative URLs
            if (value.startsWith("//")) {
                val base = java.net.URI(normalizeUrl(baseUrl))
                return "${base.scheme}:$value"
            }
            
            // Resolve relative URL
            java.net.URI(normalizeUrl(baseUrl)).resolve(value).toString()
        }.getOrElse { error ->
            Log.w(TAG, "Failed to resolve relative URL: $value from $baseUrl - ${error.message}")
            null
        }
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            ?.takeIf { it.length > 1 } // Ignore single character values
    }
}