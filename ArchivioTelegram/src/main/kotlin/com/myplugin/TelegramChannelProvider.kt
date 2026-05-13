package com.telegram.vod // Mantieni il namespace configurato all'interno del progetto

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

    // Immagine standard in assenza di metadati grafici associati al nodo sorgente
    private val defaultCover = "https://placehold.co/500x750/222222/FFFFFF/png?text=Locandina+Non+Disponibile"
    
    // Riferimento al catalogo JSON live privo di revisione temporale
    private val databaseUrl = "https://gist.githubusercontent.com/ffranckj/d73933a36991f0ff223efa048937fdf1/raw/catalogo.json"
    private var linkDatabase: Map<String, String>? = null

    // Struttura di transizione: conserva l'ID pulito, il titolo formattato e l'immagine per preservare la UI
    private data class TelegramTarget(val postId: String, val title: String, val poster: String)

    // Esecuzione del fetch in memoria della mappa ID -> URL di Render
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

    // Estrazione del background grafico nativo Telegram
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

    // Estrazione rigida dell'identificativo numerico per aderenza al JSON
    private fun extractPostId(url: String): String {
        return url.substringBefore("?").substringAfterLast("/").trim()
    }

    // Punteggio di pertinenza per l'ordinamento dinamico Fuzzy
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
                
                // Definizione e pulizia del titolo per il rendering grafico nativo
                val titleElem = textNode.selectFirst("b")?.text()
                val rawTitle = if (!titleElem.isNullOrBlank()) titleElem else textNode.text().split("-").first().trim()
                val finalPoster = currentBannerImg ?: defaultCover
                
                // Trasferisce esplicitamente il rawTitle nel target per impedire il rendering con ID raw
                val targetData = TelegramTarget(postId, rawTitle, finalPoster).toJson()
                
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
                
                val targetData = TelegramTarget(postId, rawTitle, finalPoster).toJson()

                val searchResponse = newMovieSearchResponse(rawTitle, targetData, TvType.Movie) {
                    this.posterUrl = finalPoster
                }
                
                scoredResults.add(ScoredResult(searchResponse, finalScore))
            }
            currentBannerImg = null
        }
        
        // Risoluzione in fallback in caso di interrogazione numerica specifica
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
                    
                    val targetData = TelegramTarget(cleanQuery, rawTitle, poster).toJson()
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
        
        // RIPRISTINATO: Verrà istanziata l'interfaccia nativa trasferendo esplicitamente il target.title
        return newMovieLoadResponse(target.title, url, TvType.Movie, url) {
            this.posterUrl = target.poster
            this.backgroundPosterUrl = target.poster
            this.plot = "Flusso autorizzato per l'ID Canale: #${target.postId}\nPremi Play per avviare il video da Render."
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

        // Match di decodifica rigoroso sul catalogo JSON per sbloccare la sorgente
        val streamUrl = db[target.postId] ?: db.entries.firstOrNull { it.key == target.postId }?.value

        if (streamUrl.isNullOrBlank()) {
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
