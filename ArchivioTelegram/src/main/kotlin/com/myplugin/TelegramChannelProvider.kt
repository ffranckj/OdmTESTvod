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

    // Locandina di fallback se il post originale su Telegram non ha immagini
    private val defaultCover = "https://placehold.co/500x750/222222/FFFFFF/png?text=Locandina+Non+Disponibile"
    
    // URL del catalogo JSON live (senza hash di commit per assicurare il fetch costante dell'ultima versione)
    private val databaseUrl = "https://gist.githubusercontent.com/ffranckj/d73933a36991f0ff223efa048937fdf1/raw/catalogo.json"
    private var linkDatabase: Map<String, String>? = null

    // Struttura Target: memorizziamo l'ID univoco del post per fare la verifica JSON al momento del Play
    private data class TelegramTarget(val postId: String, val postUrl: String, val poster: String)

    // Sincronizzazione e pulizia delle chiavi del database Gist
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

    // Estrazione dell'immagine nativa di Telegram
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

    // Estrapola l'ID numerico finale del post (es. "21911")
    private fun extractPostId(url: String): String {
        return url.substringBefore("?").substringAfterLast("/").trim()
    }

    // Calcolo del punteggio per ordinamento Fuzzy della ricerca
    private fun calculateRelevance(title: String, query: String): Int {
        val cleanTitle = title.lowercase()
        val cleanQuery = query.lowercase()

        if (!cleanTitle.contains(cleanQuery)) return 0

        return when {
            cleanTitle == cleanQuery -> 100
            cleanTitle.startsWith(cleanQuery) -> 80
            cleanTitle.split(Regex("\\s+")).any { it.startsWith(cleanQuery) } -> 60
            else -> 40
        }
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
                    val extracted = extractCleanPoster(photoNode.attr("style"))
                    if (extracted != defaultCover) currentBannerImg = extracted
                }
                continue
            }
            
            val textNode = node.selectFirst(".tgme_widget_message_text")
            val hasVideo = node.selectFirst(".tgme_widget_message_video, .tgme_widget_message_video_player") != null
            
            if (textNode != null && hasVideo) {
                val baseHref = node.selectFirst(".tgme_widget_message_date")?.attr("href") ?: continue
                val postId = extractPostId(baseHref)
                
                // Estrazione pulita e mirata del titolo dal tag <b> nativo di Telegram
                val titleElem = textNode.selectFirst("b")?.text()
                val rawTitle = if (!titleElem.isNullOrBlank()) titleElem else textNode.text().split("-").first().trim()
                
                val finalPoster = currentBannerImg ?: defaultCover
                
                // Disaccoppiamento: passiamo l'ID nel Target per fare il check JSON solo in fase di streaming
                val targetData = TelegramTarget(postId, baseHref, finalPoster).toJson()
                
                movies.add(newMovieSearchResponse(rawTitle, targetData, TvType.Movie) {
                    this.posterUrl = finalPoster
                })
                
                currentBannerImg = null
            }
        }
        if (movies.isEmpty()) return null
        return newHomePageResponse("Archivio Cinema", movies.reversed())
    }

    private data class ScoredResult(val response: SearchResponse, val score: Int)

    override suspend fun search(query: String): List<SearchResponse> {
        val scoredResults = mutableListOf<ScoredResult>()
        val cleanQuery = query.trim()
        val isCodeSearch = cleanQuery.toIntOrNull() != null

        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
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
            
            val titleElem = textNode.selectFirst("b")?.text()
            val rawTitle = if (!titleElem.isNullOrBlank()) titleElem else textNode.text().split("-").first().trim()

            val score = when {
                isCodeSearch && postId == cleanQuery -> 200
                else -> calculateRelevance(rawTitle, cleanQuery)
            }

            if (score > 0 || (isCodeSearch && textNode.text().contains(cleanQuery))) {
                val finalPoster = currentBannerImg ?: defaultCover
                val finalScore = if (score > 0) score else 10
                
                val targetData = TelegramTarget(postId, baseHref, finalPoster).toJson()

                val searchResponse = newMovieSearchResponse(rawTitle, targetData, TvType.Movie) {
                    this.posterUrl = finalPoster
                }
                
                scoredResults.add(ScoredResult(searchResponse, finalScore))
            }
            currentBannerImg = null
        }
        
        // Risoluzione diretta del singolo post in caso di ricerca esatta per ID numerico
        if (scoredResults.isEmpty() && isCodeSearch) {
            try {
                val directUrl = "https://t.me/s/archiviocinemaitaliano/$cleanQuery"
                val doc = app.get(directUrl).document
                val textNode = doc.selectFirst(".tgme_widget_message_text")
                if (textNode != null) {
                    val titleElem = textNode.selectFirst("b")?.text()
                    val rawTitle = if (!titleElem.isNullOrBlank()) titleElem else textNode.text().split("-").first().trim()
                    val photoNode = doc.selectFirst(".tgme_widget_message_photo_image")
                    val poster = if (photoNode != null) extractCleanPoster(photoNode.attr("style")) else defaultCover
                    
                    val targetData = TelegramTarget(cleanQuery, directUrl, poster).toJson()
                    val searchResponse = newMovieSearchResponse(rawTitle, targetData, TvType.Movie) {
                        this.posterUrl = poster
                    }
                    scoredResults.add(ScoredResult(searchResponse, 200))
                }
            } catch (e: Exception) {}
        }

        return scoredResults.sortedByDescending { it.score }.map { it.response }
    }

    override suspend fun load(url: String): LoadResponse? {
        val target = tryParseJson<TelegramTarget>(url) ?: return null
        
        // Inviamo l'oggetto Target serializzato a loadLinks
        return newMovieLoadResponse("Film #${target.postId}", url, TvType.Movie, url) {
            this.posterUrl = target.poster
            this.backgroundPosterUrl = target.poster
            this.plot = "ID Canale Telegram: ${target.postId}\nPremi il tasto Play per avviare il flusso video associato."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val target = tryParseJson<TelegramTarget>(data) ?: return false
        val db = getDatabase()

        // Controllo Live: cerchiamo l'abbinamento effettivo nel catalogo JSON in tempo reale
        val streamUrl = db[target.postId] ?: db.entries.firstOrNull { it.key.contains(target.postId) }?.value

        // BLOCCO SICUREZZA: Se il film non è mappato o punta ancora a una pagina web generica,
        // ritorniamo false in modo sicuro. L'app gestirà autonomamente il blocco impedendo crash del player.
        if (streamUrl.isNullOrBlank() || streamUrl.contains("t.me/")) {
            return false
        }

        callback.invoke(
            newExtractorLink(
                this.name,
                "Streaming Diretto HD",
                streamUrl,
                ExtractorLinkType.VIDEO
            )
        )
        return true
    }
}
