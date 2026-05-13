package com.telegram.vod // Usa il package allineato al tuo sorgente

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLDecoder
import java.net.URLEncoder

class TelegramChannelProvider : MainAPI() {
    override var mainUrl = "https://t.me/s/archiviocinemaitaliano"
    override var name = "Archivio Cinema Italiano"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    // Autenticazione nativa e pulita tramite Header HTTP
    private val tmdbApiKey = BuildConfig.TMDB_API
    private val authHeaders = mapOf("Authorization" to "Bearer $tmdbApiKey")

    private val databaseUrl = "https://gist.githubusercontent.com/ffranckj/d73933a36991f0ff223efa048937fdf1/raw/0cfb5ddca521179de3ba7e859e3099d81b6488d2/catalogo.json"
    private var linkDatabase: Map<String, String>? = null
    private val defaultCover = "https://placehold.co/500x750/222222/FFFFFF/png?text=Locandina+Non+Disponibile"

    private data class TmdbSearchResp(@JsonProperty("results") val results: List<TmdbMovie>?)
    private data class TmdbMovie(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("backdrop_path") val backdropPath: String?
    )

    private data class TelegramTarget(val streamUrl: String, val poster: String, val banner: String)

    private fun getImageUrl(path: String?, isBanner: Boolean = false): String {
        if (path.isNullOrBlank()) return defaultCover
        val size = if (isBanner) "w1280" else "w500"
        return "https://image.tmdb.org/t/p/$size$path"
    }

    private suspend fun getDatabase(): Map<String, String> {
        if (linkDatabase == null) {
            try {
                val jsonText = app.get(databaseUrl).text
                linkDatabase = parseJson<Map<String, String>>(jsonText).mapKeys { it.key.trim() }
            } catch (e: Exception) {
                linkDatabase = emptyMap()
            }
        }
        return linkDatabase ?: emptyMap()
    }

    private suspend fun fetchTmdbGraphics(title: String): Pair<String, String> {
        if (tmdbApiKey.isBlank()) return Pair(defaultCover, defaultCover)
        return try {
            val cleanTitle = title.replace(Regex("(?i)(film|streaming|ita|hd|sub|download|\\[.*?\\]|\\(.*?\\)|\\d{4})"), "").trim()
            if (cleanTitle.isEmpty()) return Pair(defaultCover, defaultCover)

            val query = URLEncoder.encode(cleanTitle, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/movie?query=$query&language=it-IT"

            val response = app.get(url, headers = authHeaders).text
            val firstResult = tryParseJson<TmdbSearchResp>(response)?.results?.firstOrNull()

            val poster = getImageUrl(firstResult?.posterPath, isBanner = false)
            val banner = getImageUrl(firstResult?.backdropPath ?: firstResult?.posterPath, isBanner = true)
            Pair(poster, banner)
        } catch (e: Exception) {
            Pair(defaultCover, defaultCover)
        }
    }

    private fun extractPostId(url: String): String = url.substringBefore("?").substringAfterLast("/").trim()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val nodes = document.select(".tgme_widget_message")
        val db = getDatabase()
        val movies = mutableListOf<SearchResponse>()

        for (node in nodes) {
            val textNode = node.selectFirst(".tgme_widget_message_text") ?: continue
            val baseHref = node.selectFirst(".tgme_widget_message_date")?.attr("href") ?: continue
            val postId = extractPostId(baseHref)

            val streamLink = db[postId]
            if (streamLink != null) {
                val rawTitle = textNode.text().substringBefore("\n").trim()
                val (poster, banner) = fetchTmdbGraphics(rawTitle)
                val targetData = TelegramTarget(streamLink, poster, banner).toJson()

                movies.add(newMovieSearchResponse(rawTitle, targetData, TvType.Movie) {
                    this.posterUrl = poster
                })
            }
        }
        if (movies.isEmpty()) return null
        return newHomePageResponse("Archivio Cinema", movies.reversed())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val db = getDatabase()
        val searchResults = mutableListOf<SearchResponse>()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("https://t.me/s/archiviocinemaitaliano?q=$encodedQuery").document

        for (node in document.select(".tgme_widget_message")) {
            val textNode = node.selectFirst(".tgme_widget_message_text") ?: continue
            val baseHref = node.selectFirst(".tgme_widget_message_date")?.attr("href") ?: continue
            val streamLink = db[extractPostId(baseHref)]

            if (streamLink != null) {
                val rawTitle = textNode.text().substringBefore("\n").trim()
                val (poster, banner) = fetchTmdbGraphics(rawTitle)
                val targetData = TelegramTarget(streamLink, poster, banner).toJson()

                searchResults.add(newMovieSearchResponse(rawTitle, targetData, TvType.Movie) {
                    this.posterUrl = poster
                })
            }
        }
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val target = tryParseJson<TelegramTarget>(url) ?: return null

        return newMovieLoadResponse("Film in Riproduzione", url, TvType.Movie, target.streamUrl) {
            this.posterUrl = target.poster
            this.backgroundPosterUrl = target.banner
            this.plot = "Streaming HD autorizzato dal database locale."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Implementazione nativa e testata di newExtractorLink
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "Streaming Diretto HD",
                url = data,
                type = ExtractorLinkType.VIDEO,
                quality = Qualities.P1080.value,
                headers = mapOf("Referer" to "https://t.me/")
            )
        )
        return true
    }
}