package org.skepsun.kototoro.parsers.site.fr

import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.attrAsAbsoluteUrlOrNull
import org.skepsun.kototoro.parsers.util.attrAsRelativeUrl
import org.skepsun.kototoro.parsers.util.attrOrNull
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.parseRaw
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import org.skepsun.kototoro.parsers.util.toRelativeUrl
import java.net.URI
import java.net.URLEncoder
import java.util.EnumSet

@ContentSourceParser(
    name = "FRENCH_MANGA",
    title = "French-Manga",
    locale = "fr",
    type = ContentType.VIDEO,
)
internal class FrenchManga(context: ContentLoaderContext) : PagedContentParser(
    context = context,
    source = ContentParserSource.valueOf("FRENCH_MANGA"),
    pageSize = 18,
) {
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("w16.french-manga.net")
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = false,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
        availableContentTypes = EnumSet.of(ContentType.VIDEO),
    )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: ContentListFilter,
    ): List<Content> {
        val query = filter.query?.trim().orEmpty()
        val response = if (query.isNotBlank()) {
            webClient.httpPost(
                "https://$domain/engine/ajax/search.php".toHttpUrl(),
                mapOf("query" to query, "page" to page.toString()),
                getRequestHeaders(),
            )
        } else {
            val path = if (page <= 1) "/manga-streaming-1/" else "/manga-streaming-1/page/$page/"
            webClient.httpGet("https://$domain$path", getRequestHeaders())
        }

        return response.use { parseCards(it.parseHtml(), query.isNotBlank()) }
    }

    private fun parseCards(document: org.jsoup.nodes.Document, search: Boolean): List<Content> {
        val selector = if (search) ".search-item" else "div.short, .card, article"
        return document.select(selector).mapNotNull { card ->
            val anchor = card.selectFirst("a[href]") ?: return@mapNotNull null
            val href = when {
                anchor.attr("href").isNotBlank() -> anchor.attrAsRelativeUrl("href")
                card.attr("onclick").isNotBlank() -> Regex("location\\.href\\s*=\\s*['\"]([^'\"]+)")
                    .find(card.attr("onclick"))?.groupValues?.getOrNull(1)?.toRelativeUrl(domain).orEmpty()
                else -> ""
            }
            if (href.isBlank()) return@mapNotNull null
            val image = card.selectFirst("img")
            val rawTitle = card.selectFirst(".search-title, .short-title, h2, h3, .title")?.text()?.trim()
                ?: image?.attrOrNull("alt")?.removeSuffix(" affiche")?.trim()
                ?: anchor.text().trim()
            val title = rawTitle.replace(Regex("\\s*\\((?:19|20)\\d{2}\\)\\s*$"), "").trim()
                .takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cover = image?.attrAsAbsoluteUrlOrNull("data-src")
                ?: image?.attrAsAbsoluteUrlOrNull("data-original")
                ?: image?.attrAsAbsoluteUrlOrNull("src")
            Content(
                id = generateUid(href),
                title = baseTitle(title),
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.SAFE,
                coverUrl = cover,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                largeCoverUrl = cover,
                description = null,
                chapters = null,
                source = source,
            )
        }.distinctBy { it.url }.take(pageSize)
    }

    override suspend fun getDetails(manga: Content): Content {
        val document = webClient.httpGet(manga.publicUrl, getRequestHeaders()).use { it.parseHtml() }
        val title = document.selectFirst("#manga-data")?.attr("data-title")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1, meta[property=og:title]")?.let { element ->
                if (element.tagName() == "meta") element.attr("content").trim() else element.text().trim()
            }?.takeIf { it.isNotBlank() }
            ?: manga.title
        val data = document.selectFirst("#manga-data")
        val newsId = data?.attr("data-newsid")?.trim()?.takeIf { it.isNotBlank() }
            ?: findNewsId(manga.publicUrl)
        val description = document.selectFirst(".fdesc, .detail-desc, meta[name=description]")?.let { element ->
            if (element.tagName() == "meta") element.attr("content").trim() else element.text().trim()
        }
        val cover = data?.attr("data-affiche")?.trim()?.takeIf(FrenchVideoSupport::isHttpUrl)
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: manga.coverUrl
        val episodeApi = newsId?.let { "https://$domain/engine/ajax/manga_episodes_api.php?id=$it" }
        val episodeRoot = episodeApi?.let { fetchJson(it) }
        val episodes: List<ContentChapter> = episodeRoot?.let { root ->
            FrenchVideoSupport.episodeNumbers(root).map { number ->
                val episodeTitle = root.optJSONObject("info")?.optJSONObject(number.toString())
                    ?.optString("title")?.takeIf { it.isNotBlank() }
                    ?: "Épisode $number"
                ContentChapter(
                    id = generateUid("${manga.url}|$number"),
                    url = FrenchVideoSupport.payload(manga.publicUrl, episodeApi, number),
                    title = episodeTitle,
                    number = number.toFloat(),
                    uploadDate = 0L,
                    volume = 0,
                    branch = null,
                    scanlator = null,
                    source = source,
                )
            }
        }.orEmpty()
        val finalChapters = episodes.ifEmpty {
            listOf(
                ContentChapter(
                    id = generateUid("${manga.url}|video"),
                    url = FrenchVideoSupport.payload(manga.publicUrl, null, null),
                    title = "Vidéo",
                    number = 1f,
                    uploadDate = 0L,
                    volume = 0,
                    branch = null,
                    scanlator = null,
                    source = source,
                ),
            )
        }
        return manga.copy(
            title = baseTitle(title),
            coverUrl = cover,
            largeCoverUrl = cover,
            description = description,
            chapters = finalChapters,
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val payload = FrenchVideoSupport.parsePayload(chapter.url)
            ?: FrenchVideoPayload(chapter.url, null, null)
        val candidates = LinkedHashSet<String>()
        if (payload.apiUrl != null) {
            fetchJson(payload.apiUrl)?.let { root ->
                candidates.addAll(FrenchVideoSupport.candidateUrls(root, payload.episode))
            }
        }
        candidates.add(payload.pageUrl)

        val pages = ArrayList<ContentPage>()
        for (candidate in candidates) {
            if (FrenchVideoSupport.isDirectMedia(candidate)) {
                pages += FrenchVideoSupport.page(candidate, source, payload.pageUrl, FrenchVideoSupport.sourceName(candidate))
                continue
            }
            val document = runCatching {
                webClient.httpGet(candidate, getRequestHeaders()).use { it.parseHtml() }
            }.getOrNull() ?: continue
            val direct = FrenchVideoSupport.directMediaFromDocument(document)
            if (direct.isNotEmpty()) {
                direct.forEach { url ->
                    pages += FrenchVideoSupport.page(url, source, candidate, FrenchVideoSupport.sourceName(candidate))
                }
            } else {
                document.select("iframe[src]").mapNotNull { it.attrAsAbsoluteUrlOrNull("src") }
                    .filter(FrenchVideoSupport::isHttpUrl)
                    .forEach { iframe ->
                        pages += FrenchVideoSupport.page(iframe, source, candidate, FrenchVideoSupport.sourceName(candidate))
                    }
            }
        }
        return pages.distinctBy { it.url }
    }

    override suspend fun getPageUrl(page: ContentPage): String = page.url

    private suspend fun fetchJson(url: String): JSONObject? = runCatching {
        webClient.httpGet(url, getRequestHeaders()).use { response ->
            if (!response.isSuccessful) return@runCatching null
            JSONObject(response.parseRaw())
        }
    }.getOrNull()

    private fun findNewsId(url: String): String? = Regex("(?:[?&]newsid=|/)(\\d+)(?:-|\\.html)")
        .find(url)?.groupValues?.getOrNull(1)

    private fun baseTitle(value: String): String = value
        .replace(Regex("\\s*[-–—]?\\s*Saison\\s+\\d+.*$", RegexOption.IGNORE_CASE), "")
        .trim()
}
