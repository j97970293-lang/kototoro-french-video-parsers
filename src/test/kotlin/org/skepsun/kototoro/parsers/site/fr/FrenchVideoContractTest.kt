package org.skepsun.kototoro.parsers.site.fr

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class FrenchVideoContractTest {

    @Test
    fun `keeps only videos explicitly marked as French`() {
        assertTrue(FrenchVideoContract.isDeclaredFrench(JSONObject("""{"language":{"id":"fr"}}""")))
        assertTrue(FrenchVideoContract.isDeclaredFrench(JSONObject("""{"language":{"id":"FR"}}""")))
        assertFalse(FrenchVideoContract.isDeclaredFrench(JSONObject("""{"language":{"id":"en"}}""")))
        assertFalse(FrenchVideoContract.isDeclaredFrench(JSONObject("""{"language":null}""")))
    }

    @Test
    fun `prefers the HTTPS file returned by PeerTube and falls back to its playlist`() {
        val direct = JSONObject(
            """{"files":[{"fileUrl":"https://cdn.example/video.mp4"}],"streamingPlaylists":[{"playlistUrl":"https://cdn.example/master.m3u8"}]}""",
        )
        val hlsOnly = JSONObject("""{"files":[],"streamingPlaylists":[{"playlistUrl":"https://cdn.example/master.m3u8"}]}""")

        assertEquals("https://cdn.example/video.mp4", FrenchVideoContract.selectPeerTubePlayback(direct))
        assertEquals("https://cdn.example/master.m3u8", FrenchVideoContract.selectPeerTubePlayback(hlsOnly))
    }

    @Test
    fun `rejects non HTTPS playback links and preserves Commons attribution pages`() {
        val unsafe = JSONObject("""{"files":[{"fileUrl":"http://cdn.example/video.mp4"}]}""")

        assertNull(FrenchVideoContract.selectPeerTubePlayback(unsafe))
        assertEquals(
            "https://commons.wikimedia.org/wiki/File%3AVid%C3%A9o_fran%C3%A7aise.webm",
            FrenchVideoContract.commonsFilePageUrl("commons.wikimedia.org", "File:Vidéo française.webm"),
        )
    }
}
