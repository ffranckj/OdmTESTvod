package com.telegram.vod // Mantieni inalterato il package originale del tuo progetto

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

// Definiamo i modelli dati esternamente per una perfetta compatibilità di compilazione
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

    // Immagine standard in assenza di copertina
    private val defaultCover = "https://placehold.co/500x750/222222/FFFFFF/png?text=Archivio+Cinema+Italiano"
    
    // URL Primario del file Raw
    private val databaseUrl = "https://gist.githubusercontent.com/ffranckj/d73933a36991f0ff223efa048937fdf1/raw/10524fce3b454e10782bb960ace377ab60de8dd5/catalogo.json"
    
    // URL di Fallback punta all'interfaccia Gist grezza nel caso il link raw dia 404
    private val fallbackUrl = "https://gist.github.com/ffranckj/d73933a36991f0ff223efa048937fdf1"
    
    private var catalogoCache: Map<String, CatalogoEntry>? = null

    // Inizializziamo un ObjectMapper personalizzato e permissivo
    private val mapper: ObjectMapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
    }

    // Funzione sicura per il fetch del JSON con controllo anti-404
    private suspend fun getCatalogo(): Map<String, CatalogoEntry> {
        catalogoCache?.let { return it }
        
        var jsonText = ""
        try {
            val response = app.get(databaseUrl)
            if (response.code == 200 && !response.text.contains("404: Not Found")) {
                jsonText = response.text
            }
        } catch (e: Exception) {
            // Ignora e tenta il fallback
        }

        // Se l'URL primario ha fallito o ha restituito 404, tentiamo il fallback sul documento sorgente
        if (jsonText.isBlank() || jsonText.contains("404")) {
            try {
                val fallbackDoc = app.get(fallbackUrl).document
                // Estrae il testo grezzo dalla tabella del codice Gist
                val codeBlock = fallbackDoc.selectFirst("table.highlight, .js-file-line-container")
                if (codeBlock != null) {
                    jsonText = codeBlock.text()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Se non abbiamo recuperato nulla, interrompiamo in modo sicuro
        if (jsonText.isBlank() || jsonText.contains("404: Not Found")) {
            return emptyMap()
        }

        return try {
            // Mappiamo il testo ripulito
            val map: Map<String, CatalogoEntry> = mapper.readValue(jsonText)
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
    // HOME PAGE: Popolata esclusivamente con i dati validi del JSON
    // =========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val catalogo = getCatalogo()
        if (catalogo.isEmpty()) return null

        val movies = mutableListOf<SearchResponse>()
        
        // Filtra chiavi valide e limita a 100 elementi per un caricamento grafico istantaneo
        val vociValide = catalogo.entries.filter { !it.value.streamUrl.isNullOrBlank() }.takeLast(100)

        for ((id, entry) in vociValide) {
            val streamUrl = entry.streamUrl!!.trim()
            val titoloPulito = pulisciTitolo(entry.title)
            
            // Passiamo l'oggetto serializzato
            val targetData = mapper.writeValueAsString(StreamTarget(titoloPulito, streamUrl))

            movies.add(newMovieSearchResponse(titoloPulito, targetData, TvType.Movie) {
                this.posterUrl = defaultCover
            })
        }

        if (movies.isEmpty()) return null
        return newHomePageResponse("Catalogo Gist", movies.reversed())
    }

    private data class RisultatoOrdinato(val response: SearchResponse, val score: Int)

    // =========================================================================
    // RICERCA GLOBALE SUL JSON
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
                    val targetData = mapper.writeValueAsString(StreamTarget(titoloPulito, streamUrl))
                    val resp = newMovieSearchResponse(titoloPulito, targetData, TvType.Movie) {
                        this.posterUrl = defaultCover
                    }
                    risultati.add(RisultatoOrdinato(resp, score))
                }
            }
        }

        return risultati.sortedByDescending { it.score }.map { it.response }
    }

    override suspend fun load(url: String): LoadResponse? {
        val target = try {
            mapper.readValue<StreamTarget>(url)
        } catch (e: Exception) {
            return null
        }
        
        return newMovieLoadResponse(target.title, url, TvType.Movie, target.streamUrl) {
            this.posterUrl = defaultCover
            this.plot = "Flusso video nativo estratto dal JSON remoto."
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
