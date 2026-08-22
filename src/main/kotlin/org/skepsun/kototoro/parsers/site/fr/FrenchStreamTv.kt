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
import org.skepsun.kototoro.parsers.util.toAbsoluteUrl
import java.net.URI
import java.net.URLEncoder
import java.util.EnumSet

@ContentSourceParser(
    name = "FRENCH_STREAM_TV",
    title = "French-Stream TV",
    locale = "fr",
    type = ContentType.VIDEO,
)
internal class FrenchStreamTv(context: ContentLoaderContext) : PagedContentParser(
    context = context,
    source = ContentParserSource.valueOf("FRENCH_STREAM_TV"),
    pageSize = 30,
) {
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("fstv.rest")
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

    private val categories = listOf(
        "sport" to "Sport",
        "cinema" to "Cinéma",
        "generaliste" to "Généralistes",
        "enfants" to "Jeunesse",
        "documentaire" to "Documentaire",
        "musique" to "Musique",
        "information" to "Information",
    )

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
        val categoryItems = if (query.isBlank()) {
            listOf(categories[(page - 1).coerceIn(0, categories.lastIndex)])
        } else {
            categories
        }
        val results = ArrayList<Content>()
        for ((slug, _) in categoryItems) {
            val url = "https://$domain/index.php?category=${URLEncoder.encode(slug, "UTF-8")}&do=cat"
            val document = runCatching {
                webClient.httpGet(url, getRequestHeaders()).use { it.parseHtml() }
            }.getOrNull() ?: continue
            document.select("div.short, .short, article, .card").forEach { card ->
                val anchor = card.selectFirst("a.short-poster[href], a[href]") ?: return@forEach
                val href = anchor.attrAsRelativeUrl("href")
                if (href.isBlank()) return@forEach
                val image = card.selectFirst("img")
                val title = card.selectFirst(".short-title, h2, h3, .title")?.text()?.trim()
                    ?: image?.attrOrNull("alt")?.trim()
                    ?: anchor.text().trim()
                if (title.isBlank() || (query.isNotBlank() && !title.contains(query, ignoreCase = true))) return@forEach
                val cover = image?.attrAsAbsoluteUrlOrNull("data-src")
                    ?: image?.attrAsAbsoluteUrlOrNull("src")
                results += Content(
                    id = generateUid(href),
                    title = title,
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
            }
            if (query.isBlank() && results.size >= pageSize) break
        }
        return results.distinctBy { it.url }.take(pageSize)
    }

    override suspend fun getDetails(manga: Content): Content {
        val document = webClient.httpGet(manga.publicUrl, getRequestHeaders()).use { it.parseHtml() }
        val html = document.outerHtml()
        val title = Regex("window\\.FSTV_NAME\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: manga.title
        val chapter = ContentChapter(
            id = generateUid("${manga.url}|live"),
            url = JSONObject().put("page", manga.publicUrl).toString(),
            title = "Direct",
            number = 1f,
            uploadDate = 0L,
            volume = 0,
            branch = null,
            scanlator = null,
            source = source,
        )
        val cover = document.selectFirst("#posterImage, meta[property=og:image]")?.let { element ->
            if (element.tagName() == "meta") element.attr("content") else element.attrAsAbsoluteUrlOrNull("src")
        }?.takeIf(FrenchVideoSupport::isHttpUrl) ?: manga.coverUrl
        return manga.copy(
            title = title,
            coverUrl = cover,
            largeCoverUrl = cover,
            description = "Chaîne TV française en direct.",
            chapters = listOf(chapter),
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val pageUrl = runCatching { JSONObject(chapter.url).optString("page") }
            .getOrNull()?.takeIf(FrenchVideoSupport::isHttpUrl) ?: chapter.url
        val document = runCatching {
            webClient.httpGet(pageUrl, getRequestHeaders()).use { it.parseHtml() }
        }.getOrNull() ?: return emptyList()
        val html = document.outerHtml()
        val sourceUrl = Regex("window\\.FSTV_SRC\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .findAll(html).mapNotNull { it.groupValues.getOrNull(1)?.trim() }.lastOrNull()
            ?.replace("&amp;", "&")
            ?.let { URI(pageUrl).resolve(it).toString() }
            ?.takeIf(FrenchVideoSupport::isHttpUrl)
        val direct = sourceUrl?.takeIf(FrenchVideoSupport::isDirectMedia)
            ?: FrenchVideoSupport.directMediaFromDocument(document).firstOrNull()
        if (direct != null) {
            return listOf(FrenchVideoSupport.page(direct, source, pageUrl, "Direct HLS"))
        }
        if (sourceUrl != null) {
            return listOf(FrenchVideoSupport.page(sourceUrl, source, pageUrl, "Flux TV"))
        }
        return document.select("iframe[src]").mapNotNull { it.attrAsAbsoluteUrlOrNull("src") }
            .filter(FrenchVideoSupport::isHttpUrl)
            .map { FrenchVideoSupport.page(it, source, pageUrl, "Lecteur TV") }
            .distinctBy { it.url }
    }

    override suspend fun getPageUrl(page: ContentPage): String = page.url
}
