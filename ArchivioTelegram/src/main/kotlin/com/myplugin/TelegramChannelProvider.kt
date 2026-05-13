package com.telegram.vod

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLDecoder
import java.net.URLEncoder

class TelegramChannelProvider : MainAPI() {
    override var mainUrl = "https://t.me/s/archiviocinemaitaliano"
    override var name = "Archivio Cinema Italiano"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    // Chiave TMDB iniettata in sicurezza in fase di build
    private val tmdbApiKey = BuildConfig.TMDB_API 

    private val databaseUrl = "https://gist.githubusercontent.com/ffranckj/d73933a36991f0ff223efa048937fdf1/raw/catalogo.json"
    private var linkDatabase: Map<String, String>? = null
    
    // Immagine di fallback se il film non ha poster su TMDB
    private val defaultCover = "https://image.tmdb.org/t/p/w500/8Ph696ih9o99X9v76Y99S97799.jpg"

    // Struttura per memorizzare sia il poster che il banner di sfondo da TMDB
    private data class TmdbArt(val poster: String, val background: String)

    // Scarica e mappa in cache il catalogo JSON remoto
    private suspend fun getDatabase(): Map<String, String> {
        if (linkDatabase == null) {
            try {
                val jsonText = app.get(databaseUrl).text
                val rawMap = AppUtils.parseJson<Map<String, String>>(jsonText)
                linkDatabase = rawMap.mapKeys { it.key.trim() }
            } catch (e: Exception) {
                linkDatabase = emptyMap()
            }
        }
        return linkDatabase ?: emptyMap()
    }

    // Interroga TMDB per ottenere Grafiche Ufficiali (Poster + Sfondo)
    private suspend fun fetchTmdbArt(title: String): TmdbArt {
        if (tmdbApiKey.isBlank()) return TmdbArt(defaultCover, defaultCover)

        return try {
            // Pulisce il titolo da tag Telegram per massimizzare l'accuratezza di TMDB
            val cleanTitle = title.replace(Regex("(?i)(film|streaming|ita|hd|sub|download|\\[.*?\\]|\\(.*?\\))"), "").trim()
            val query = URLEncoder.encode(cleanTitle, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/movie?api_key=$tmdbApiKey&query=$query&language=it-IT"
            
            val response = app.get(url).text
            val json = AppUtils.parseJson<TmdbSearchResponse>(response)
            val firstResult = json.results?.firstOrNull()
            
            val poster = firstResult?.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" } ?: defaultCover
            val banner = firstResult?.backdrop_path?.let { "https://image.tmdb.org/t/p/w1280$it" } ?: poster
            
            TmdbArt(poster, banner)
        } catch (e: Exception) {
            TmdbArt(defaultCover, defaultCover)
        }
    }

    // Estrae l'ID univoco del post di Telegram da un URL
    private fun extractPostId(url: String): String {
        return url.substringBefore("?").substringAfterLast("/").trim()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val nodes = document.select(".tgme_widget_message")
        val db = getDatabase()
        val movies = mutableListOf<SearchResponse>()
        
        for (node in nodes) {
            val textNode = node.selectFirst(".tgme_widget_message_text") ?: continue
            val baseHref = node.selectFirst(".tgme_widget_message_date")?.attr("href") ?: continue
            val postId = extractPostId(baseHref)
            
            // REGOLA 1: Mostra in Home SOLO i film che esistono effettivamente nel catalogo.json
            if (db.containsKey(postId)) {
                val rawTitle = textNode.text().substringBefore("\n").trim()
                
                // DELEGA TOTALE A TMDB: Scarica le grafiche ufficiali
                val art = fetchTmdbArt(rawTitle)
                
                // Passiamo le grafiche via URL per farle ritrovare alla funzione load()
                val encodedPoster = URLEncoder.encode(art.poster, "UTF-8")
                val encodedBanner = URLEncoder.encode(art.background, "UTF-8")
                val targetUrl = "$baseHref?poster=$encodedPoster&banner=$encodedBanner"
                
                movies.add(newMovieSearchResponse(rawTitle, targetUrl, TvType.Movie) {
                    this.posterUrl = art.poster
                })
            }
        }
        if (movies.isEmpty()) return null
        return newHomePageResponse("Archivio Cinema", movies.reversed())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val db = getDatabase()
        val searchResults = mutableListOf<SearchResponse>()
        
        // REGOLA 2: Ricerca effettuata interamente scorrendo i post storici di Telegram,
        // ma filtrando rigorosamente SOLO quelli presenti nel DB e che matchano la query.
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://t.me/s/archiviocinemaitaliano?q=$encodedQuery"
        val document = app.get(searchUrl).document
        val nodes = document.select(".tgme_widget_message")

        for (node in nodes) {
            val textNode = node.selectFirst(".tgme_widget_message_text") ?: continue
            val baseHref = node.selectFirst(".tgme_widget_message_date")?.attr("href") ?: continue
            val postId = extractPostId(baseHref)

            // Il post deve essere nel database locale per garantire lo streaming
            if (db.containsKey(postId)) {
                val rawTitle = textNode.text().substringBefore("\n").trim()
                
                val art = fetchTmdbArt(rawTitle)
                val encodedPoster = URLEncoder.encode(art.poster, "UTF-8")
                val encodedBanner = URLEncoder.encode(art.background, "UTF-8")
                val targetUrl = "$baseHref?poster=$encodedPoster&banner=$encodedBanner"

                searchResults.add(newMovieSearchResponse(rawTitle, targetUrl, TvType.Movie) {
                    this.posterUrl = art.poster
                })
            }
        }
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url.substringBefore("?")
        
        // Recupera le grafiche TMDB iniettate nei parametri durante la ricerca/home
        val posterParam = if (url.contains("poster=")) {
            URLDecoder.decode(url.substringAfter("poster=").substringBefore("&"), "UTF-8")
        } else defaultCover

        val bannerParam = if (url.contains("banner=")) {
            URLDecoder.decode(url.substringAfter("banner="), "UTF-8")
        } else posterParam

        val document = app.get(cleanUrl).document
        val rawText = document.selectFirst(".tgme_widget_message_text")?.text() ?: "Nessuna trama disponibile."
        val title = rawText.substringBefore("\n").trim()
        
        return newMovieLoadResponse(title, url, TvType.Movie, cleanUrl) {
            this.plot = rawText
            this.posterUrl = posterParam
            this.backgroundPosterUrl = bannerParam // Applica il banner largo in cima alla scheda
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val db = getDatabase()
        val postId = extractPostId(data)
        
        val finalUrl = db[postId] ?: return false

        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = "Streaming TMDB/Gist",
                url = finalUrl,
                referer = "https://t.me/",
                quality = Qualities.P1080.value,
                type = ExtractorLinkType.VIDEO
            )
        )
        return true
    }

    // Classi dati di supporto per la decodifica JSON di TMDB
    private data class TmdbSearchResponse(val results: List<TmdbMovie>?)
    private data class TmdbMovie(val poster_path: String?, val backdrop_path: String?)
}
