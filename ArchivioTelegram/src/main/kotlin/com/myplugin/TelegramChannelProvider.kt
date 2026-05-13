package com.telegram.vod // Mantieni inalterato il package originale del tuo modulo

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// Modelli dati per ricevere le risposte grafiche di TMDB
data class TmdbResponse(
    @JsonProperty("results") val results: List<TmdbResult>?
)

data class TmdbResult(
    @JsonProperty("poster_path") val posterPath: String?
)

// Modelli dati per il Catalogo JSON Gist
data class CatalogoEntry(
    @JsonProperty("streamUrl") val streamUrl: String?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("poster") val poster: String?
)

data class StreamTarget(
    @JsonProperty("title") val title: String,
    @JsonProperty("streamUrl") val streamUrl: String,
    @JsonProperty("poster") val poster: String?
)

class TelegramChannelProvider : MainAPI() {
    override var mainUrl = "https://t.me/s/archiviocinemaitaliano"
    override var name = "Archivio Cinema Italiano"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    // Locandina predefinita di sicurezza se TMDB non trova corrispondenze
    private val defaultCover = "https://placehold.co/600x900/222222/FFFFFF/png?text=Archivio+Cinema+Italiano"
    
    private val databaseUrl = "https://gist.githubusercontent.com/ffranckj/d73933a36991f0ff223efa048937fdf1/raw/catalogo.json"
    
    private var catalogoCache: Map<String, CatalogoEntry>? = null

    private suspend fun getCatalogo(): Map<String, CatalogoEntry> {
        catalogoCache?.let { return it }
        return try {
            val responseText = app.get(databaseUrl).text
            val map = parseJson<Map<String, CatalogoEntry>>(responseText)
            catalogoCache = map
            map
        } catch (e: Exception) {
            e.printStackTrace()
            emptyMap()
        }
    }

    private fun pulisciTitolo(titoloGrezzo: String?): String {
        if (titoloGrezzo.isNullOrBlank()) return "Film Senza Titolo"
        return titoloGrezzo.replace("*", "")
                           .replace("`", "")
                           .trim()
    }

    // Interroga TMDB usando la chiave segreta iniettata da GitHub Actions
    private suspend fun getTmdbPoster(title: String): String {
        // Ripuliamo il titolo da diciture di parti o puntate per facilitare il match su TMDB
        var query = title.replace(Regex("(?i)\\(.*\\)"), "") // Rimuove anni o note tra parentesi
        query = query.replace(Regex("(?i)prima parte|seconda parte|parte\\s*\\d+|puntata\\s*\\d+|1x\\d+|2x\\d+|3x\\d+"), "")
        query = query.trim()

        if (query.length < 2) return defaultCover

        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/movie?api_key=${BuildConfig.TMDB_API}&query=$encoded&language=it"
            val resp = app.get(url).text
            val res = tryParseJson<TmdbResponse>(resp)
            val posterPath = res?.results?.firstOrNull { !it.posterPath.isNullOrBlank() }?.posterPath
            
            if (!posterPath.isNullOrBlank()) {
                "https://image.tmdb.org/t/p/w500$posterPath"
            } else {
                defaultCover
            }
        } catch (e: Exception) {
            defaultCover
        }
    }

    private fun calcolaPertinenza(titolo: String, query: String): Int {
        val t = titolo.lowercase()
        val q = query.lowercase()
        if (!t.contains(q)) return 0
        return when {
            t == q -> 100
            t.startsWith(q) -> 80
            t.split(" ").any { it.startsWith(q) } -> 60
            else -> 40
        }
    }

    // =========================================================================
    // HOME PAGE: 30 Film Casuali con Fetch Copertine in Parallelo
    // =========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val catalogo = getCatalogo()
        if (catalogo.isEmpty()) return null

        val vociValide = catalogo.entries.filter { !it.value.streamUrl.isNullOrBlank() }
        val selezioneCasuale = vociValide.shuffled().take(30)

        // coroutineScope + async avviano le 30 chiamate di rete a TMDB simultaneamente
        val movies = coroutineScope {
            selezioneCasuale.map { (id, entry) ->
                async {
                    val streamUrl = entry.streamUrl!!.trim()
                    val titoloPulito = pulisciTitolo(entry.title)
                    val customPoster = entry.poster?.trim()
                    
                    // Se il JSON ha una copertina custom usa quella, altrimenti interroga TMDB
                    val posterFinale = if (!customPoster.isNullOrBlank()) {
                        customPoster
                    } else {
                        getTmdbPoster(titoloPulito)
                    }

                    val target = StreamTarget(titoloPulito, streamUrl, posterFinale).toJson()

                    newMovieSearchResponse(titoloPulito, target, TvType.Movie) {
                        this.posterUrl = posterFinale
                    }
                }
            }.awaitAll()
        }

        if (movies.isEmpty()) return null
        return newHomePageResponse("Film Consigliati (Casuali)", movies)
    }

    private data class RisultatoMatch(
        val titolo: String,
        val streamUrl: String,
        val customPoster: String?,
        val score: Int
    )

    // =========================================================================
    // RICERCA GLOBALE CON COPERTINE IN HD
    // =========================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val catalogo = getCatalogo()
        val risultatiMatch = mutableListOf<RisultatoMatch>()
        val q = query.trim()
        val isRicercaCodice = q.toIntOrNull() != null

        for ((id, entry) in catalogo) {
            val streamUrl = entry.streamUrl?.trim()
            if (!streamUrl.isNullOrBlank()) {
                val titoloPulito = pulisciTitolo(entry.title)
                val customPoster = entry.poster?.trim()
                
                val score = if (isRicercaCodice && id == q) {
                    200 
                } else {
                    calcolaPertinenza(titoloPulito, q)
                }

                if (score > 0) {
                    risultatiMatch.add(RisultatoMatch(titoloPulito, streamUrl, customPoster, score))
                }
            }
        }

        // Preleviamo i 40 risultati più pertinenti e risolviamo i poster in parallelo
        val topResults = risultatiMatch.sortedByDescending { it.score }.take(40)

        return coroutineScope {
            topResults.map { match ->
                async {
                    val posterFinale = if (!match.customPoster.isNullOrBlank()) {
                        match.customPoster
                    } else {
                        getTmdbPoster(match.titolo)
                    }
                    val target = StreamTarget(match.titolo, match.streamUrl, posterFinale).toJson()
                    
                    newMovieSearchResponse(match.titolo, target, TvType.Movie) {
                        this.posterUrl = posterFinale
                    }
                }
            }.awaitAll()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val target = tryParseJson<StreamTarget>(url) ?: return null
        
        return newMovieLoadResponse(target.title, url, TvType.Movie, target.streamUrl) {
            this.posterUrl = if (!target.poster.isNullOrBlank()) target.poster else defaultCover
            this.plot = "Flusso streaming on-demand agganciato dalla sorgente JSON."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "Streaming Diretto HD",
                url = data,
                type = ExtractorLinkType.VIDEO
            )
        )
        return true
    }
}
