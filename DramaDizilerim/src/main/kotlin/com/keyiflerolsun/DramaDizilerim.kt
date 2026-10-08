// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class DramaDizilerim : MainAPI() {
    override var mainUrl        = "https://dramadizilerim.com"
    override var name           = "DramaDizilerim"
    override val hasMainPage    = true
    override var lang           = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/dizi"                                              to "Son Eklenenler",
        "${mainUrl}/dizi?sort=views"                                   to "En Çok İzlenenler",
        "${mainUrl}/p/dramabox"                                        to "DramaBox",
        "${mainUrl}/p/reelshort"                                       to "ReelShort",
        "${mainUrl}/p/dramawave"                                       to "DramaWave",
        "${mainUrl}/p/netshort"                                        to "NetShort",
        "${mainUrl}/p/flextv"                                          to "FlexTV",
        "${mainUrl}/p/goodshort"                                       to "GoodShort",
        "${mainUrl}/p/shortmax"                                        to "ShortMax",
        "${mainUrl}/p/storyreel"                                       to "StoryReel"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Sayfalama: dizi listesi ?page=N şeklinde çalışıyor
        val url = if (request.data.contains("/p/")) {
            // Platform sayfaları sayfalama olmayabilir, ilerisi için
            request.data
        } else {
            "${request.data}?page=${page}"
        }

        val document = app.get(url).document
        val home = document.select("a.standard-video-card, a.wp-hcard").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst(".std-card-title, .wp-hcard p")?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Sitenin API endpoint'ini kullan - JSON döner, HTML parse'dan çok daha hızlı
        val apiUrl  = "${mainUrl}/api/search?q=${query}&lang=tr"
        val results = mutableListOf<SearchResponse>()

        try {
            val response = app.get(apiUrl)
            val json     = response.parsedSafe<List<DramaSearchResult>>()
            json?.forEach { item ->
                val href  = "${mainUrl}/dizi/${item.slug}"
                results.add(
                    newTvSeriesSearchResponse(item.title, href, TvType.TvSeries) {
                        this.posterUrl = item.poster
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("DDZ", "API arama hatası, HTML fallback: ${e.message}")
            // Fallback: HTML arama sayfası
            val document = app.get("${mainUrl}/search?q=${query}").document
            document.select("a.standard-video-card").mapNotNull {
                it.toMainPageResult()
            }.also { results.addAll(it) }
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // --- Ana bilgiler ---
        val title       = document.selectFirst("div.wp-info h1, .wp-title")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
                ?.replace(" - Full İzle (Tüm Bölümler Tek Parça) | Drama Dizilerim", "")
                ?.trim()
            ?: return null

        val poster      = fixUrlNull(
            document.selectFirst("div.wp-poster img")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        )

        val description = document.selectFirst(".wp-desc p, .wp-description")?.text()?.trim()
            ?: document.selectFirst("meta[name='description']")?.attr("content")?.trim()

        val tags        = document.select("a[href*='/genre/'], a[href*='/tur/'], .wp-genres a").map { it.text().trim() }
        val year        = document.selectFirst("a[href*='/yil/'], .wp-year")?.text()?.trim()?.toIntOrNull()
        val rating      = document.selectFirst(".wp-imdb span, span.imdb-score")?.text()?.trim()?.toRatingInt()

        // --- Bölüm listesi ---
        // Bölüm kartları: <a class="wp-ecard" href="/izle/{slug}?s=1&e=5" data-season="1" data-episode="5">
        val episodeList = mutableListOf<Episode>()
        document.select("a.wp-ecard[href]").forEach { el ->
            val epHref    = fixUrlNull(el.attr("href")) ?: return@forEach
            val epSeason  = el.attr("data-season").toIntOrNull() ?: 1
            val epEpisode = el.attr("data-episode").toIntOrNull()
            val epTitle   = el.selectFirst(".wp-etitle, .wp-enum")?.text()?.trim() ?: "Bölüm $epEpisode"
            val epThumb   = fixUrlNull(el.selectFirst("img")?.attr("src"))

            episodeList.add(newEpisode(epHref) {
                this.name       = epTitle
                this.season     = epSeason
                this.episode    = epEpisode
                this.posterUrl  = epThumb
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
            this.posterUrl   = poster
            this.plot        = description
            this.year        = year
            this.tags        = tags
            this.rating      = rating
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DDZ", "loadLinks data » $data")

        val document = app.get(data).document

        // İzle sayfasında iki tip player vardır:
        //   1) İlk bölüm: <iframe src="https://dramadizilerim.com/embed.php?...">
        //   2) Diğer bölümler: <div class="lazy-player" data-src="https://dramadizilerim.com/embed.php?...">
        // Her iki durumda da embed.php URL'sini alıp sayfayı parse ederiz.

        val embedUrls = mutableListOf<String>()

        // 1) iframe embed URL'si
        document.select("div.player-wrap iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotEmpty()) embedUrls.add(fixUrl(src))
        }

        // 2) lazy-player data-src URL'si
        document.select("div.lazy-player[data-src]").forEach { div ->
            val src = div.attr("data-src").trim()
            if (src.isNotEmpty()) embedUrls.add(fixUrl(src))
        }

        if (embedUrls.isEmpty()) {
            Log.w("DDZ", "Embed URL bulunamadı, sayfa: $data")
            return false
        }

        // İlk geçerli embed URL'sini işle (şu anki bölüm)
        val embedUrl = embedUrls.first()
        Log.d("DDZ", "embed URL » $embedUrl")

        return try {
            val embedDoc = app.get(embedUrl, referer = data).document
            val scriptText = embedDoc.select("script").joinToString("\n") { it.html() }

            // Örnek: let source = "https://ns-aws-cdn.netshort.com/....mp4?..."
            // veya:  let source = "https://...m3u8?..."
            val sourceRegex = Regex("""let\s+source\s*=\s*"(https?://[^"]+)"""")
            val match = sourceRegex.find(scriptText)

            if (match != null) {
                val videoUrl = match.groupValues[1]
                Log.d("DDZ", "video URL » $videoUrl")

                val isM3U8 = videoUrl.contains("m3u8", ignoreCase = true)
                callback.invoke(
                    ExtractorLink(
                        source  = name,
                        name    = name,
                        url     = videoUrl,
                        referer = embedUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8  = isM3U8
                    )
                )
                true
            } else {
                Log.w("DDZ", "video URL regex eşleşmedi, embed sayfası parse hatası")
                false
            }
        } catch (e: Exception) {
            Log.e("DDZ", "embed parse hatası: ${e.message}")
            false
        }
    }

    // --- Yardımcı veri sınıfı (API JSON için) ---
    data class DramaSearchResult(
        val slug    : String,
        val title   : String,
        val poster  : String?,
        val year    : String?,
        val type    : String?
    )
}
