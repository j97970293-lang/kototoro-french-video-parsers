package org.skepsun.kototoro.parsers.site.fr

import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.util.toRelativeUrl
import org.jsoup.nodes.Document
import java.net.URI
import java.util.Locale

internal data class FrenchVideoPayload(
    val pageUrl: String,
    val apiUrl: String?,
    val episode: Int?,
)

internal object FrenchVideoSupport {
    private val mediaRegex = Regex(
        """https?://[^\s\"'<>\\\\]+?\.(?:m3u8|mp4)(?:\?[^\s\"'<>\\\\]*)?""",
        RegexOption.IGNORE_CASE,
    )
    private val knownHosterRegex = Regex(
        """(fsvid|vidzy|uqload|dood|voe|vidmoly|filemoon|streamtape|vidoza|sibnet|sendvid|ok\.ru|mymail|lulu|luluvdo|kokoflix|bysebuho|embedseek|embed4me|mixdrop|vidara|streamix)""",
        RegexOption.IGNORE_CASE,
    )

    fun payload(pageUrl: String, apiUrl: String?, episode: Int?): String {
        return JSONObject()
            .put("page", pageUrl)
            .put("api", apiUrl ?: JSONObject.NULL)
            .put("episode", episode ?: JSONObject.NULL)
            .toString()
    }

    fun parsePayload(value: String): FrenchVideoPayload? {
        val root = runCatching { JSONObject(value) }.getOrNull() ?: return null
        val page = root.optString("page").takeIf { isHttpUrl(it) } ?: return null
        val api = root.optString("api").takeIf { isHttpUrl(it) }
        val episode = root.optInt("episode", -1).takeIf { it > 0 }
        return FrenchVideoPayload(page, api, episode)
    }

    fun isHttpUrl(value: String): Boolean =
        value.startsWith("https://") || value.startsWith("http://")

    fun isDirectMedia(value: String): Boolean = mediaRegex.matches(value.trim())

    fun directMediaFromHtml(html: String): List<String> {
        val normalized = html.replace("\\/", "/").replace("&amp;", "&")
        return mediaRegex.findAll(normalized).map { it.value }.distinct().toList()
    }

    fun directMediaFromDocument(document: Document): List<String> {
        val urls = LinkedHashSet<String>()
        urls.addAll(directMediaFromHtml(document.outerHtml()))
        document.select("video[src], video source[src], a[href], a[data-src]").forEach { element ->
            listOf("src", "data-src", "href").forEach { attr ->
                val value = element.attr(attr).trim()
                if (isDirectMedia(value)) urls.add(value)
            }
        }
        return urls.toList()
    }

    fun candidateUrls(value: Any?): List<String> {
        val result = ArrayList<String>()
        collectUrls(value, result)
        return result.distinct().filter { url ->
            isDirectMedia(url) || knownHosterRegex.containsMatchIn(url)
        }
    }

    fun candidateUrls(root: JSONObject, episode: Int?): List<String> {
        if (episode == null) return candidateUrls(root)
        val branches = ArrayList<Any?>()
        for (key in listOf("vf", "vostfr", "vo", "players", "episodes", "links", "sources")) {
            val section = root.optJSONObject(key) ?: continue
            section.opt(episode.toString())?.let(branches::add)
            section.opt("E$episode")?.let(branches::add)
            section.opt("episode$episode")?.let(branches::add)
        }
        return (if (branches.isEmpty()) listOf(root) else branches)
            .flatMap(::candidateUrls)
            .distinct()
    }

    private fun findEpisodeBranch(root: JSONObject, episode: Int): Any? {
        for (key in listOf("vf", "vostfr", "vo", "players", "episodes", "links", "sources")) {
            val section = root.opt(key)
            if (section is JSONObject) {
                section.opt(episode.toString())?.let { return it }
                section.opt("E$episode")?.let { return it }
                section.opt("episode$episode")?.let { return it }
            }
        }
        return root
    }

    private fun collectUrls(value: Any?, output: MutableList<String>) {
        when (value) {
            is JSONObject -> value.keys().forEach { key -> collectUrls(value.opt(key), output) }
            is JSONArray -> (0 until value.length()).forEach { index -> collectUrls(value.opt(index), output) }
            is String -> if (isHttpUrl(value)) output.add(value.trim())
        }
    }

    fun episodeNumbers(root: JSONObject): List<Int> {
        val numbers = sortedSetOf<Int>()
        listOf("vf", "vostfr", "vo", "info", "episodes").forEach { key ->
            val section = root.optJSONObject(key) ?: return@forEach
            section.keys().forEach { it.toIntOrNull()?.takeIf { n -> n > 0 }?.let(numbers::add) }
        }
        return numbers.toList()
    }

    fun sourceName(url: String): String {
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        return host.substringBefore('.').replaceFirstChar { it.uppercase(Locale.ROOT) }.ifBlank { "Lecteur français" }
    }

    fun page(url: String, source: ContentSource, referer: String?, label: String?): ContentPage {
        val headers = referer?.takeIf(::isHttpUrl)?.let {
            mapOf("Referer" to it)
        }
        return ContentPage(
            id = stableId(url),
            url = url,
            preview = null,
            headers = headers,
            source = source,
        )
    }

    fun relativeOrSelf(url: String, domain: String): String =
        runCatching { url.toRelativeUrl(domain) }.getOrDefault(url)

    private fun stableId(value: String): Long = value.hashCode().toLong()
}
