package com.telegram.vod // Mantieni il package originale configurato nel tuo progetto

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

    // Immagine di fallback standard se il post originale non ha una copertina
    private val defaultCover = "https://placehold.co/500x750/222222/FFFFFF/png?text=Locandina+Non+Disponibile"
    
    // URL diretto al JSON live su Gist (senza hash del commit per garantire l'aggiornamento costante)
    private val databaseUrl = "https://gist.githubusercontent.com/ffranckj/d73933a36991f0ff223efa048937fdf1/raw/catalogo.json"
    private var linkDatabase: Map<String, String>? = null

    // Struttura Target arricchita: memorizziamo l'URL di streaming già risolto per un avvio immediato
    private data class TelegramTarget(
        @JsonProperty("postId") val postId: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("poster") val poster: String,
        @JsonProperty("streamUrl") val streamUrl: String
    )

    // Sincronizzazione in cache della mappa ID -> URL di streaming Render
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

    // Estrazione dell'URL dell'immagine di background dai tag style di Telegram
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

    // Estrapola l'ID numerico finale dall'URL del post Telegram (es. "22026")
    private fun extractPostId(url: String): String {
        return url.substringBefore("?").substringAfterLast("/").trim()
    }

    // MOTORE DI RICERCA A FINESTRA: Cerca il link di streaming associato al post.
    // Poiché su Telegram il testo e il file video vengono spesso inviati in messaggi consecutivi
    // (es. testo a ID X e video a ID X+1 o X+2), controlliamo un intervallo di adiacenza [ID-6, ID+6].
    private fun findStreamUrlForPost(db: Map<String, String>, basePostId: String): String? {
        val cleanId = basePostId.trim()
        // 1. Corrispondenza diretta sull'ID esatto
        db[cleanId]?.let { return it.trim() }
        
        // 2. Controllo a finestra sui messaggi adiacenti
        val idNum = cleanId.toIntOrNull()
        if (idNum != null) {
            // Controlla i messaggi successivi (video inviato subito dopo la descrizione)
            for (offset in 1..6) {
                db[(idNum + offset).toString()]?.let { return it.trim() }
            }
            // Controlla i messaggi precedenti (descrizione inviata subito dopo il video)
            for (offset in 1..6) {
                db[(idNum - offset).toString()]?.let { return it.trim() }
            }
        }
        
        // 3. Fallback nel caso in cui le chiavi contengano l'ID all'interno di un URL
        return db.entries.firstOrNull { it.key.contains(cleanId) }?.value?.trim()
    }

    // Calcolo del punteggio di pertinenza per l'ordinamento Fuzzy della ricerca
    private fun calculateRelevance(title: String, query: String): Int {
        val cleanTitle = title.lowercase()
        val cleanQuery = query.lowercase()

        if (!cleanTitle.contains(cleanQuery)) return 0

        return when {
            cleanTitle == cleanQuery -> 100 // Match perfetto
            cleanTitle.startsWith(cleanQuery) -> 80 // Inizia con la query
            cleanTitle.split(Regex("\\s+")).any { it.startsWith(cleanQuery) } -> 60 // Inizio di una parola
            else -> 40 // Contenuto all'interno di una parola
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val nodes = document.select(".tgme_widget_message")
        val db = getDatabase()
        val movies = mutableListOf<SearchResponse>()
        
        var currentBannerImg: String? = null
        
        for (node in nodes) {
            // Catturiamo l'eventuale poster o miniatura video presente nel blocco corrente
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
                
                // FILTRO ASSOLUTO: Se non esiste un link di streaming associato a questa finestra di post,
                // si tratta di un semplice messaggio di chat/servizio e viene scartato in automatico.
                val streamUrl = findStreamUrlForPost(db, postId)
                if (streamUrl != null) {
                    // Estrazione accurata del titolo: priorità al tag <b>, altrimenti prima riga/trattino
                    val bTag = textNode.selectFirst("b")
                    var rawTitle = if (bTag != null && bTag.text().isNotBlank()) {
                        bTag.text().trim()
                    } else {
                        fullText.split("\n").first().split("-").first().trim()
                    }
                    rawTitle = rawTitle.removeSuffix("-").trim()
                    
                    // Pulizia finale per scartare frammenti di testo corti o parole di passaggio
                    if (rawTitle.length > 2 && rawTitle.lowercase() !in setOf("se", "io", "il", "la", "per", "un", "una", "view")) {
                        val finalPoster = currentBannerImg ?: defaultCover
                        val targetData = TelegramTarget(postId, rawTitle, finalPoster, streamUrl).toJson()
                        
                        movies.add(newMovieSearchResponse(rawTitle, targetData, TvType.Movie) {
                            this.posterUrl = finalPoster
                        })
                        
                        currentBannerImg = null // Reset del poster dopo averlo associato al film
                    }
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
                
                val streamUrl = findStreamUrlForPost(db, postId)
                if (streamUrl != null) {
                    val bTag = textNode.selectFirst("b")
                    var rawTitle = if (bTag != null && bTag.text().isNotBlank()) {
                        bTag.text().trim()
                    } else {
                        fullText.split("\n").first().split("-").first().trim()
                    }
                    rawTitle = rawTitle.removeSuffix("-").trim()

                    if (rawTitle.length > 2 && rawTitle.lowercase() !in setOf("se", "io", "il", "la", "per", "un", "una")) {
                        val score = when {
                            isCodeSearch && postId == cleanQuery -> 200 // Codice ID esatto cercato
                            else -> calculateRelevance(rawTitle, cleanQuery)
                        }
                        
                        // Assegna un punteggio base se la query è contenuta nella descrizione estesa
                        val finalScore = if (score > 0) score else if (fullText.contains(cleanQuery, ignoreCase = true)) 20 else 0

                        if (finalScore > 0 || isCodeSearch) {
                            val finalPoster = currentBannerImg ?: defaultCover
                            val targetData = TelegramTarget(postId, rawTitle, finalPoster, streamUrl).toJson()

                            val searchResponse = newMovieSearchResponse(rawTitle, targetData, TvType.Movie) {
                                this.posterUrl = finalPoster
                            }
                            
                            scoredResults.add(ScoredResult(searchResponse, finalScore))
                            currentBannerImg = null
                        }
                    }
                }
            }
        }
        
        // INTERVENTO DI RISOLUZIONE DIRETTA: Se la ricerca per ID numerico non ha prodotto risultati,
        // interroghiamo la pagina del singolo post Telegram per estrarre locandina e titolo.
        if (scoredResults.isEmpty() && isCodeSearch) {
            try {
                val directUrl = "https://t.me/s/archiviocinemaitaliano/$cleanQuery"
                val doc = app.get(directUrl).document
                val textNode = doc.selectFirst(".tgme_widget_message_text")
                if (textNode != null) {
                    val fullText = textNode.text().trim()
                    val bTag = textNode.selectFirst("b")
                    var rawTitle = if (bTag != null && bTag.text().isNotBlank()) {
                        bTag.text().trim()
                    } else {
                        fullText.split("\n").first().split("-").first().trim()
                    }
                    rawTitle = rawTitle.removeSuffix("-").trim()
                    
                    val styleNode = doc.selectFirst(".tgme_widget_message_photo_wrap, .tgme_widget_message_video_thumb, .tgme_widget_message_photo_image")
                    val poster = if (styleNode != null) extractCleanPoster(styleNode.attr("style")) else defaultCover
                    
                    val streamUrl = findStreamUrlForPost(db, cleanQuery)
                    if (streamUrl != null && rawTitle.length > 2) {
                        val targetData = TelegramTarget(cleanQuery, rawTitle, poster, streamUrl).toJson()
                        val searchResponse = newMovieSearchResponse(rawTitle, targetData, TvType.Movie) {
                            this.posterUrl = poster
                        }
                        scoredResults.add(ScoredResult(searchResponse, 200))
                    }
                }
            } catch (e: Exception) {}
        }

        // Restituisce i risultati ordinati per pertinenza, filtrando eventuali duplicati generati da Telegram
        return scoredResults.sortedByDescending { it.score }.distinctBy { it.response.url }.map { it.response }
    }

    override suspend fun load(url: String): LoadResponse? {
        val target = tryParseJson<TelegramTarget>(url) ?: return null
        
        return newMovieLoadResponse(target.title, url, TvType.Movie, url) {
            this.posterUrl = target.poster
            this.backgroundPosterUrl = target.poster
            this.plot = "ID Canale Telegram: #${target.postId}\nFlusso video in streaming nativo da Render."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val target = tryParseJson<TelegramTarget>(data) ?: return false

        // Sfruttiamo l'URL di streaming Render reale, risolto e blindato nei passaggi precedenti
        if (target.streamUrl.isBlank() || target.streamUrl.contains("t.me/")) {
            return false
        }

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "Streaming Diretto HD",
                url = target.streamUrl,
                type = ExtractorLinkType.VIDEO
            )
        )
        return true
    }
}
