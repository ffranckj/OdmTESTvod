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

    // Immagine di fallback standard in assenza di copertina
    private val defaultCover = "https://placehold.co/500x750/222222/FFFFFF/png?text=Locandina+Non+Disponibile"
    
    // URL diretto al raw JSON live su Gist (privo di hash del commit per garantire l'aggiornamento costante)
    private val databaseUrl = "https://gist.githubusercontent.com/ffranckj/d73933a36991f0ff223efa048937fdf1/raw/catalogo.json"
    private var linkDatabase: Map<String, String>? = null

    // Struttura di sessione per conservare in modo affidabile ID e Titolo per la fase di avvio
    private data class TelegramTarget(val postId: String, val title: String, val poster: String)

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

    // MOTORE DI RICERCA STREAMING: Mappa in modo flessibile sia per ID numerico che per Titolo del film
    private fun findStreamUrl(db: Map<String, String>, postId: String, title: String): String? {
        val cleanPostId = postId.trim()
        if (cleanPostId.isNotEmpty()) {
            // 1. Verifica corrispondenza diretta o parziale dell'ID numerico
            db[cleanPostId]?.let { return it.trim() }
            db.entries.firstOrNull { it.key.trim() == cleanPostId }?.value?.let { return it.trim() }
            db.entries.firstOrNull { it.key.contains(cleanPostId) }?.value?.let { return it.trim() }
        }
        
        val cleanTitle = title.trim()
        if (cleanTitle.isNotEmpty()) {
            // 2. Verifica corrispondenza del Titolo esatto (case-insensitive)
            db.entries.firstOrNull { it.key.trim().equals(cleanTitle, ignoreCase = true) }?.value?.let { return it.trim() }
            
            // 3. Verifica robusta ripulendo da spazi e caratteri speciali
            val alphaTitle = cleanTitle.lowercase().replace(Regex("[^a-z0-9]"), "")
            if (alphaTitle.length > 2) {
                db.entries.firstOrNull { entry ->
                    val alphaKey = entry.key.lowercase().replace(Regex("[^a-z0-9]"), "")
                    alphaKey.isNotEmpty() && (alphaKey == alphaTitle || alphaKey.contains(alphaTitle) || alphaTitle.contains(alphaKey))
                }?.value?.let { return it.trim() }
            }
        }
        return null
    }

    // Punteggio di pertinenza intatto per ordinamento Fuzzy
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
            // Rimosso il controllo restrittivo sul video per far apparire l'intero catalogo in Home
            if (textNode != null) {
                val baseHref = node.selectFirst(".tgme_widget_message_date")?.attr("href") ?: continue
                val postId = extractPostId(baseHref)
                
                val titleElem = textNode.selectFirst("b")?.text()
                val rawTitle = if (!titleElem.isNullOrBlank()) titleElem else textNode.text().split("-").first().trim()
                
                if (rawTitle.isNotEmpty()) {
                    val finalPoster = currentBannerImg ?: defaultCover
                    val targetData = TelegramTarget(postId, rawTitle, finalPoster).toJson()
                    
                    movies.add(newMovieSearchResponse(rawTitle, targetData, TvType.Movie) {
                        this.posterUrl = finalPoster
                    })
                }
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
            
            // Assegna un punteggio base se la query testuale compare nella descrizione del post
            val finalScore = if (score > 0) score else if (textNode.text().contains(cleanQuery, ignoreCase = true)) 20 else 0

            if (finalScore > 0 || (isCodeSearch && textNode.text().contains(cleanQuery))) {
                val finalPoster = currentBannerImg ?: defaultCover
                val targetData = TelegramTarget(postId, rawTitle, finalPoster).toJson()

                val searchResponse = newMovieSearchResponse(rawTitle, targetData, TvType.Movie) {
                    this.posterUrl = finalPoster
                }
                
                scoredResults.add(ScoredResult(searchResponse, finalScore))
            }
            currentBannerImg = null
        }
        
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
        
        return newMovieLoadResponse(target.title, url, TvType.Movie, url) {
            this.posterUrl = target.poster
            this.backgroundPosterUrl = target.poster
            this.plot = "ID Canale Telegram: #${target.postId}\nPremi Play per avviare lo streaming associato nel catalogo JSON."
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

        // Risoluzione potenziata: abbina sia per ID numerico che per Titolo del film
        val streamUrl = findStreamUrl(db, target.postId, target.title)

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
