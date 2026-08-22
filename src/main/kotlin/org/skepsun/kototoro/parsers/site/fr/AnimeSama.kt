package org.skepsun.kototoro.parsers.site.fr

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.net.URI
import java.text.Normalizer
import java.util.EnumSet

@ContentSourceParser(
    name = "ANIME_SAMA",
    title = "Anime-Sama",
    locale = "fr",
    type = ContentType.VIDEO,
)
internal class AnimeSama(context: ContentLoaderContext) : PagedContentParser(
    context = context,
    source = ContentParserSource.valueOf("ANIME_SAMA"),
    pageSize = 24,
) {
    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("anime-sama.to")
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
        if (query.isBlank()) return emptyList()
        val response = runCatching {
            webClient.httpPost(
                "https://$domain/template-php/defaut/fetch.php".toHttpUrl(),
                mapOf("query" to query),
                getRequestHeaders(),
            )
        }.getOrNull() ?: return emptyList()
        return response.use { parseSearch(it.parseHtml()) }.take(pageSize)
    }

    private fun parseSearch(document: org.jsoup.nodes.Document): List<Content> {
        val seen = LinkedHashSet<String>()
        return document.select("a[href*='/catalogue/'], .asn-search-result-title").mapNotNull { element ->
            val anchor = if (element.tagName() == "a") element else element.closest("a[href]") ?: return@mapNotNull null
            val href = anchor.attrAsRelativeUrl("href")
            val slug = Regex("/catalogue/([^/?#]+)/?").find(href)?.groupValues?.getOrNull(1)
                ?: return@mapNotNull null
            if (!seen.add(slug)) return@mapNotNull null
            val title = element.text().trim().ifBlank { anchor.text().trim() }.ifBlank { slug.replace('-', ' ') }
            val image = anchor.selectFirst("img") ?: anchor.parent()?.selectFirst("img")
            val cover = image?.attrAsAbsoluteUrlOrNull("data-src") ?: image?.attrAsAbsoluteUrlOrNull("src")
            Content(
                id = generateUid("/catalogue/$slug/"),
                title = title,
                altTitles = emptySet(),
                url = "/catalogue/$slug/",
                publicUrl = "https://$domain/catalogue/$slug/",
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
    }

    override suspend fun getDetails(manga: Content): Content {
        val document = webClient.httpGet(manga.publicUrl, getRequestHeaders()).use { it.parseHtml() }
        val title = document.selectFirst("h1, #titreOeuvre, meta[property=og:title]")?.let { element ->
            if (element.tagName() == "meta") element.attr("content").trim() else element.text().trim()
        }?.takeIf { it.isNotBlank() } ?: manga.title
        val slug = Regex("/catalogue/([^/?#]+)/?").find(manga.publicUrl)?.groupValues?.getOrNull(1)
            ?: return manga.copy(title = title, chapters = emptyList())
        val seasons = linkedSetOf(1)
        document.select("a[href*='/saison']").forEach { anchor ->
            Regex("/saison(\\d+)").find(anchor.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?.takeIf { it > 0 }?.let(seasons::add)
        }
        val chapters = ArrayList<ContentChapter>()
        for (season in seasons.sorted()) {
            for (language in listOf("vf", "vostfr")) {
                val apiUrl = "https://$domain/catalogue/$slug/saison$season/$language/episodes.js"
                val js = fetchText(apiUrl) ?: continue
                val episodes = parseEpisodeUrls(js)
                episodes.indices.forEach { index ->
                    val number = index + 1
                    chapters += ContentChapter(
                        id = generateUid("$slug|$season|$language|$number"),
                        url = FrenchVideoSupport.payload(
                            pageUrl = manga.publicUrl,
                            apiUrl = apiUrl,
                            episode = number,
                        ),
                        title = "Saison $season · Épisode $number [$language]",
                        number = number.toFloat(),
                        uploadDate = 0L,
                        volume = season,
                        branch = language.uppercase(),
                        scanlator = null,
                        source = source,
                    )
                }
            }
        }
        val cover = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?.takeIf(FrenchVideoSupport::isHttpUrl) ?: manga.coverUrl
        val description = document.selectFirst("meta[name=description], .description, #synopsis")?.let { element ->
            if (element.tagName() == "meta") element.attr("content").trim() else element.text().trim()
        }
        return manga.copy(
            title = title,
            coverUrl = cover,
            largeCoverUrl = cover,
            description = description,
            chapters = chapters.distinctBy { it.url },
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val payload = FrenchVideoSupport.parsePayload(chapter.url) ?: return emptyList()
        val candidates = LinkedHashSet<String>()
        if (payload.apiUrl != null && payload.episode != null) {
            val js = fetchText(payload.apiUrl)
            parseEpisodeUrls(js.orEmpty()).getOrNull(payload.episode - 1)?.let(candidates::add)
        }
        if (candidates.isEmpty()) candidates.add(payload.pageUrl)
        val pages = ArrayList<ContentPage>()
        for (candidate in candidates) {
            if (FrenchVideoSupport.isDirectMedia(candidate)) {
                pages += FrenchVideoSupport.page(candidate, source, payload.pageUrl, FrenchVideoSupport.sourceName(candidate))
                continue
            }
            val document = runCatching {
                webClient.httpGet(candidate, getRequestHeaders()).use { it.parseHtml() }
            }.getOrNull() ?: continue
            FrenchVideoSupport.directMediaFromDocument(document).forEach { media ->
                pages += FrenchVideoSupport.page(media, source, candidate, FrenchVideoSupport.sourceName(candidate))
            }
            document.select("iframe[src], video source[src]").mapNotNull { it.attrAsAbsoluteUrlOrNull("src") }
                .filter(FrenchVideoSupport::isHttpUrl)
                .forEach { embedded ->
                    pages += FrenchVideoSupport.page(embedded, source, candidate, FrenchVideoSupport.sourceName(candidate))
                }
        }
        return pages.distinctBy { it.url }
    }

    override suspend fun getPageUrl(page: ContentPage): String = page.url

    private suspend fun fetchText(url: String): String? = runCatching {
        webClient.httpGet(url, getRequestHeaders()).use { response ->
            if (!response.isSuccessful) return@runCatching null
            response.parseRaw()
        }
    }.getOrNull()

    private fun parseEpisodeUrls(js: String): List<String> {
        val array = Regex("var\\s+eps\\d+\\s*=\\s*\\[([\\s\\S]*?)]", RegexOption.IGNORE_CASE)
            .find(js)?.groupValues?.getOrNull(1) ?: js
        return Regex("['\"](https?://[^'\"]+)['\"]")
            .findAll(array)
            .map { it.groupValues[1].replace("\\/", "/") }
            .distinct()
            .toList()
    }
}
