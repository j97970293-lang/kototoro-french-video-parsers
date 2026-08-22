package org.skepsun.kototoro.parsers.site.fr

import org.json.JSONObject
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
import java.net.URLEncoder
import java.util.EnumSet

@ContentSourceParser(
    name = "FRENCH_STREAM",
    title = "French-Stream",
    locale = "fr",
    type = ContentType.VIDEO,
)
internal class FrenchStream(context: ContentLoaderContext) : PagedContentParser(
    context = context,
    source = ContentParserSource.valueOf("FRENCH_STREAM"),
    pageSize = 24,
) {
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("french-stream.one")
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
        val path = if (query.isNotBlank()) {
            "/?do=search&subaction=search&story=${URLEncoder.encode(query, "UTF-8")}&page=$page"
        } else {
            if (page <= 1) "/films/" else "/films/page/$page"
        }
        val document = webClient.httpGet("https://$domain$path", getRequestHeaders()).use { it.parseHtml() }
        return document.select("div.short, .short, article, .card").mapNotNull { card ->
            val anchor = card.selectFirst("a.short-poster[href], a[href]") ?: return@mapNotNull null
            val href = anchor.attrAsRelativeUrl("href")
            if (href.isBlank()) return@mapNotNull null
            val image = card.selectFirst("img")
            val title = anchor.attr("title").takeIf { it.isNotBlank() }
                ?: card.selectFirst(".short-title, h2, h3, .title")?.text()?.trim()
                ?: image?.attrOrNull("alt")?.trim()
                ?: anchor.text().trim()
            if (title.isBlank()) return@mapNotNull null
            val cover = image?.attrAsAbsoluteUrlOrNull("data-src")
                ?: image?.attrAsAbsoluteUrlOrNull("src")
            Content(
                id = generateUid(href),
                title = cleanTitle(title),
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
        val title = document.selectFirst("h1#s-title, h1, meta[property=og:title]")?.let { element ->
            if (element.tagName() == "meta") element.attr("content").trim() else element.text().trim()
        }?.takeIf { it.isNotBlank() } ?: manga.title
        val id = extractId(manga.publicUrl)
        val series = document.select(".episodes-wrapper, #serie-config, #sv-cfg, [data-type=serie], [data-type=series]").isNotEmpty() ||
            title.contains("saison", ignoreCase = true)
        val apiUrl = id?.let { if (series) "https://$domain/ep-data.php?id=$it" else "https://$domain/engine/ajax/film_api.php?id=$it" }
        val root = apiUrl?.let { fetchJson(it) }
        val chapters = if (series && root != null) {
            FrenchVideoSupport.episodeNumbers(root).map { number ->
                ContentChapter(
                    id = generateUid("${manga.url}|$number"),
                    url = FrenchVideoSupport.payload(manga.publicUrl, apiUrl, number),
                    title = "Épisode $number",
                    number = number.toFloat(),
                    uploadDate = 0L,
                    volume = 0,
                    branch = null,
                    scanlator = null,
                    source = source,
                )
            }
        } else {
            listOf(
                ContentChapter(
                    id = generateUid("${manga.url}|video"),
                    url = FrenchVideoSupport.payload(manga.publicUrl, apiUrl, null),
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
        val cover = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?.takeIf(FrenchVideoSupport::isHttpUrl) ?: manga.coverUrl
        val description = document.selectFirst(".fdesc, #s-desc, meta[name=description]")?.let { element ->
            if (element.tagName() == "meta") element.attr("content").trim() else element.text().trim()
        }
        return manga.copy(
            title = cleanTitle(title),
            coverUrl = cover,
            largeCoverUrl = cover,
            description = description,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val payload = FrenchVideoSupport.parsePayload(chapter.url)
            ?: FrenchVideoPayload(chapter.url, null, null)
        val candidates = LinkedHashSet<String>()
        payload.apiUrl?.let { api ->
            fetchJson(api)?.let { root -> candidates.addAll(FrenchVideoSupport.candidateUrls(root, payload.episode)) }
        }
        candidates.add(payload.pageUrl)
        val pages = ArrayList<ContentPage>()
        candidates.forEach { candidate ->
            if (FrenchVideoSupport.isDirectMedia(candidate)) {
                pages += FrenchVideoSupport.page(candidate, source, payload.pageUrl, FrenchVideoSupport.sourceName(candidate))
                return@forEach
            }
            val document = runCatching {
                webClient.httpGet(candidate, getRequestHeaders()).use { it.parseHtml() }
            }.getOrNull() ?: return@forEach
            FrenchVideoSupport.directMediaFromDocument(document).forEach { media ->
                pages += FrenchVideoSupport.page(media, source, candidate, FrenchVideoSupport.sourceName(candidate))
            }
            document.select("iframe[src]").mapNotNull { it.attrAsAbsoluteUrlOrNull("src") }
                .filter(FrenchVideoSupport::isHttpUrl)
                .forEach { iframe ->
                    pages += FrenchVideoSupport.page(iframe, source, candidate, FrenchVideoSupport.sourceName(candidate))
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

    private fun extractId(url: String): String? = Regex("[?&]newsid=(\\d+)").find(url)?.groupValues?.getOrNull(1)
        ?: Regex("/(\\d+)-[^/]+\\.html").find(url)?.groupValues?.getOrNull(1)

    private fun cleanTitle(value: String): String = value
        .replace(Regex("\\s*[-–—]?\\s*Saison\\s+\\d+.*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
