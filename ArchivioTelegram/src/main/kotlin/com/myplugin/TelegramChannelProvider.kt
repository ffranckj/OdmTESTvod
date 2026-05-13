package com.telegram.vod // Mantieni il package originale del tuo progetto

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

    // Immagine di fallback se il post originale non ha una copertina
    private val defaultCover = "https://placehold.co/500x750/222222/FFFFFF/png?text=Locandina+Non+Disponibile"
    
    private val databaseUrl = "https://gist.githubusercontent.com/ffranckj/d73933a36991f0ff223efa048937fdf1/raw/0cfb5ddca521179de3ba7e859e3099d81b6488d2/catalogo.json"
    private var linkDatabase: Map<String, String>? = null

    // Struttura per passare i dati in modo compatto al caricatore del video
    private data class TelegramTarget(val streamUrl: String, val poster: String)

    // Sincronizzazione sicura del catalogo remoto (Gist)
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

    // Estrazione dinamica del poster nativo di Telegram tramite Espressioni Regolari
    private fun extractCleanPoster(styleString: String?): String {
        if (styleString.isNullOrBlank()) return defaultCover
        return try {
            val regex = "url\\(['\"]?(https?://[^)'\"]+)['\"]?\\)".toRegex()
            val match = regex.find(styleString)
            match?.groups?.get(1)?.value ?: defaultCover
        } catch (e: Exception) {
            defaultCover
        }
    }

    private fun extractPostId(url: String): String {
        return url.substringBefore("?").substringAfterLast("/").trim()
    }

    // Calcolo del punteggio di rilevanza per ordinare la ricerca Fuzzy
    private fun calculateRelevance(title: String, query: String): Int {
        val cleanTitle = title.lowercase()
        val cleanQuery = query.lowercase()

        return when {
            cleanTitle == cleanQuery -> 100 // Match Perfetto
            cleanTitle.startsWith(cleanQuery) -> 75 // Inizia con la query
            // La query si trova all'inizio di una qualsiasi parola del titolo
            cleanTitle.split(" ").any { it.startsWith(cleanQuery) } -> 50 
            cleanTitle.contains(cleanQuery) -> 25 // Contiene la query ma in mezzo a una parola
            else -> 0
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val nodes = document.select(".tgme_widget_message")
        val db = getDatabase()
        val movies = mutableListOf<SearchResponse>()
        
        var currentBannerImg: String? = null
        
        for (node in nodes) {
            val photoNode = node.selectFirst(".tgme_widget_message_photo_image")
            if (photoNode != null) {
                if (currentBannerImg == null) {
                    val extracted = extractCleanPoster(photoNode.attr("style"))
                    if (extracted != defaultCover) currentBannerImg = extracted
                }
                continue
            }
            
            val textNode = node.selectFirst(".tgme_widget_message_text")
            val hasVideo = node.selectFirst(".tgme_widget_message_video, .tgme_widget_message_video_player") != null
            
            if (textNode != null && hasVideo) {
                val rawTitle = textNode.text().substringBefore("\n").trim()
                val baseHref = node.selectFirst(".tgme_widget_message_date")?.attr("href") ?: continue
                val postId = extractPostId(baseHref)
                
                val finalPoster = currentBannerImg ?: defaultCover
                val streamLink = db[postId] ?: baseHref
                val targetData = TelegramTarget(streamLink, finalPoster).toJson()
                
                movies.add(newMovieSearchResponse(rawTitle, targetData, TvType.Movie) {
                    this.posterUrl = finalPoster
                })
                
                currentBannerImg = null
            }
        }
        if (movies.isEmpty()) return null
        return newHomePageResponse("Archivio Cinema", movies.reversed())
    }

    // Struttura temporanea di supporto per ordinare i risultati di ricerca
    private data class ScoredResult(val response: SearchResponse, val score: Int)

    override suspend fun search(query: String): List<SearchResponse> {
        val db = getDatabase()
        val scoredResults = mutableListOf<ScoredResult>()
        
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://t.me/s/archiviocinemaitaliano?q=$encodedQuery"
        val document = app.get(searchUrl).document
        val nodes = document.select(".tgme_widget_message")

        var currentBannerImg: String? = null

        for (node in nodes) {
            val photoNode = node.selectFirst(".tgme_widget_message_photo_image")
            if (photoNode != null) {
                if (currentBannerImg == null) {
                    val extracted = extractCleanPoster(photoNode.attr("style"))
                    if (extracted != defaultCover) currentBannerImg = extracted
                }
                continue
            }

            val textNode = node.selectFirst(".tgme_widget_message_text") ?: continue
            val baseHref = node.selectFirst(".tgme_widget_message_date")?.attr("href") ?: continue
            val postId = extractPostId(baseHref)
            val streamLink = db[postId]

            val rawTitle = textNode.text().substringBefore("\n").trim()
            val score = calculateRelevance(rawTitle, query)

            // Aggiungiamo alla lista se ha un punteggio di rilevanza valido o se è mappato nel DB
            if (score > 0 || streamLink != null) {
                // CORRETTO: Sostituito 'se' con 'if'
                val finalScore = if (score > 0) score else 10 
                val finalPoster = currentBannerImg ?: defaultCover
                val resolvedStream = streamLink ?: baseHref
                
                val targetData = TelegramTarget(resolvedStream, finalPoster).toJson()

                val searchResponse = newMovieSearchResponse(rawTitle, targetData, TvType.Movie) {
                    this.posterUrl = finalPoster
                }
                
                scoredResults.add(ScoredResult(searchResponse, finalScore))
                currentBannerImg = null
            }
        }
        
        // Ordina dal punteggio più alto (più rilevante) al più basso e restituisce solo la lista di SearchResponse
        return scoredResults.sortedByDescending { it.score }.map { it.response }
    }

    override suspend fun load(url: String): LoadResponse? {
        val target = tryParseJson<TelegramTarget>(url) ?: return null
        
        return newMovieLoadResponse("Film in Riproduzione", url, TvType.Movie, target.streamUrl) {
            this.posterUrl = target.poster
            this.backgroundPosterUrl = target.poster
            this.plot = "Streaming nativo indicizzato dal canale Telegram."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Mantenimento della firma stabile richiesta dall'infrastruttura di build CI
        callback.invoke(
            newExtractorLink(
                this.name,
                "Streaming Diretto HD",
                data,
                ExtractorLinkType.VIDEO
            )
        )
        return true
    }
}
