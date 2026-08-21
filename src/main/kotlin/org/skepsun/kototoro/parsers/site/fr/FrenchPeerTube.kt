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
 * Catalogue de vidéos dont la langue déclarée par l’API PeerTube est le français.
 *
 * L’instance reste configurable par l’utilisateur. Ce parser ne contacte jamais un
 * annuaire, ne déchiffre aucun flux et restitue uniquement les URL fournies par l’API
 * publique de l’instance choisie.
 */
@ContentSourceParser("FRENCH_PEERTUBE", "PeerTube francophone", "fr", type = ContentType.VIDEO)
internal class FrenchPeerTube(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.FRENCH_PEERTUBE, pageSize = PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("framatube.org")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
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
        val start = (page - 1).coerceAtLeast(0) * pageSize
        val query = buildString {
            append("start=").append(start)
            append("&count=").append(pageSize)
            append("&sort=").append(sortParameter(order))
            append("&languageOneOf%5B%5D=fr")
            filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let { term ->
                append("&search=").append(term.urlEncode())
            }
        }
        val response = webClient.httpGet("${apiBase()}/api/v1/videos?$query", getRequestHeaders()).parseJson()
        val videos = response.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (index in 0 until videos.length()) {
                videoToContent(videos.optJSONObject(index))?.let(::add)
            }
        }
    }

    override suspend fun getDetails(manga: Content): Content {
        val video = fetchVideo(manga.url) ?: return manga
        val chapter = videoToChapter(video)
        return videoToContent(video)?.copy(
            description = video.optString("description").ifBlank { manga.description },
            chapters = listOf(chapter),
        ) ?: manga
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val video = fetchVideo(chapter.url)
            ?: throw IllegalStateException("La vidéo PeerTube demandée est introuvable.")
        val directUrl = FrenchVideoContract.selectPeerTubePlayback(video)
            ?: throw IllegalStateException("L’API PeerTube ne fournit pas de lien de lecture public pour cette vidéo.")
        return listOf(
            ContentPage(
                id = generateUid("peertube-page:$directUrl"),
                url = directUrl,
                preview = video.optString("thumbnailPath").toAbsoluteUrl(),
                source = source,
            ),
        )
    }

    private suspend fun fetchVideo(apiPath: String): JSONObject? {
        val path = apiPath.takeIf { it.startsWith("/api/v1/videos/") } ?: return null
        val video = webClient.httpGet("${apiBase()}$path", getRequestHeaders()).parseJson()
        return video.takeIf(FrenchVideoContract::isDeclaredFrench)
    }

    private fun videoToContent(video: JSONObject?): Content? {
        video ?: return null
        if (!FrenchVideoContract.isDeclaredFrench(video)) return null
        val uuid = video.optString("uuid").ifBlank { return null }
        val title = video.optString("name").ifBlank { return null }
        val publicUrl = video.optString("url").ifBlank {
            val shortUuid = video.optString("shortUUID").ifBlank { uuid }
            "${apiBase()}/w/$shortUuid"
        }
        return Content(
            id = generateUid("peertube:$uuid"),
            title = title,
            altTitles = emptySet(),
            url = "/api/v1/videos/$uuid",
            publicUrl = publicUrl,
            rating = RATING_UNKNOWN,
            contentRating = ContentRating.SAFE,
            coverUrl = video.optString("thumbnailPath").toAbsoluteUrl(),
            tags = video.toTags(),
            state = ContentState.FINISHED,
            authors = setOfNotNull(video.optJSONObject("account")?.optString("displayName")?.ifBlank { null }),
            largeCoverUrl = video.optString("thumbnailPath").toAbsoluteUrl(),
            description = video.optString("description").ifBlank { null },
            source = source,
        )
    }

    private fun videoToChapter(video: JSONObject): ContentChapter {
        val uuid = video.optString("uuid").ifBlank { throw IllegalArgumentException("UUID PeerTube absent") }
        return ContentChapter(
            id = generateUid("peertube-chapter:$uuid"),
            title = "Vidéo française",
            number = 1f,
            volume = 0,
            url = "/api/v1/videos/$uuid",
            scanlator = domain,
            uploadDate = video.optString("publishedAt").parseIsoTimestamp(),
            branch = FRENCH_LABEL,
            source = source,
        )
    }

    private fun JSONObject.toTags(): Set<ContentTag> = buildSet {
        add(ContentTag(FRENCH_LABEL, "language-fr", source))
        optJSONObject("category")?.optString("label")?.ifBlank { null }?.let { label ->
            add(ContentTag(label, "category:${label.lowercase()}", source))
        }
        optJSONObject("licence")?.optString("label")?.ifBlank { null }?.let { label ->
            add(ContentTag(label, "license:${label.lowercase()}", source))
        }
    }

    private fun String?.toAbsoluteUrl(): String? = this
        ?.takeIf { it.isNotBlank() }
        ?.let { path -> if (path.startsWith("http")) path else "${apiBase()}$path" }

    private fun apiBase(): String = domain.trim().let { configured ->
        if (configured.startsWith("https://") || configured.startsWith("http://")) {
            configured.trimEnd('/')
        } else {
            "https://${configured.trim('/') }"
        }
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    private fun String.parseIsoTimestamp(): Long = runCatching {
        java.time.Instant.parse(this).toEpochMilli()
    }.getOrDefault(0L)

    private fun sortParameter(order: SortOrder): String = when (order) {
        SortOrder.NEWEST -> "-createdAt"
        SortOrder.POPULARITY -> "-views"
        SortOrder.ALPHABETICAL -> "name"
        else -> "-publishedAt"
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val FRENCH_LABEL = "Français"
    }
}
