package com.telegram.vod // Mantieni inalterato il package del tuo progetto

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

// Classi dati esterne per garantire la corretta decodifica nativa di Jackson
data class CatalogoEntry(
    @JsonProperty("streamUrl") val streamUrl: String?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("poster") val poster: String? // Supporto opzionale per copertine custom
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

    // Indirizzo Raw sorgente del JSON su Gist
    private val databaseUrl = "https://gist.githubusercontent.com/ffranckj/d73933a36991f0ff223efa048937fdf1/raw/catalogo.json"
    
    // Cache in memoria per mantenere l'accesso istantaneo ai dati
    private var catalogoCache: Map<String, CatalogoEntry>? = null

    // Fetch del catalogo con gestione sicura degli errori
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

    // Depurazione del titolo da formattazioni residue Markdown
    private fun pulisciTitolo(titoloGrezzo: String?): String {
        if (titoloGrezzo.isNullOrBlank()) return "Film Senza Titolo"
        return titoloGrezzo.replace("*", "")
                           .replace("`", "")
                           .trim()
    }

    // Motore di calcolo pertinenza per la ricerca testuale
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
    // HOME PAGE: Genera 30 consigli casuali a ogni ricaricamento
    // =========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val catalogo = getCatalogo()
        if (catalogo.isEmpty()) return null

        val movies = mutableListOf<SearchResponse>()
        
        // Filtra solo le entità del catalogo provviste di flussi di rete validi
        val vociValide = catalogo.entries.filter { !it.value.streamUrl.isNullOrBlank() }
        
        // ROTAZIONE CASUALE: Mescola l'elenco e preleva esattamente 30 elementi
        val selezioneCasuale = vociValide.shuffled().take(30)

        for ((id, entry) in selezioneCasuale) {
            val streamUrl = entry.streamUrl!!.trim()
            val titoloPulito = pulisciTitolo(entry.title)
            val customPoster = entry.poster?.trim()
            
            val target = StreamTarget(titoloPulito, streamUrl, customPoster).toJson()

            movies.add(newMovieSearchResponse(titoloPulito, target, TvType.Movie) {
                // Se il JSON fornisce un poster personalizzato lo usa, 
                // altrimenti lascia null delegando il fetch automatico a TMDB nativo.
                if (!customPoster.isNullOrBlank()) {
                    this.posterUrl = customPoster
                }
            })
        }

        if (movies.isEmpty()) return null
        
        return newHomePageResponse("Film Consigliati (Casuali)", movies)
    }

    private data class RisultatoOrdinato(val response: SearchResponse, val score: Int)

    // =========================================================================
    // RICERCA: Accesso globale e istantaneo all'indice completo
    // =========================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val catalogo = getCatalogo()
        val risultati = mutableListOf<RisultatoOrdinato>()
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
                    val target = StreamTarget(titoloPulito, streamUrl, customPoster).toJson()
                    val resp = newMovieSearchResponse(titoloPulito, target, TvType.Movie) {
                        if (!customPoster.isNullOrBlank()) {
                            this.posterUrl = customPoster
                        }
                    }
                    risultati.add(RisultatoOrdinato(resp, score))
                }
            }
        }

        return risultati.sortedByDescending { it.score }.map { it.response }
    }

    // =========================================================================
    // PLAYER E GESTIONE FLUSSI
    // =========================================================================
    override suspend fun load(url: String): LoadResponse? {
        val target = tryParseJson<StreamTarget>(url) ?: return null
        
        return newMovieLoadResponse(target.title, url, TvType.Movie, target.streamUrl) {
            if (!target.poster.isNullOrBlank()) {
                this.posterUrl = target.poster
            }
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
