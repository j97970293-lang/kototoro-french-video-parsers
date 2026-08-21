package org.skepsun.kototoro.parsers.site.fr

import okhttp3.HttpUrl
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
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseJson
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.EnumSet

/**
 * Recherche les fichiers de la catégorie Wikimedia Commons « Videos in French ».
 *
 * Les résultats restent liés à leur page de description et à leurs métadonnées de
 * licence. Le parser retourne exactement l’URL de fichier publiée par l’API
 * MediaWiki, sans extraction de lecteur tiers.
 */
@ContentSourceParser("WIKIMEDIA_COMMONS_FR_VIDEO", "Wikimedia Commons — vidéos françaises", "fr", type = ContentType.VIDEO)
internal class WikimediaCommonsFrenchVideo(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.WIKIMEDIA_COMMONS_FR_VIDEO, pageSize = PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("commons.wikimedia.org")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.RELEVANCE,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = false,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions(): ContentListFilterOptions = ContentListFilterOptions(
        availableStates = EnumSet.of(ContentState.FINISHED),
        availableContentTypes = EnumSet.of(ContentType.VIDEO),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        val query = filter.query?.trim()?.takeIf { it.isNotEmpty() }
        val response = webClient.httpGet(buildSearchUrl(query, offset), getRequestHeaders()).parseJson()
        val pages = response.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        return buildList {
            val keys = pages.keys().asSequence().toList().sorted()
            for (key in keys) {
                fileToContent(pages.optJSONObject(key))?.let(::add)
            }
        }
    }

    override suspend fun getDetails(manga: Content): Content {
        val file = fetchFile(manga.url) ?: return manga
        val chapter = fileToChapter(file)
        return fileToContent(file)?.copy(chapters = listOf(chapter)) ?: manga
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val file = fetchFile(chapter.url)
            ?: throw IllegalStateException("Le fichier Wikimedia Commons demandé est introuvable.")
        val info = file.optJSONArray("imageinfo")?.optJSONObject(0)
            ?: throw IllegalStateException("Les informations de fichier Wikimedia Commons sont indisponibles.")
        val fileUrl = info.optString("url").takeIf { it.startsWith("https://") }
            ?: throw IllegalStateException("Wikimedia Commons ne fournit pas d’URL de fichier HTTPS.")
        return listOf(
            ContentPage(
                id = generateUid("commons-page:$fileUrl"),
                url = fileUrl,
                preview = info.optString("thumburl").takeIf { it.startsWith("https://") },
                source = source,
            ),
        )
    }

    private suspend fun fetchFile(title: String): JSONObject? {
        if (!title.startsWith(FILE_PREFIX)) return null
        val response = webClient.httpGet(buildFileUrl(title), getRequestHeaders()).parseJson()
        return response.optJSONObject("query")?.optJSONObject("pages")?.let { pages ->
            pages.keys().asSequence().firstOrNull()?.let(pages::optJSONObject)
        }
    }

    private fun fileToContent(file: JSONObject?): Content? {
        file ?: return null
        val title = file.optString("title").takeIf { it.startsWith(FILE_PREFIX) } ?: return null
        val info = file.optJSONArray("imageinfo")?.optJSONObject(0)
        val metadata = info?.optJSONObject("extmetadata")
        val visibleTitle = title.removePrefix(FILE_PREFIX).substringBeforeLast('.').ifBlank { title }
        val filePage = FrenchVideoContract.commonsFilePageUrl(domain, title)
        return Content(
            id = generateUid("commons:$title"),
            title = visibleTitle,
            altTitles = emptySet(),
            url = title,
            publicUrl = filePage,
            rating = RATING_UNKNOWN,
            contentRating = ContentRating.SAFE,
            coverUrl = info?.optString("thumburl")?.takeIf { it.startsWith("https://") },
            tags = metadata.toTags(),
            state = ContentState.FINISHED,
            authors = setOf(WIKIMEDIA_AUTHOR),
            largeCoverUrl = info?.optString("thumburl")?.takeIf { it.startsWith("https://") },
            description = metadata?.optJSONObject("ImageDescription")?.optString("value")?.ifBlank { null },
            source = source,
        )
    }

    private fun fileToChapter(file: JSONObject): ContentChapter {
        val title = file.optString("title").takeIf { it.startsWith(FILE_PREFIX) }
            ?: throw IllegalArgumentException("Titre de fichier Wikimedia absent")
        val info = file.optJSONArray("imageinfo")?.optJSONObject(0)
        return ContentChapter(
            id = generateUid("commons-chapter:$title"),
            title = "Fichier source libre",
            number = 1f,
            volume = 0,
            url = title,
            scanlator = WIKIMEDIA_AUTHOR,
            uploadDate = info?.optString("timestamp")?.parseIsoTimestamp() ?: 0L,
            branch = FRENCH_LABEL,
            source = source,
        )
    }

    private fun JSONObject?.toTags(): Set<ContentTag> = buildSet {
        add(ContentTag(FRENCH_LABEL, "language-fr", source))
        this@toTags?.optJSONObject("LicenseShortName")?.optString("value")?.ifBlank { null }?.let { license ->
            add(ContentTag(license, "license:${license.lowercase()}", source))
        }
    }

    private fun buildSearchUrl(query: String?, offset: Int): String {
        val search = buildString {
            append("incategory:\\").append(CATEGORY).append("\\\"")
            query?.let { append(' ').append(it) }
        }.urlEncode()
        return "https://${domain.trim('/')}/w/api.php?action=query&format=json&generator=search" +
            "&gsrnamespace=6&gsrsearch=$search&gsrlimit=$pageSize&gsroffset=$offset" +
            "&prop=imageinfo&iiprop=url%7Cextmetadata&iiurlwidth=320"
    }

    private fun buildFileUrl(title: String): String =
        "https://${domain.trim('/')}/w/api.php?action=query&format=json&titles=${title.urlEncode()}" +
            "&prop=imageinfo&iiprop=url%7Cextmetadata&iiurlwidth=640"

    private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    private fun String.parseIsoTimestamp(): Long = runCatching {
        java.time.Instant.parse(this).toEpochMilli()
    }.getOrDefault(0L)

    private companion object {
        const val PAGE_SIZE = 20
        const val FILE_PREFIX = "File:"
        const val CATEGORY = "Videos in French"
        const val FRENCH_LABEL = "Français"
        const val WIKIMEDIA_AUTHOR = "Wikimedia Commons"
    }
}
