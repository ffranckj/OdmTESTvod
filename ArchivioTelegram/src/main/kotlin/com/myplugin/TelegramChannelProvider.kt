package com.telegram.vod // Mantieni il package corretto del tuo modulo

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class TelegramChannelProvider : MainAPI() {
    // Indirizzo nominale di facciata richiesto dalla struttura di Cloudstream
    override var mainUrl = "https://t.me/s/archiviocinemaitaliano"
    override var name = "Archivio Cinema Italiano"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    // Copertina generica di layout
    private val defaultCover = "https://placehold.co/500x750/222222/FFFFFF/png?text=Archivio+Cinema+Italiano"
    
    // L'UNICA VERA SORGENTE DATI: Il tuo JSON live su GitHub Gist
    private val databaseUrl = "https://gist.githubusercontent.com/ffranckj/d73933a36991f0ff223efa048937fdf1/raw/catalogo.json"
    
    // Struttura dati per mappare gli oggetti interni del tuo JSON
    private data class CatalogoEntry(
        @JsonProperty("streamUrl") val streamUrl: String?,
        @JsonProperty("title") val title: String?
    )
    
    // Struttura di transito per passare in modo compatto Titolo e Link al Player
    private data class StreamTarget(
        @JsonProperty("title") val title: String,
        @JsonProperty("streamUrl") val streamUrl: String
    )

    // Funzione helper per ripulire al volo i titoli da eventuali asterischi o backtick rimasti nel JSON
    private fun pulisciTitolo(titoloGrezzo: String?): String {
        if (titoloGrezzo.isNullOrBlank()) return "Film Senza Titolo"
        return titoloGrezzo.replace("*", "")
                           .replace("`", "")
                           .trim()
    }

    // Scarica e mappa in memoria ESCLUSIVAMENTE il file JSON
    private suspend fun getCatalogo(): Map<String, CatalogoEntry> {
        return try {
            val response = app.get(databaseUrl).text
            parseJson<Map<String, CatalogoEntry>>(response)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // Calcolo pertinenza per ordinare la ricerca
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
    // 1. HOME PAGE: Popolata unicamente scorrendo le voci del JSON
    // =========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val catalogo = getCatalogo()
        if (catalogo.isEmpty()) return null

        val movies = mutableListOf<SearchResponse>()

        // Scorre ogni singolo film presente nel tuo JSON
        for ((id, entry) in catalogo) {
            val streamUrl = entry.streamUrl?.trim()
            val rawTitle = entry.title

            if (!streamUrl.isNullOrBlank()) {
                val titoloPulito = pulisciTitolo(rawTitle)
                
                // Salviamo i dati per la fase di riproduzione
                val target = StreamTarget(titoloPulito, streamUrl).toJson()

                movies.add(newMovieSearchResponse(titoloPulito, target, TvType.Movie) {
                    this.posterUrl = defaultCover
                })
            }
        }

        if (movies.isEmpty()) return null
        
        // Mostra l'elenco in Home (invertito per mostrare in cima gli ultimi inseriti)
        return newHomePageResponse("Catalogo Live", movies.reversed())
    }

    // Struttura di supporto per ordinare i risultati di ricerca
    private data class RisultatoOrdinato(val response: SearchResponse, val score: Int)

    // =========================================================================
    // 2. RICERCA: Interroga direttamente le chiavi e i titoli del JSON
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
                
                // Assegna priorità massima se cerchi per ID esatto, altrimenti pertinenza sul titolo
                val score = if (isRicercaCodice && id == q) {
                    200 
                } else {
                    calcolaPertinenza(titoloPulito, q)
                }

                if (score > 0) {
                    val target = StreamTarget(titoloPulito, streamUrl).toJson()
                    val resp = newMovieSearchResponse(titoloPulito, target, TvType.Movie) {
                        this.posterUrl = defaultCover
                    }
                    risultati.add(RisultatoOrdinato(resp, score))
                }
            }
        }

        // Restituisce l'elenco ordinato dal più pertinente al meno pertinente
        return risultati.sortedByDescending { it.score }.map { it.response }
    }

    // =========================================================================
    // 3. CARICAMENTO SCHEDA E FLUSSO STREAMING
    // =========================================================================
    override suspend fun load(url: String): LoadResponse? {
        val target = tryParseJson<StreamTarget>(url) ?: return null
        
        return newMovieLoadResponse(target.title, url, TvType.Movie, target.streamUrl) {
            this.posterUrl = defaultCover
            this.plot = "In riproduzione diretta dal catalogo JSON sorgente."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // I link in arrivo sono già le stringhe Render pure ed esatte lette dal JSON
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
