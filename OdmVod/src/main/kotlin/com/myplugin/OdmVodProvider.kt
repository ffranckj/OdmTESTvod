package com.odmvod.vod

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.net.URLEncoder

class OdmVodProvider : MainAPI() {
    override var mainUrl = "https://odmvod.org"
    override var name = "OdmVod"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        val response = app.get(url, headers = headers).text
        val document = Jsoup.parse(response)
        
        val movies = document.select("div.movie-card, div.item, article.post-item").mapNotNull {
            it.toSearchResult()
        }
        
        if (movies.isEmpty()) return null
        return newHomePageResponse("Recenti", movies)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8").toString()
        val searchUrl = "$mainUrl/search?q=$encodedQuery"
        val response = app.get(searchUrl, headers = headers).text
        val document = Jsoup.parse(response)
        
        return document.select("div.movie-card, div.item, article.post-item").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2, h3, .title, .post-title") ?: return null
        val title = titleElement.text().trim()
        val href = titleElement.selectFirst("a")?.attr("href") ?: this.selectFirst("a")?.attr("href") ?: return null
        
        val posterElement = this.selectFirst("img")
        val posterUrl = posterElement?.let { 
            it.attr("data-src").ifEmpty { it.attr("src") } 
        }
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url, headers = headers).text
        val document = Jsoup.parse(response)

        val title = document.selectFirst("h1, .entry-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst(".poster img, .movie-poster img")?.attr("src")
        val plot = document.selectFirst(".plot, .description, .movie-description")?.text()
        
        val yearText = document.select(".release-date, .info, .technical-specs").text()
        val year = Regex("\\d{4}").find(yearText)?.value?.toIntOrNull()
        
        val cast = document.select(".cast-list a, .actors a").map { it.text() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            addActors(cast)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val responseText = app.get(data, headers = headers).text
        val document = Jsoup.parse(responseText)

        var linkFound = false

        document.select(".video-js[data-setup], #play[data-setup]").forEach { element ->
            val dataSetupJson = element.attr("data-setup")
            if (dataSetupJson.isNotBlank()) {
                val ytMatch = Regex("""https?://(?:www\.)?youtube\.com/watch\?v=([^"\\'\s\}]+)""").find(dataSetupJson)
                if (ytMatch != null) {
                    val ytId = ytMatch.groupValues[1]
                    loadExtractor("https://www.youtube.com/watch?v=$ytId", data, subtitleCallback, callback)
                    linkFound = true
                } else {
                    val srcMatch = Regex("""src["']?\s*:\s*["'](https?://[^"'\s\}]+)""").find(dataSetupJson)
                    if (srcMatch != null) {
                        val mediaUrl = srcMatch.groupValues[1]
                        val isHls = mediaUrl.contains(".m3u8")
                        
                        // CORREZIONE BUILD: Parametri nativi estratti e riposizionati
                        callback.invoke(
                            ExtractorLink(
                                source = this.name,
                                name = "Server Diretto",
                                url = mediaUrl,
                                referer = data,
                                quality = getQualityFromName(mediaUrl),
                                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            )
                        )
                        linkFound = true
                    }
                }
            }
        }

        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("facebook")) {
                if (src.contains("youtube") || src.contains("youtu.be")) {
                    val ytId = Regex("""(?:embed/|v/|watch\?v=)([^"&?/\s]{11})""").find(src)?.groupValues?.get(1)
                    ytId?.let { id ->
                        loadExtractor("https://www.youtube.com/watch?v=$id", data, subtitleCallback, callback)
                        linkFound = true
                    }
                } else if (src.startsWith("http")) {
                    loadExtractor(src, data, subtitleCallback, callback)
                    linkFound = true
                }
            }
        }

        if (!linkFound) {
            val globalYtRegex = Regex("""(?:watch\?v=|embed/)([^"\\'&?/\s\}]{11})""")
            globalYtRegex.findAll(responseText).forEach { match ->
                val ytId = match.groupValues[1]
                loadExtractor("https://www.youtube.com/watch?v=$ytId", data, subtitleCallback, callback)
                linkFound = true
            }

            val directMediaRegex = Regex("""["'](https?://[^"'\s]+?\.(?:mp4|m3u8)[^"'\s]*?)["']""")
            directMediaRegex.findAll(responseText).forEach { match ->
                val mediaUrl = match.groupValues[1]
                val isHls = mediaUrl.contains(".m3u8")
                
                // CORREZIONE BUILD: Parametri allineati
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = "Flusso Globale",
                        url = mediaUrl,
                        referer = data,
                        quality = getQualityFromName(mediaUrl),
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
                linkFound = true
            }
        }

        return linkFound
    }
}
