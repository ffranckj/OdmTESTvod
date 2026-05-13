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

    // Immagine di fallback se il post originale su Telegram non ha una copertina
    private val defaultCover = "https://placehold.co/500x750/222222/FFFFFF/png?text=Locandina+Non+Disponibile"
    
    // URL del database Gist. 
    // Nota: se aggiorni il file catalogo.json su Gist, assicurati di aggiornare l'hash del commit 
    // in questo link, oppure rimuovi la parte /0cfb5ddca521179de3ba7e859e3099d81b6488d2/ per far 
    // leggere all'applicazione sempre l'ultima versione in tempo reale.
    private val databaseUrl = "https://gist.githubusercontent.com/ffranckj/d73933a36991f0ff223efa048937fdf1/raw/0cfb5ddca521179de3ba7e859e3099d81b6488d2/catalogo.json"
    private var linkDatabase: Map<String, String>? = null

    // Struttura dati interna per instradare in modo sicuro l'URL di streaming effettivo e la copertina
    private data class TelegramTarget(val streamUrl: String, val poster: String)

    // Sincronizzazione e caricamento in cache del catalogo JSON
    private suspend fun getDatabase(): Map<String, String> {
        if (linkDatabase == null) {
            try {
                val jsonText = app.get(databaseUrl).text
                // Mappa il JSON associando in modo pulito l'ID numerico al link Render
                linkDatabase = parseJson<Map<String, String>>(jsonText).mapKeys { it.key.trim() }
            } catch (e: Exception) {
                linkDatabase = emptyMap()
            }
        }
        return linkDatabase ?: emptyMap()
    }

    // Estrazione pulita del poster nativo di Telegram tramite Espressioni Regolari
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

    // Estrae in modo affidabile l'ID numerico finale dall'URL del post Telegram
    private fun extractPostId(url: String): String {
        return url.substringBefore("?").substringAfterLast("/").trim()
    }

    // Algoritmo di calcolo della rilevanza (Scoring Fuzzy) per ordinare i risultati
    private fun calculateRelevance(title: String, query: String): Int {
        val cleanTitle = title.lowercase()
        val cleanQuery = query.lowercase()

        // Se il titolo non contiene le lettere cercate, scarta subito
        if (!cleanTitle.contains(cleanQuery)) return 0

        return when {
            cleanTitle == cleanQuery -> 100 // Corrispondenza perfetta
            cleanTitle.startsWith(cleanQuery) -> 80 // Il titolo inizia con le lettere cercate
            // Una qualsiasi parola del titolo inizia con le lettere cercate
            cleanTitle.split(Regex("\\s+")).any { it.startsWith(cleanQuery) } -> 60 
            else -> 40 // Le lettere si trovano all'interno di una parola
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
                val baseHref = node.selectFirst(".tgme_widget_message_date")?.attr("href") ?: continue
                val postId = extractPostId(baseHref)
                
                // VINCOLO STRUTTURALE: Mostra in Home SOLO i film regolarmente associati nel catalogo JSON
                val streamUrl = db[postId]
                if (streamUrl != null) {
                    val rawTitle = textNode.text().substringBefore("\n").trim()
                    val finalPoster = currentBannerImg ?: defaultCover
                    
                    val targetData = TelegramTarget(streamUrl, finalPoster).toJson()
                    
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

    // Classe di supporto temporanea per ordinare i risultati in base al punteggio calcolato
    private data class ScoredResult(val response: SearchResponse, val score: Int)

    override suspend fun search(query: String): List<SearchResponse> {
        val db = getDatabase()
        val scoredResults = mutableListOf<ScoredResult>()
        val cleanQuery = query.trim()
        
        // Determina in automatico se l'utente sta cercando direttamente tramite Codice ID numerico
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
            
            // Il flusso video deve corrispondere in modo assoluto al mapping del JSON
            val streamUrl = db[postId]
            if (streamUrl != null) {
                val rawTitle = textNode.text().substringBefore("\n").trim()
                
                // Assegna il punteggio: se è il codice ID esatto cercato, riceve la priorità massima
                val score = when {
                    isCodeSearch && postId == cleanQuery -> 200
                    else -> calculateRelevance(rawTitle, cleanQuery)
                }

                // Includi nei risultati se rispetta la rilevanza testuale o se corrisponde al codice
                if (score > 0 || (isCodeSearch && textNode.text().contains(cleanQuery))) {
                    val finalPoster = currentBannerImg ?: defaultCover
                    val finalScore = if (score > 0) score else 10
                    
                    val targetData = TelegramTarget(streamUrl, finalPoster).toJson()

                    val searchResponse = newMovieSearchResponse(rawTitle, targetData, TvType.Movie) {
                        this.posterUrl = finalPoster
                    }
                    
                    scoredResults.add(ScoredResult(searchResponse, finalScore))
                }
                currentBannerImg = null
            }
        }
        
        // FALLBACK DI PRECISIONE: Se si cerca per Codice ID ed è presente nel JSON, ma la ricerca 
        // standard di Telegram non lo ha restituito, interroghiamo direttamente il singolo post.
        if (scoredResults.isEmpty() && isCodeSearch && db.containsKey(cleanQuery)) {
            try {
                val directUrl = "https://t.me/s/archiviocinemaitaliano/$cleanQuery"
                val doc = app.get(directUrl).document
                val textNode = doc.selectFirst(".tgme_widget_message_text")
                val streamUrl = db[cleanQuery]
                if (textNode != null && streamUrl != null) {
                    val rawTitle = textNode.text().substringBefore("\n").trim()
                    val photoNode = doc.selectFirst(".tgme_widget_message_photo_image")
                    val poster = if (photoNode != null) extractCleanPoster(photoNode.attr("style")) else defaultCover
                    
                    val targetData = TelegramTarget(streamUrl, poster).toJson()
                    val searchResponse = newMovieSearchResponse(rawTitle, targetData, TvType.Movie) {
                        this.posterUrl = poster
                    }
                    scoredResults.add(ScoredResult(searchResponse, 200))
                }
            } catch (e: Exception) {
                // Ignora silenziosamente errori di rete nel blocco di emergenza
            }
        }

        // Restituisce la lista ordinata partendo dal punteggio più alto (più pertinente) al più basso
        return scoredResults.sortedByDescending { it.score }.map { it.response }
    }

    override suspend fun load(url: String): LoadResponse? {
        val target = tryParseJson<TelegramTarget>(url) ?: return null
        
        return newMovieLoadResponse("Film in Riproduzione", url, TvType.Movie, target.streamUrl) {
            this.posterUrl = target.poster
            this.backgroundPosterUrl = target.poster
            this.plot = "Streaming nativo erogato dal servizio dati personale."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Interfaccia diretta e stabile per il riproduttore video di CloudStream
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
