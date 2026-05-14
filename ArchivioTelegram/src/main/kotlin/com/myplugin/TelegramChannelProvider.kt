package com.telegram.vod // Mantieni inalterato il package originale del tuo progetto

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

// Risposte TMDB
data class TmdbResponse(
    @JsonProperty("results") val results: List<TmdbResult>?
)

data class TmdbResult(
    @JsonProperty("poster_path") val posterPath: String?
)

// Modelli dati allineati al nuovo formato JSON (Waterfall Failover)
data class CatalogoEntry(
    @JsonProperty("routePath") val routePath: String?,
    @JsonProperty("servers") val servers: List<String>?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("poster") val poster: String?
)

// Target di transito per passare rotte e riserve alla schermata di riproduzione
data class StreamTarget(
    @JsonProperty("title") val title: String,
    @JsonProperty("routePath") val routePath: String,
    @JsonProperty("servers") val servers: List<String>,
    @JsonProperty("poster") val poster: String?
)

class TelegramChannelProvider : MainAPI() {
    override var mainUrl = "https://t.me/s/archiviocinemaitaliano"
    override var name = "Archivio Cinema Italiano"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    // Segnaposto grafico standard se TMDB non restituisce locandine
    private val defaultCover = "https://placehold.co/600x900/222222/FFFFFF/png?text=Archivio+Cinema+Italiano"
    
    // Assicurati che l'indirizzo punti sempre al link "Raw" effettivo del tuo Gist
    private val databaseUrl = "https://gist.githubusercontent.com/ffranckj/d73933a36991f0ff223efa048937fdf1/raw/e4b582253a4fc40479f0263a295a05aae9b902da/gistfile2.txt"
    
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

    private suspend fun getTmdbPoster(title: String): String {
        var query = title.replace(Regex("(?i)\\(.*\\)"), "") // Rimuove anni o metadati
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
    // HOME PAGE: Caricamento 30 pellicole casuali supportate in Failover
    // =========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val catalogo = getCatalogo()
        if (catalogo.isEmpty()) return null

        // Seleziona unicamente gli stream in possesso di una rotta e di una catena di server
        val vociValide = catalogo.entries.filter { !it.value.routePath.isNullOrBlank() && !it.value.servers.isNullOrEmpty() }
        val selezioneCasuale = vociValide.shuffled().take(30)

        val movies = coroutineScope {
            selezioneCasuale.map { (id, entry) ->
                async {
                    val routePath = entry.routePath!!.trim()
                    val servers = entry.servers!!
                    val titoloPulito = pulisciTitolo(entry.title)
                    val customPoster = entry.poster?.trim()
                    
                    val posterFinale = if (!customPoster.isNullOrBlank()) {
                        customPoster
                    } else {
                        getTmdbPoster(titoloPulito)
                    }

                    // Impacchetta rotta e lista server per la transizione di riproduzione
                    val target = StreamTarget(titoloPulito, routePath, servers, posterFinale).toJson()

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
        val routePath: String,
        val servers: List<String>,
        val customPoster: String?,
        val score: Int
    )

    // =========================================================================
    // RICERCA GLOBALE INTEGRATA CON TMDB
    // =========================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val catalogo = getCatalogo()
        val risultatiMatch = mutableListOf<RisultatoMatch>()
        val q = query.trim()
        val isRicercaCodice = q.toIntOrNull() != null

        for ((id, entry) in catalogo) {
            val routePath = entry.routePath?.trim()
            val servers = entry.servers
            if (!routePath.isNullOrBlank() && !servers.isNullOrEmpty()) {
                val titoloPulito = pulisciTitolo(entry.title)
                val customPoster = entry.poster?.trim()
                
                val score = if (isRicercaCodice && id == q) {
                    200 
                } else {
                    calcolaPertinenza(titoloPulito, q)
                }

                if (score > 0) {
                    risultatiMatch.add(RisultatoMatch(titoloPulito, routePath, servers, customPoster, score))
                }
            }
        }

        val topResults = risultatiMatch.sortedByDescending { it.score }.take(40)

        return coroutineScope {
            topResults.map { match ->
                async {
                    val posterFinale = if (!match.customPoster.isNullOrBlank()) {
                        match.customPoster
                    } else {
                        getTmdbPoster(match.titolo)
                    }
                    val target = StreamTarget(match.titolo, match.routePath, match.servers, posterFinale).toJson()
                    
                    newMovieSearchResponse(match.titolo, target, TvType.Movie) {
                        this.posterUrl = posterFinale
                    }
                }
            }.awaitAll()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val target = tryParseJson<StreamTarget>(url) ?: return null
        
        return newMovieLoadResponse(target.title, url, TvType.Movie, url) {
            this.posterUrl = if (!target.poster.isNullOrBlank()) target.poster else defaultCover
            this.plot = "Flusso di streaming ridondante supportato da un'architettura a cascata (Waterfall) su ${target.servers.size} server indipendenti."
        }
    }

    // =========================================================================
    // GESTORE DI RETE: Esecuzione del Failover Multiplo
    // =========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val target = tryParseJson<StreamTarget>(data) ?: return false
        if (target.routePath.isBlank() || target.servers.isEmpty()) return false

        // INOLTRO A CASCATA: Invia in sequenza tutti i domini associati alla rotta.
        // ExoPlayer aprirà il primo link; se il servizio HTTP risponde con un codice di errore
        // dovuto all'esaurimento della banda, salterà automaticamente alla sorgente successiva.
        target.servers.forEachIndexed { index, host ->
            val hostPulito = host.trim().removeSuffix("/")
            val urlCompleto = "$hostPulito${target.routePath}"
            
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "Server Cascata #${index + 1}",
                    url = urlCompleto,
                    type = ExtractorLinkType.VIDEO
                )
            )
        }
        return true
    }
}
