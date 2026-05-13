package com.telegram.vod

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLDecoder
import java.net.URLEncoder
// Il package di BuildConfig viene importato in automatico dal tuo modulo
// import com.telegram.vod.BuildConfig 

class TelegramChannelProvider : MainAPI() {
    override var mainUrl = "https://t.me/s/archiviocinemaitaliano"
    override var name = "Archivio Cinema Italiano"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    // ACCESSO SICURO ALLA CHIAVE API DA LOCAL.PROPERTIES
    private val tmdbApiKey = BuildConfig.TMDB_API 

    private val databaseUrl = "https://gist.githubusercontent.com/ffranckj/d73933a36991f0ff223efa048937fdf1/raw/0cfb5ddca521179de3ba7e859e3099d81b6488d2/catalogo.json"
    private var linkDatabase: Map<String, String>? = null

    private val defaultCover = "https://image.tmdb.org/t/p/w500/8Ph696ih9o99X9v76Y99S97799.jpg"

    /* // ESEMPIO: Come usare la chiave TMDB per ottenere un poster dinamico
    private suspend fun fetchTmdbPoster(movieTitle: String): String {
        if (tmdbApiKey.isEmpty()) return defaultCover
        return try {
            val query = URLEncoder.encode(movieTitle, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/movie?api_key=$tmdbApiKey&query=$query"
            // Logica di parsing JSON per estrarre il poster_path...
            defaultCover
        } catch (e: Exception) {
            defaultCover
        }
    }
    */

    private suspend fun getDatabase(): Map<String, String> {
        if (linkDatabase == null) {
            try {
                val jsonText = app.get(databaseUrl).text
                linkDatabase = AppUtils.parseJson<Map<String, String>>(jsonText)
            } catch (e: Exception) {
                linkDatabase = emptyMap()
            }
        }
        return linkDatabase ?: emptyMap()
    }

    private fun extractCleanPoster(styleString: String?): String? {
        if (styleString == null) return null
        var url = ""
        if (styleString.contains("url('")) {
            url = styleString.substringAfter("url('").substringBefore("')")
        } else if (styleString.contains("url(")) {
            url = styleString.substringAfter("url(").substringBefore(")")
        }
        url = url.replace("\"", "").replace("'", "").trim()
        return if (url.startsWith("http")) url else null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val nodes = document.select(".tgme_widget_message")
        val movies = mutableListOf<SearchResponse>()
        
        var currentBannerImg: String? = null
        
        for (node in nodes) {
            val photoNode = node.selectFirst(".tgme_widget_message_photo_image")
            if (photoNode != null) {
                if (currentBannerImg == null) {
                    currentBannerImg = extractCleanPoster(photoNode.attr("style"))
                }
                continue
            }
            
            val textNode = node.selectFirst(".tgme_widget_message_text")
            val hasVideo = node.selectFirst(".tgme_widget_message_video, .tgme_widget_message_video_player") != null
            
            if (textNode != null && hasVideo) {
                val title = textNode.text().substringBefore("\n").trim()
                val baseHref = node.selectFirst(".tgme_widget_message_date")?.attr("href") ?: continue
                
                val finalPoster = currentBannerImg ?: defaultCover
                val encodedPoster = URLEncoder.encode(finalPoster, "UTF-8")
                val targetUrl = "$baseHref?poster=$encodedPoster"
                
                movies.add(newMovieSearchResponse(title, targetUrl, TvType.Movie) {
                    this.posterUrl = finalPoster
                })
                
                currentBannerImg = null
            }
        }
        if (movies.isEmpty()) return null
        return newHomePageResponse("Archivio Cinema", movies.reversed())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val db = getDatabase()
        val searchResults = mutableListOf<SearchResponse>()
        
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://t.me/s/archiviocinemaitaliano?q=$encodedQuery"
        val document = app.get(searchUrl).document
        val nodes = document.select(".tgme_widget_message")

        var currentBannerImg: String? = null

        for (node in nodes) {
            val photoNode = node.selectFirst(".tgme_widget_message_photo_image")
            if (photoNode != null) {
                if (currentBannerImg == null) {
                    currentBannerImg = extractCleanPoster(photoNode.attr("style"))
                }
                continue
            }

            val textNode = node.selectFirst(".tgme_widget_message_text") ?: continue
            val baseHref = node.selectFirst(".tgme_widget_message_date")?.attr("href") ?: continue
            val cleanPostId = baseHref.substringAfterLast("/").substringBefore("?")

            if (db.containsKey(cleanPostId)) {
                val title = textNode.text().substringBefore("\n").trim()
                val finalPoster = currentBannerImg ?: defaultCover
                val encodedPoster = URLEncoder.encode(finalPoster, "UTF-8")
                val targetUrl = "$baseHref?poster=$encodedPoster"

                searchResults.add(newMovieSearchResponse(title, targetUrl, TvType.Movie) {
                    this.posterUrl = finalPoster
                })
                currentBannerImg = null
            }
        }
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url.substringBefore("?")
        val posterParam = if (url.contains("poster=")) {
            URLDecoder.decode(url.substringAfter("poster="), "UTF-8")
        } else {
            defaultCover
        }

        val document = app.get(cleanUrl).document
        val rawText = document.selectFirst(".tgme_widget_message_text")?.text() ?: "Film"
        val title = rawText.substringBefore("\n").trim()
        
        return newMovieLoadResponse(title, url, TvType.Movie, cleanUrl) {
            this.plot = rawText
            this.posterUrl = posterParam
            this.backgroundPosterUrl = posterParam
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val db = getDatabase()
        val cleanData = data.substringBefore("?")
        val postId = cleanData.substringAfterLast("/")
        
        val finalUrl = db[postId] ?: return false

        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = "Streaming Diretto HD",
                url = finalUrl,
                referer = "https://t.me/",
                quality = Qualities.P1080.value,
                type = ExtractorLinkType.VIDEO
            )
        )
        return true
    }
}
