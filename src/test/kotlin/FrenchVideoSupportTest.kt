import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.site.fr.FrenchVideoSupport

class FrenchVideoSupportTest {
    @Test
    fun `payload round trip preserves page api and episode`() {
        val payload = FrenchVideoSupport.payload(
            pageUrl = "https://french-stream.one/123-film.html",
            apiUrl = "https://french-stream.one/ep-data.php?id=123",
            episode = 4,
        )
        val parsed = FrenchVideoSupport.parsePayload(payload)
        assertEquals("https://french-stream.one/123-film.html", parsed?.pageUrl)
        assertEquals("https://french-stream.one/ep-data.php?id=123", parsed?.apiUrl)
        assertEquals(4, parsed?.episode)
    }

    @Test
    fun `html extraction accepts direct media and ignores hoster pages`() {
        val html = """
            <video><source src="https://cdn.example/video-720p.mp4"></video>
            <iframe src="https://uqload.cx/embed/abc"></iframe>
            <a href="https://cdn.example/playlist.m3u8?token=public">play</a>
        """.trimIndent()
        val urls = FrenchVideoSupport.directMediaFromHtml(html)
        assertEquals(2, urls.size)
        assertTrue(urls.any { it.endsWith("video-720p.mp4") })
        assertTrue(urls.any { it.contains("playlist.m3u8") })
    }

    @Test
    fun `json candidates are limited to supported media and hosters`() {
        val json = JSONObject(
            """
            {"vf":{"1":{"Uqload":"https://uqload.cx/embed/ok","bad":"https://example.org/page"}},
             "vostfr":{"1":{"direct":"https://cdn.example/show-1080p.m3u8"}}}
            """.trimIndent(),
        )
        val urls = FrenchVideoSupport.candidateUrls(json, 1)
        assertEquals(2, urls.size)
        assertTrue(urls.any { it.contains("uqload.cx") })
        assertTrue(urls.any { it.contains(".m3u8") })
    }
}
