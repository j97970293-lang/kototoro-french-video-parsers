package org.skepsun.kototoro.parsers.site.fr

import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Règles pures partagées par les parsers, isolées pour des tests sans réseau. */
internal object FrenchVideoContract {

    const val FRENCH_LANGUAGE = "fr"

    fun isDeclaredFrench(video: JSONObject): Boolean = video.optJSONObject("language")
        ?.optString("id")
        ?.lowercase()
        ?.trim() == FRENCH_LANGUAGE

    fun selectPeerTubePlayback(video: JSONObject): String? {
        val files = video.optJSONArray("files")
        for (index in 0 until (files?.length() ?: 0)) {
            files?.optJSONObject(index)?.optString("fileUrl")?.takeIf(::isHttps)?.let { return it }
        }
        val playlists = video.optJSONArray("streamingPlaylists")
        for (index in 0 until (playlists?.length() ?: 0)) {
            playlists?.optJSONObject(index)?.optString("playlistUrl")?.takeIf(::isHttps)?.let { return it }
        }
        return null
    }

    fun commonsFilePageUrl(domain: String, title: String): String {
        val encodedTitle = URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8.name())
            .replace("+", "_")
        return "https://${domain.trim('/')}/wiki/$encodedTitle"
    }

    private fun isHttps(value: String): Boolean = value.startsWith("https://")
}
