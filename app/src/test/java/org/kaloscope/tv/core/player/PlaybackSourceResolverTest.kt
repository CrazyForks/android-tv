package org.kaloscope.tv.core.player

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kaloscope.tv.core.model.NetworkVideoType
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.model.SessionUser
import org.kaloscope.tv.core.network.OriginAuthPolicy

class PlaybackSourceResolverTest {
    @Test
    fun `direct local source encodes path without transcode metadata`() {
        val source = PlaybackSourceResolver.localMediaSource(
            session = session(),
            path = "/媒体/Season 01/Episode 1.mkv",
            sourceKind = PlaybackSourceKind.Direct,
            quality = TranscodeQuality.High,
        )

        assertEquals(
            "http://127.0.0.1:8000/_api/media/stream" +
                "?path=%2F%E5%AA%92%E4%BD%93%2FSeason%2001%2FEpisode%201.mkv",
            source.url,
        )
        assertNull(source.mimeType)
    }

    @Test
    fun `transcode stream URL includes selected quality without resolution override`() {
        val source = PlaybackSourceResolver.localMediaSource(
            session = session(),
            path = "/媒体/Season 01/Episode 1.mkv",
            sourceKind = PlaybackSourceKind.HlsTranscode,
            quality = TranscodeQuality.High,
        )

        assertEquals(
            "http://127.0.0.1:8000/_api/media/stream" +
                "?path=%2F%E5%AA%92%E4%BD%93%2FSeason%2001%2FEpisode%201.mkv" +
                "&transcode=true&quality=high",
            source.url,
        )
        assertEquals("application/x-mpegURL", source.mimeType)
    }

    @Test
    fun `relative subtitle URL resolves against the current server`() {
        assertEquals(
            "http://127.0.0.1:8000/_api/subtitle/content?path=fixture",
            PlaybackSourceResolver.resolveServerResource(
                session(),
                "/_api/subtitle/content?path=fixture",
            ),
        )
    }

    @Test
    fun `network HLS resolves same server path without changing its media type`() {
        val source = PlaybackSourceResolver.networkMediaSource(
            session = session(),
            rawUrl = "/_api/media/proxy?id=1",
            videoType = NetworkVideoType.Hls,
        )

        assertEquals("http://127.0.0.1:8000/_api/media/proxy?id=1", source.url)
        assertEquals("application/x-mpegURL", source.mimeType)
    }

    @Test
    fun `third party MP4 remains absolute and unaffiliated with server origin`() {
        val source = PlaybackSourceResolver.networkMediaSource(
            session = session(),
            rawUrl = "https://cdn.example/video.mp4",
            videoType = NetworkVideoType.Mp4,
        )

        assertEquals("https://cdn.example/video.mp4", source.url)
        assertEquals("video/mp4", source.mimeType)
    }

    @Test
    fun `protocol relative streams inherit the scheme without the server host or port`() {
        val mediaTypes = mapOf(
            NetworkVideoType.Hls to "application/x-mpegURL",
            NetworkVideoType.Dash to "application/dash+xml",
            NetworkVideoType.Mp4 to "video/mp4",
            NetworkVideoType.Unknown to null,
        )
        val rawUrl = "//cdn.example:9443/video%20one?signature=a%2Fb%2Bc&part=2"
        for (scheme in listOf("http", "https")) {
            val session = session("$scheme://server.example:8443")
            for ((videoType, mimeType) in mediaTypes) {
                val source = PlaybackSourceResolver.networkMediaSource(session, rawUrl, videoType)

                assertEquals("$scheme:$rawUrl", source.url)
                assertEquals(mimeType, source.mimeType)
                assertEquals(
                    false,
                    OriginAuthPolicy.shouldAttachToken(session.server.origin, source.url),
                )
            }
        }
    }

    @Test
    fun `protocol relative subtitles retain authority for origin based authorization`() {
        val session = session("https://server.example")
        val authorities = mapOf(
            "server.example" to true,
            "server.example:443" to true,
            "server.example:8443" to false,
            "cdn.example" to false,
        )
        for ((authority, shouldAttachToken) in authorities) {
            val rawUrl = "//$authority/subtitle.vtt?path=episode%2F1"
            val url = PlaybackSourceResolver.resolveServerResource(session, rawUrl)

            assertEquals("https:$rawUrl", url)
            assertEquals(
                shouldAttachToken,
                OriginAuthPolicy.shouldAttachToken(session.server.origin, url),
            )
        }
    }

    @Test
    fun `data source stays unchanged when resolving network media`() {
        val rawUrl = "data:video/mp4;base64,AAAA"
        val source = PlaybackSourceResolver.networkMediaSource(
            session(),
            rawUrl,
            NetworkVideoType.Mp4,
        )

        assertEquals(rawUrl, source.url)
        assertEquals("video/mp4", source.mimeType)
    }

    @Test
    fun `network DASH URL declares DASH media type`() {
        val source = PlaybackSourceResolver.networkMediaSource(
            session = session(),
            rawUrl = "https://cdn.example/manifest.mpd",
            videoType = NetworkVideoType.Dash,
        )

        assertEquals("https://cdn.example/manifest.mpd", source.url)
        assertEquals("application/dash+xml", source.mimeType)
    }

    @Test
    fun `inline DASH rewrites server API base and becomes data URI`() {
        val source = PlaybackSourceResolver.networkMediaSource(
            session = session(),
            rawUrl = """
                <?xml version="1.0"?>
                <MPD><Period><BaseURL>/_api/media/proxy/</BaseURL></Period></MPD>
            """.trimIndent(),
            videoType = NetworkVideoType.Dash,
        )

        assertEquals("application/dash+xml", source.mimeType)
        val encodedManifest = source.url.substringAfter("base64,")
        assertEquals(
            """
                <?xml version="1.0"?>
                <MPD><Period><BaseURL>http://127.0.0.1:8000/_api/media/proxy/</BaseURL></Period></MPD>
            """.trimIndent(),
            String(Base64.getDecoder().decode(encodedManifest), Charsets.UTF_8),
        )
    }
}

private fun session(origin: String = "http://127.0.0.1:8000") = Session(
    server = SavedServer("server-1", "Home", origin),
    token = "fixture-token",
    user = SessionUser(1, "tv", "user"),
)
