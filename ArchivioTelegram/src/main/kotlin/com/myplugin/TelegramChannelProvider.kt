package com.telegram.vod // Mantieni inalterato il package originale del tuo progetto

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLEncoder

class TelegramChannelProvider : MainAPI() {
    override var mainUrl = "https://t.me/s/archiviocinemaitaliano"
    override var name = "Archivio Cinema Italiano"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    // Copertina di fallback standard in assenza di grafiche nel post sorgente
    private val defaultCover = "https://placehold.co/500x750/222222/FFFFFF/png?text=Locandina+Non+Disponibile"
    
    // URL di fetch remoto verso l'ultima versione del catalogo Gist
    private val databaseUrl = "https://gist.githubusercontent.com/ffranckj/d73933a36991f0ff223efa048937fdf1/raw/catalogo.json"
    
    // Modello dati per il mapping strutturato delle chiavi JSON
    private data class CatalogoEntry(
        @JsonProperty("streamUrl") val streamUrl: String?,
        @JsonProperty("title") val title: String?
    )
    
    private var linkDatabase: Map<String, CatalogoEntry>? = null

    // Modello dati di navigazione interna trasferito alle viste di riproduzione
    private data class TelegramTarget(
        @JsonProperty("postId") val postId: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("poster") val poster: String,
        @JsonProperty("streamUrl") val streamUrl: String
    )

    private suspend fun getDatabase(): Map<String, CatalogoEntry> {
        if (linkDatabase == null) {
            try {
                val jsonText = app.get(databaseUrl).text
                linkDatabase = parseJson<Map<String, CatalogoEntry>>(jsonText).mapKeys { it.key.trim() }
            } catch (e: Exception) {
                linkDatabase = emptyMap()
            }
        }
        return linkDatabase ?: emptyMap()
    }

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

    // Risoluzione potenziata: esplora una finestra sequenziale estesa (+/- 30 messaggi)
    // per intercettare l'associazione tra post descrittivo e flusso streaming Render remoto.
    private fun findCatalogoEntryForPost(db: Map<String, CatalogoEntry>, basePostId: String): CatalogoEntry? {
        val cleanId = basePostId.trim()
        
        db[cleanId]?.let { return it }
        
        val idNum = cleanId.toIntOrNull()
        if (idNum != null) {
            // Esplora in avanti per flussi inviati in coda
            for (offset in 1..30) {
                db[(idNum + offset).toString()]?.let { return it }
            }
            // Esplora all'indietro
            for (offset in 1..30) {
                db[(idNum - offset).toString()]?.let { return it }
            }
        }
        
        return db.entries.firstOrNull { it.key.contains(cleanId) }?.value
    }

    private fun sanitizeTitle(raw: String): String {
        return raw.replace("*", "")
                  .removeSuffix("-")
                  .trim()
    }

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
        val db = getDatabase()
        val movies = mutableListOf<SearchResponse>()
        
        var currentBannerImg: String? = null
        
        for (node in nodes) {
            val styleNode = node.selectFirst(".tgme_widget_message_photo_wrap, .tgme_widget_message_video_thumb, .tgme_widget_message_photo_image")
            val extractedPoster = if (styleNode != null) extractCleanPoster(styleNode.attr("style")) else null
            
            if (extractedPoster != null && extractedPoster != defaultCover) {
                currentBannerImg = extractedPoster
            }
            
            val textNode = node.selectFirst(".tgme_widget_message_text")
            if (textNode != null) {
                val fullText = textNode.text().trim()
                val baseHref = node.selectFirst(".tgme_widget_message_date")?.attr("href") ?: continue
                val postId = extractPostId(baseHref)
                
                // DISACCOPPIAMENTO HOME: Estraiamo il titolo nativo e mostriamo il film a prescindere
                // dal mapping immediato sul JSON, garantendo il popolamento immediato della vista.
                val catEntry = findCatalogoEntryForPost(db, postId)
                val resolvedStream = catEntry?.streamUrl?.trim() ?: ""
                
                val jsonTitle = catEntry?.title
                val bTag = textNode.selectFirst("b")
                
                val rawUnsanitizedTitle = when {
                    !jsonTitle.isNullOrBlank() -> jsonTitle
                    bTag != null && bTag.text().isNotBlank() -> bTag.text()
                    else -> fullText.split("\n").first().split("-").first()
                }
                
                val cleanTitle = sanitizeTitle(rawUnsanitizedTitle)
                
                if (cleanTitle.length > 2 && cleanTitle.lowercase() !in setOf("se", "io", "il", "la", "per", "un", "una", "view")) {
                    val finalPoster = currentBannerImg ?: defaultCover
                    val targetData = TelegramTarget(postId, cleanTitle, finalPoster, resolvedStream).toJson()
                    
                    movies.add(newMovieSearchResponse(cleanTitle, targetData, TvType.Movie) {
                        this.posterUrl = finalPoster
                    })
                    
                    currentBannerImg = null
                }
            }
        }
        if (movies.isEmpty()) return null
        return newHomePageResponse("Archivio Cinema", movies.reversed())
    }

    private data class ScoredResult(val response: SearchResponse, val score: Int)

    override suspend fun search(query: String): List<SearchResponse> {
        val db = getDatabase()
        val scoredResults = mutableListOf<ScoredResult>()
        val cleanQuery = query.trim()
        val isCodeSearch = cleanQuery.toIntOrNull() != null

        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
        val searchUrl = "https://t.me/s/archiviocinemaitaliano?q=$encodedQuery"
        val document = app.get(searchUrl).document
        val nodes = document.select(".tgme_widget_message")

        var currentBannerImg: String? = null

        for (node in nodes) {
            val styleNode = node.selectFirst(".tgme_widget_message_photo_wrap, .tgme_widget_message_video_thumb, .tgme_widget_message_photo_image")
            val extractedPoster = if (styleNode != null) extractCleanPoster(styleNode.attr("style")) else null
            
            if (extractedPoster != null && extractedPoster != defaultCover) {
                currentBannerImg = extractedPoster
            }

            val textNode = node.selectFirst(".tgme_widget_message_text")
            if (textNode != null) {
                val fullText = textNode.text().trim()
                val baseHref = node.selectFirst(".tgme_widget_message_date")?.attr("href") ?: continue
                val postId = extractPostId(baseHref)
                
                val catEntry = findCatalogoEntryForPost(db, postId)
                val resolvedStream = catEntry?.streamUrl?.trim() ?: ""
                
                val jsonTitle = catEntry?.title
                val bTag = textNode.selectFirst("b")
                
                val rawUnsanitizedTitle = when {
                    !jsonTitle.isNullOrBlank() -> jsonTitle
                    bTag != null && bTag.text().isNotBlank() -> bTag.text()
                    else -> fullText.split("\n").first().split("-").first()
                }

                val cleanTitle = sanitizeTitle(rawUnsanitizedTitle)

                if (cleanTitle.length > 2 && cleanTitle.lowercase() !in setOf("se", "io", "il", "la", "per", "un", "una")) {
                    val score = when {
                        isCodeSearch && postId == cleanQuery -> 200
                        else -> calculateRelevance(cleanTitle, cleanQuery)
                    }
                    
                    val finalScore = if (score > 0) score else if (fullText.contains(cleanQuery, ignoreCase = true)) 20 else 0

                    if (finalScore > 0 || isCodeSearch) {
                        val finalPoster = currentBannerImg ?: defaultCover
                        val targetData = TelegramTarget(postId, cleanTitle, finalPoster, resolvedStream).toJson()

                        val searchResponse = newMovieSearchResponse(cleanTitle, targetData, TvType.Movie) {
                            this.posterUrl = finalPoster
                        }
                        
                        scoredResults.add(ScoredResult(searchResponse, finalScore))
                        currentBannerImg = null
                    }
                }
            }
        }
        
        if (scoredResults.isEmpty() && isCodeSearch) {
            try {
                val directUrl = "https://t.me/s/archiviocinemaitaliano/$cleanQuery"
                val doc = app.get(directUrl).document
                val textNode = doc.selectFirst(".tgme_widget_message_text")
                if (textNode != null) {
                    val fullText = textNode.text().trim()
                    
                    val catEntry = findCatalogoEntryForPost(db, cleanQuery)
                    val resolvedStream = catEntry?.streamUrl?.trim() ?: ""
                    
                    val jsonTitle = catEntry?.title
                    val bTag = textNode.selectFirst("b")
                    
                    val rawUnsanitizedTitle = when {
                        !jsonTitle.isNullOrBlank() -> jsonTitle
                        bTag != null && bTag.text().isNotBlank() -> bTag.text()
                        else -> fullText.split("\n").first().split("-").first()
                    }
                    
                    val cleanTitle = sanitizeTitle(rawUnsanitizedTitle)
                    val styleNode = doc.selectFirst(".tgme_widget_message_photo_wrap, .tgme_widget_message_video_thumb, .tgme_widget_message_photo_image")
                    val poster = if (styleNode != null) extractCleanPoster(styleNode.attr("style")) else defaultCover
                    
                    if (cleanTitle.length > 2) {
                        val targetData = TelegramTarget(cleanQuery, cleanTitle, poster, resolvedStream).toJson()
                        val searchResponse = newMovieSearchResponse(cleanTitle, targetData, TvType.Movie) {
                            this.posterUrl = poster
                        }
                        scoredResults.add(ScoredResult(searchResponse, 200))
                    }
                }
            } catch (e: Exception) {}
        }

        return scoredResults.sortedByDescending { it.score }.distinctBy { it.response.url }.map { it.response }
    }

    override suspend fun load(url: String): LoadResponse? {
        val target = tryParseJson<TelegramTarget>(url) ?: return null
        
        return newMovieLoadResponse(target.title, url, TvType.Movie, url) {
            this.posterUrl = target.poster
            this.backgroundPosterUrl = target.poster
            this.plot = "ID Canale Telegram: #${target.postId}\nFlusso di rete nativo transcodificato tramite Render."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val target = tryParseJson<TelegramTarget>(data) ?: return false
        var targetStreamUrl = target.streamUrl

        // Se la stringa passata non possiede un flusso pre-risolto in fase di parsing visivo,
        // tentiamo una seconda risoluzione di profondità sul JSON live al momento del Play.
        if (targetStreamUrl.isBlank() || targetStreamUrl.contains("t.me/")) {
            val db = getDatabase()
            val catEntry = findCatalogoEntryForPost(db, target.postId)
            targetStreamUrl = catEntry?.streamUrl?.trim() ?: ""
        }

        if (targetStreamUrl.isBlank() || targetStreamUrl.contains("t.me/")) {
            return false
        }

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "Streaming Diretto HD",
                url = targetStreamUrl,
                type = ExtractorLinkType.VIDEO
            )
        )
        return true
    }
}
