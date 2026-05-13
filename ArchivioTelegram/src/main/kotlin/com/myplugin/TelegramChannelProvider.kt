package com.telegram.vod // Mantieni inalterato il package originale del tuo progetto

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

// 1. CLASSI PUBBLICHE ESTERNE: Fondamentali per evitare che Jackson/R8 blocchino la decodifica JSON
data class CatalogoEntry(
    @JsonProperty("streamUrl") val streamUrl: String?,
    @JsonProperty("title") val title: String?
)

data class StreamTarget(
    @JsonProperty("title") val title: String,
    @JsonProperty("streamUrl") val streamUrl: String
)

class TelegramChannelProvider : MainAPI() {
    override var mainUrl = "https://t.me/s/archiviocinemaitaliano"
    override var name = "Archivio Cinema Italiano"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    // Immagine segnaposto predefinita
    private val defaultCover = "https://placehold.co/500x750/222222/FFFFFF/png?text=Archivio+Cinema+Italiano"
    
    // SORGENTE UNICA: Il tuo catalogo JSON su Gist
    private val databaseUrl = "https://gist.githubusercontent.com/ffranckj/d73933a36991f0ff223efa048937fdf1/raw/catalogo.json"
    
    // Cache in memoria per evitare di scaricare il file remoto a ogni singola operazione
    private var catalogoCache: Map<String, CatalogoEntry>? = null

    // Scarica il JSON solo se non è già presente in cache
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

    // Pulisce la stringa testuale da formattazioni residue del dizionario
    private fun pulisciTitolo(titoloGrezzo: String?): String {
        if (titoloGrezzo.isNullOrBlank()) return "Film Senza Titolo"
        return titoloGrezzo.replace("*", "")
                           .replace("`", "")
                           .trim()
    }

    // Ordinamento di pertinenza per la ricerca
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
    // HOME PAGE: Carica gli ultimi 100 inserimenti per un rendering istantaneo
    // =========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val catalogo = getCatalogo()
        if (catalogo.isEmpty()) return null

        val movies = mutableListOf<SearchResponse>()
        
        // Estraiamo tutte le chiavi valide
        val vociValide = catalogo.entries.filter { !it.value.streamUrl.isNullOrBlank() }
        
        // Prendiamo solo le ultime 100 voci per evitare il blocco grafico dell'emulatore Android
        val ultimeVoci = vociValide.takeLast(100)

        for ((id, entry) in ultimeVoci) {
            val streamUrl = entry.streamUrl!!.trim()
            val titoloPulito = pulisciTitolo(entry.title)
            
            val target = StreamTarget(titoloPulito, streamUrl).toJson()

            movies.add(newMovieSearchResponse(titoloPulito, target, TvType.Movie) {
                this.posterUrl = defaultCover
            })
        }

        if (movies.isEmpty()) return null
        
        // Mostriamo la riga in Home ordinata a comparsa dal più recente
        return newHomePageResponse("Ultimi Arrivi", movies.reversed())
    }

    private data class RisultatoOrdinato(val response: SearchResponse, val score: Int)

    // =========================================================================
    // RICERCA: Esplora istantaneamente l'intero database di 3660 film in cache
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

        return risultati.sortedByDescending { it.score }.map { it.response }
    }

    // =========================================================================
    // CARICAMENTO SCHEDA E FLUSSO STREAMING
    // =========================================================================
    override suspend fun load(url: String): LoadResponse? {
        val target = tryParseJson<StreamTarget>(url) ?: return null
        
        return newMovieLoadResponse(target.title, url, TvType.Movie, target.streamUrl) {
            this.posterUrl = defaultCover
            this.plot = "Flusso streaming diretto prelevato dal catalogo JSON sorgente."
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
