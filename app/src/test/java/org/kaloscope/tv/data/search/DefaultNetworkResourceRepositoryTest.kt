package org.kaloscope.tv.data.search

import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kaloscope.tv.core.common.AppError
import org.kaloscope.tv.core.common.AppResult
import org.kaloscope.tv.core.model.DanmakuComment
import org.kaloscope.tv.core.model.NetworkChapter
import org.kaloscope.tv.core.model.NetworkDefinition
import org.kaloscope.tv.core.model.NetworkMediaType
import org.kaloscope.tv.core.model.NetworkPlaybackSource
import org.kaloscope.tv.core.model.NetworkSearchResult
import org.kaloscope.tv.core.model.NetworkVideoType
import org.kaloscope.tv.core.model.ReaderChapter
import org.kaloscope.tv.core.model.ReaderImageContent
import org.kaloscope.tv.core.model.ReaderTextContent
import org.kaloscope.tv.core.model.ResolvedNetworkResource
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.model.SessionUser
import org.kaloscope.tv.core.network.ApiClientFactory
import org.kaloscope.tv.core.player.NetworkVideoCodecSupport
import org.kaloscope.tv.core.player.PlaybackRequest
import org.kaloscope.tv.core.player.PlaybackRequestNavigator
import org.kaloscope.tv.core.player.PlaybackSourceResolver
import org.kaloscope.tv.core.player.TranscodeResolution

class DefaultNetworkResourceRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: DefaultNetworkResourceRepository
    private lateinit var json: Json

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        repository = DefaultNetworkResourceRepository(ApiClientFactory(json), json)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `details type overrides catalog hint and resolves text array`() = runTest {
        server.enqueue(
            response(
                """
                {"status":200,"message":"","data":{
                  "id":"book-1","title":"Chapter One","media_type":"text",
                  "text":["First paragraph","Second paragraph"],
                  "chapters":[{"id":"c1","title":"Chapter One","volume":"Volume A"}]
                }}
                """.trimIndent(),
            ),
        )

        val resolved = repository.resolveResource(
            session = session(),
            indexerId = 11,
            result = result("book-1", NetworkMediaType.Image),
            preferredDefinition = TranscodeResolution.P1080,
        )

        val text = (resolved as AppResult.Success).value as ResolvedNetworkResource.Text
        assertEquals("First paragraph\n\nSecond paragraph", text.content.text)
        assertEquals("c1", text.content.chapters.single().id)
    }

    @Test
    fun `text reader keeps search result title after first chapter fallback`() = runTest {
        server.enqueue(
            response(
                """
                {"status":200,"message":"","data":{
                  "id":"book-1","title":"Book","media_type":"text",
                  "chapters":[{"id":"c1","title":"Chapter One"}]
                }}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            response(
                """
                {"status":200,"message":"","data":{
                  "id":"book-1","title":"Chapter One","media_type":"text","text":"Body"
                }}
                """.trimIndent(),
            ),
        )

        val resolved = repository.resolveResource(
            session = session(),
            indexerId = 11,
            result = result("book-1", NetworkMediaType.Text),
            preferredDefinition = TranscodeResolution.P1080,
        )

        val text = (resolved as AppResult.Success).value as ResolvedNetworkResource.Text
        assertEquals("Result", text.content.title)
    }

    @Test
    fun `image reader keeps search result title after first chapter fallback`() = runTest {
        server.enqueue(
            response(
                """
                {"status":200,"message":"","data":{
                  "id":"comic-1","title":"Comic","media_type":"image",
                  "chapters":[{"id":"c1","title":"Chapter One"}]
                }}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            response(
                """
                {"status":200,"message":"","data":{
                  "id":"comic-1","title":"Chapter One","media_type":"image",
                  "images":["one.jpg"]
                }}
                """.trimIndent(),
            ),
        )

        val resolved = repository.resolveResource(
            session = session(),
            indexerId = 11,
            result = result("comic-1", NetworkMediaType.Image),
            preferredDefinition = TranscodeResolution.P1080,
        )

        val image = (resolved as AppResult.Success).value as ResolvedNetworkResource.Image
        assertEquals("Result", image.content.title)
    }

    @Test
    fun `malformed video definition returns invalid data and allows retry`() = runTest {
        server.enqueue(
            response(
                """
                {"status":200,"message":"","data":{
                  "id":"video-1","title":"Video","media_type":"video","video_type":"hls",
                  "definitions":[{"url":"https://cdn.example/video.m3u8","definition":{}}]
                }}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            response(
                """
                {"status":200,"message":"","data":{
                  "id":"video-1","title":"Video","media_type":"video","video_type":"hls",
                  "definitions":[{"url":"https://cdn.example/video.m3u8","definition":"1080P"}]
                }}
                """.trimIndent(),
            ),
        )

        val failed = repository.resolveResource(
            session = session(),
            indexerId = 11,
            result = result("video-1", NetworkMediaType.Video),
            preferredDefinition = TranscodeResolution.P1080,
        )

        assertTrue((failed as AppResult.Failure).error is AppError.InvalidData)
        assertEquals(1, server.requestCount)

        val retried = repository.resolveResource(
            session = session(),
            indexerId = 11,
            result = result("video-1", NetworkMediaType.Video),
            preferredDefinition = TranscodeResolution.P1080,
        )

        val source = ((retried as AppResult.Success).value as ResolvedNetworkResource.Video).source
        assertEquals("https://cdn.example/video.m3u8", source.url)
        assertEquals("1080P", source.definitions.single().label)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `malformed chapter definition returns invalid data`() = runTest {
        server.enqueue(
            response(
                """
                {"status":200,"message":"","data":{
                  "id":"episode-2","title":"Episode 2","media_type":"video","video_type":"hls",
                  "definitions":[{"url":"https://cdn.example/episode-2.m3u8","definition":[]}]
                }}
                """.trimIndent(),
            ),
        )
        val current = NetworkPlaybackSource(
            indexerId = 11,
            resourceId = "series-1",
            title = "Episode 1",
            url = "https://cdn.example/episode-1.m3u8",
            videoType = NetworkVideoType.Hls,
            danmakus = emptyList(),
            chapters = listOf(
                NetworkChapter("episode-1", null, "Episode 1", null),
                NetworkChapter("episode-2", null, "Episode 2", null),
            ),
            selectedChapterIndex = 0,
        )

        val failed = repository.resolveVideoChapter(
            session = session(),
            source = current,
            chapterIndex = 1,
            preferredDefinition = TranscodeResolution.P1080,
        )

        assertTrue((failed as AppResult.Failure).error is AppError.InvalidData)
        assertEquals(1, server.requestCount)
        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(JsonPrimitive("series-1"), body["id"])
        assertEquals(JsonPrimitive("episode-2"), body["chapter_id"])
    }

    @Test
    fun `video details use catalog video type when response omits it`() = runTest {
        server.enqueue(
            response(
                """
                {"status":200,"message":"","data":{
                  "id":"video-1","title":"Video","media_type":"video",
                  "url":"<MPD><Period /></MPD>"
                }}
                """.trimIndent(),
            ),
        )

        val resolved = repository.resolveResource(
            session = session(),
            indexerId = 11,
            result = result(
                id = "video-1",
                type = NetworkMediaType.Video,
                videoTypeHint = NetworkVideoType.Dash,
            ),
            preferredDefinition = TranscodeResolution.P1080,
        )

        val video = (resolved as AppResult.Success).value as ResolvedNetworkResource.Video
        assertEquals(NetworkVideoType.Dash, video.source.videoType)
    }

    @Test
    fun `software AVC capability selects matching HEVC DASH definition`() = runTest {
        server.enqueue(
            response(
                """
                {
                  "status": 200,
                  "message": "",
                  "data": {
                    "id": "video-1",
                    "title": "Video",
                    "media_type": "video",
                    "video_type": "dash",
                    "definitions": [
                      {"url": "https://media.example/avc.mpd", "definition": "480P AVC"},
                      {"url": "https://media.example/hevc.mpd", "definition": "480P HEVC"}
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )
        val compatibleRepository = DefaultNetworkResourceRepository(
            apiClientFactory = ApiClientFactory(json),
            json = json,
            videoCodecSupport = NetworkVideoCodecSupport { true },
        )

        val resolved = compatibleRepository.resolveResource(
            session = session(),
            indexerId = 11,
            result = result(
                id = "video-1",
                type = NetworkMediaType.Video,
                videoTypeHint = NetworkVideoType.Dash,
            ),
            preferredDefinition = TranscodeResolution.P480,
        )

        val source = ((resolved as AppResult.Success).value as ResolvedNetworkResource.Video).source
        assertEquals("https://media.example/hevc.mpd", source.url)
        assertEquals(1, source.selectedDefinitionIndex)
    }

    @Test
    fun `video details retain catalog resource id when response identifies a chapter`() = runTest {
        server.enqueue(
            response(
                """
                {"status":200,"message":"","data":{
                  "id":"episode-1","title":"Episode 1","media_type":"video",
                  "video_type":"hls","url":"https://cdn.example/episode-1.m3u8",
                  "chapters":[
                    {"id":"episode-1","title":"Episode 1"},
                    {"id":"episode-2","title":"Episode 2"}
                  ]
                }}
                """.trimIndent(),
            ),
        )

        val resolved = repository.resolveResource(
            session = session(),
            indexerId = 11,
            result = result("series-1", NetworkMediaType.Video),
            preferredDefinition = TranscodeResolution.P1080,
        )

        val source = ((resolved as AppResult.Success).value as ResolvedNetworkResource.Video).source
        assertEquals("series-1", source.resourceId)
        assertEquals("https://cdn.example/episode-1.m3u8", source.url)
        assertEquals(listOf("episode-1", "episode-2"), source.chapters.map { it.id })
        assertEquals(0, source.selectedChapterIndex)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `resolved later chapter starts adjacent navigation from that chapter`() = runTest {
        server.enqueue(
            response(
                """
                {"status":200,"message":"","data":{
                  "id":"episode-2","title":"Episode 2","media_type":"video",
                  "video_type":"hls","url":"https://cdn.example/episode-2.m3u8",
                  "chapters":[
                    {"id":"episode-1","title":"Episode 1"},
                    {"id":"episode-2","title":"Episode 2"},
                    {"id":"episode-3","title":"Episode 3"}
                  ]
                }}
                """.trimIndent(),
            ),
        )
        val resolved = repository.resolveResource(
            session = session(),
            indexerId = 11,
            result = result("series-1", NetworkMediaType.Video),
            preferredDefinition = TranscodeResolution.P1080,
        )
        val source = ((resolved as AppResult.Success).value as ResolvedNetworkResource.Video).source
        val request = PlaybackRequest.NetworkVideo(
            requestId = "request-1",
            serverId = session().server.id,
            title = source.title,
            source = source,
        )

        assertEquals("series-1", source.resourceId)
        assertTrue(PlaybackRequestNavigator.hasPrevious(request))
        assertEquals(0, PlaybackRequestNavigator.adjacentNetworkChapter(request, -1))
        assertEquals(null, PlaybackRequestNavigator.selectNetworkEpisode(request, 1))
        val nextIndex = PlaybackRequestNavigator.adjacentNetworkChapter(request, 1)
        assertEquals(2, nextIndex)
        server.takeRequest()
        server.enqueue(
            response(
                """
                {"status":200,"message":"","data":{
                  "id":"episode-3","title":"Episode 3","media_type":"video",
                  "video_type":"hls","url":"https://cdn.example/episode-3.m3u8"
                }}
                """.trimIndent(),
            ),
        )

        val nextResult = repository.resolveVideoChapter(
            session = session(),
            source = source,
            chapterIndex = checkNotNull(nextIndex),
            preferredDefinition = TranscodeResolution.P1080,
        )

        val next = (nextResult as AppResult.Success).value
        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(JsonPrimitive("series-1"), body["id"])
        assertEquals(JsonPrimitive("episode-3"), body["chapter_id"])
        assertEquals("https://cdn.example/episode-3.m3u8", next.url)
        assertFalse(PlaybackRequestNavigator.hasNext(request.copy(source = next)))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `details re-resolves first id-only chapter`() = runTest {
        server.enqueue(
            response(
                """{"status":200,"message":"","data":{""" +
                    """"id":"series-1","title":"Series","media_type":"video",""" +
                    """"video_type":"dash","chapters":[{"id":"episode-1",""" +
                    """"title":"Episode 1"}]}}""",
            ),
        )
        server.enqueue(
            response(
                """{"status":200,"message":"","data":{""" +
                    """"id":"episode-1","title":"Episode 1","media_type":"video",""" +
                    """"video_type":"dash","url":"https://cdn.example/episode-1.mpd"}}""",
            ),
        )

        val resolved = repository.resolveResource(
            session = session(),
            indexerId = 11,
            result = result(
                id = "series-1",
                type = NetworkMediaType.Video,
                videoTypeHint = NetworkVideoType.Dash,
            ),
            preferredDefinition = TranscodeResolution.P1080,
        )

        val source = ((resolved as AppResult.Success).value as ResolvedNetworkResource.Video).source
        assertEquals("series-1", source.resourceId)
        assertEquals("https://cdn.example/episode-1.mpd", source.url)
        assertEquals(0, source.selectedChapterIndex)
        server.takeRequest()
        val chapterBody = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(JsonPrimitive("series-1"), chapterBody["id"])
        assertEquals(JsonPrimitive("episode-1"), chapterBody["chapter_id"])
    }

    @Test
    fun `first chapter inherits details DASH type for inline manifest playback`() = runTest {
        for (catalogType in listOf(NetworkVideoType.Unknown, NetworkVideoType.Hls)) {
            server.enqueue(
                response(
                    """
                    {"status":200,"message":"","data":{
                      "id":"series-1","title":"Series","media_type":"video",
                      "video_type":"dash","chapters":[{"id":"episode-1","title":"Episode 1"}]
                    }}
                    """.trimIndent(),
                ),
            )
            server.enqueue(
                response(
                    """
                    {"status":200,"message":"","data":{
                      "id":"series-1","title":"Episode 1","media_type":"video",
                      "url":"<MPD><Period><BaseURL>/_api/media/proxy/</BaseURL><BaseURL serviceLocation='backup'>/_api/media/backup/</BaseURL></Period></MPD>"
                    }}
                    """.trimIndent(),
                ),
            )
            val playbackSession = session()

            val resolved = repository.resolveResource(
                session = playbackSession,
                indexerId = 11,
                result = result("series-1", NetworkMediaType.Video, catalogType),
                preferredDefinition = TranscodeResolution.P1080,
            )

            val source = ((resolved as AppResult.Success).value as ResolvedNetworkResource.Video).source
            assertEquals(NetworkVideoType.Dash, source.videoType)
            assertEquals(0, source.selectedChapterIndex)
            assertEquals("episode-1", source.chapters.single().id)
            val playbackSource = PlaybackSourceResolver.networkMediaSource(
                session = playbackSession,
                rawUrl = source.url,
                videoType = source.videoType,
            )
            assertEquals("application/dash+xml", playbackSource.mimeType)
            assertTrue(playbackSource.url.startsWith("data:application/dash+xml;base64,"))
            val manifest = String(
                Base64.getDecoder().decode(playbackSource.url.substringAfter("base64,")),
                Charsets.UTF_8,
            )
            assertEquals(
                "<MPD><Period>" +
                    "<BaseURL>${playbackSession.server.origin}/_api/media/proxy/</BaseURL>" +
                    "<BaseURL serviceLocation='backup'>" +
                    "${playbackSession.server.origin}/_api/media/backup/</BaseURL>" +
                    "</Period></MPD>",
                manifest,
            )
            server.takeRequest()
            val chapterRequest = server.takeRequest()
            assertTrue(chapterRequest.body.readUtf8().contains(""""chapter_id":"episode-1""""))
        }
    }

    @Test
    fun `first chapter video type preserves missing and explicit override semantics`() = runTest {
        val cases = listOf(
            Triple("hls", null, NetworkVideoType.Hls),
            Triple("hls", "  ", NetworkVideoType.Hls),
            Triple("custom", null, NetworkVideoType.Unknown),
            Triple(null, null, NetworkVideoType.Dash),
            Triple("  ", null, NetworkVideoType.Dash),
            Triple("dash", "mp4", NetworkVideoType.Mp4),
            Triple("dash", "custom", NetworkVideoType.Unknown),
        )
        for ((detailsType, chapterType, expectedType) in cases) {
            server.enqueue(
                response(
                    """
                    {"status":200,"message":"","data":{
                      "id":"series-1","title":"Series","media_type":"video",
                      "video_type":${detailsType?.let(::JsonPrimitive) ?: JsonNull},
                      "chapters":[{"id":"episode-1","title":"Episode 1"}]
                    }}
                    """.trimIndent(),
                ),
            )
            server.enqueue(
                response(
                    """
                    {"status":200,"message":"","data":{
                      "id":"series-1","title":"Episode 1","media_type":"video",
                      "video_type":${chapterType?.let(::JsonPrimitive) ?: JsonNull},
                      "url":"https://cdn.example/stream"
                    }}
                    """.trimIndent(),
                ),
            )
            val requestsBefore = server.requestCount

            val resolved = repository.resolveResource(
                session = session(),
                indexerId = 11,
                result = result("series-1", NetworkMediaType.Video, NetworkVideoType.Dash),
                preferredDefinition = TranscodeResolution.P1080,
            )

            val source = ((resolved as AppResult.Success).value as ResolvedNetworkResource.Video).source
            assertEquals("details=$detailsType chapter=$chapterType", expectedType, source.videoType)
            assertEquals(requestsBefore + 2, server.requestCount)
        }
    }

    @Test
    fun `video chapter switches keep resource id across forward and backward requests`() = runTest {
        val chapters = (1..3).map { episode ->
            NetworkChapter("episode-$episode", null, "Episode $episode", null)
        }
        var source = NetworkPlaybackSource(
            indexerId = 11,
            resourceId = "series-1",
            title = "Episode 1",
            url = "https://cdn.example/episode-1.m3u8",
            videoType = NetworkVideoType.Hls,
            danmakus = emptyList(),
            chapters = chapters,
            selectedChapterIndex = 0,
        )

        for (chapterIndex in listOf(1, 2, 0)) {
            val chapter = chapters[chapterIndex]
            val playbackUrl = "https://cdn.example/${chapter.id}.m3u8"
            server.enqueue(
                response(
                    """
                    {"status":200,"message":"","data":{
                      "id":"${chapter.id}","title":"${chapter.title}","media_type":"video",
                      "video_type":"hls","url":"$playbackUrl"
                    }}
                    """.trimIndent(),
                ),
            )

            val resolved = repository.resolveVideoChapter(
                session = session(),
                source = source,
                chapterIndex = chapterIndex,
                preferredDefinition = TranscodeResolution.P1080,
            )

            source = (resolved as AppResult.Success).value
            val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals(JsonPrimitive("series-1"), body["id"])
            assertEquals(JsonPrimitive(chapter.id), body["chapter_id"])
            assertEquals(playbackUrl, source.url)
            assertEquals(chapterIndex, source.selectedChapterIndex)
            assertEquals(chapters, source.chapters)
        }
        assertEquals("series-1", source.resourceId)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `direct chapter does not retain previous episode definitions or danmakus`() = runTest {
        val current = NetworkPlaybackSource(
            indexerId = 11,
            resourceId = "series-1",
            title = "Episode 1",
            url = "https://cdn.example/episode-1.m3u8",
            videoType = NetworkVideoType.Hls,
            danmakus = listOf(
                DanmakuComment(
                    id = "old",
                    text = "Old episode",
                    mode = "scroll",
                    color = null,
                    startMillis = 1_000,
                ),
            ),
            definitions = listOf(
                NetworkDefinition(
                    "1080P",
                    "https://cdn.example/episode-1-1080.m3u8",
                ),
            ),
            chapters = listOf(
                NetworkChapter("ep-1", null, "Episode 1", null),
                NetworkChapter(
                    null,
                    "https://cdn.example/episode-2.m3u8",
                    "Episode 2",
                    null,
                ),
            ),
            selectedDefinitionIndex = 0,
            selectedChapterIndex = 0,
        )

        val result = repository.resolveVideoChapter(
            session = session(),
            source = current,
            chapterIndex = 1,
            preferredDefinition = TranscodeResolution.P1080,
        )

        val next = (result as AppResult.Success).value
        assertEquals("series-1", next.resourceId)
        assertTrue(next.definitions.isEmpty())
        assertTrue(next.danmakus.isEmpty())
        assertEquals(1, next.selectedChapterIndex)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `image resolution falls back to first valid chapter once`() = runTest {
        server.enqueue(
            response(
                """
                {"status":200,"message":"","data":{
                  "id":"comic-1","title":"Comic","media_type":"image",
                  "chapters":[
                    {"id":null,"title":"Broken"},
                    {"id":"c1","title":"Chapter One","volume":"Volume A"},
                    {"id":"c1","title":"Duplicate","volume":"Volume A"}
                  ]
                }}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            response(
                """
                {"status":200,"message":"","data":{
                  "id":"comic-1","title":"Chapter One","media_type":"image",
                  "images":[" one.jpg ","one.jpg","two.jpg"],"image_count":5
                }}
                """.trimIndent(),
            ),
        )

        val resolved = repository.resolveResource(
            session = session(),
            indexerId = 11,
            result = result("comic-1", NetworkMediaType.Image),
            preferredDefinition = TranscodeResolution.P1080,
        )

        val image = (resolved as AppResult.Success).value as ResolvedNetworkResource.Image
        assertEquals(listOf("one.jpg", "two.jpg"), image.content.images)
        assertEquals(5, image.content.imageCount)
        assertEquals(listOf("c1"), image.content.chapters.map { it.id })
        assertEquals(0, image.content.selectedChapterIndex)
        assertEquals(2, server.requestCount)
        server.takeRequest()
        assertTrue(server.takeRequest().body.readUtf8().contains("\"chapter_id\":\"c1\""))
    }

    @Test
    fun `reader chapter request preserves source chapters and selects requested chapter`() = runTest {
        server.enqueue(
            response(
                """
                {"status":200,"message":"","data":{
                  "id":"book-1","title":"Chapter Two","media_type":"text","text":"Body"
                }}
                """.trimIndent(),
            ),
        )
        val current = ReaderTextContent.network(
            indexerId = 11,
            resourceId = "book-1",
            title = "Chapter One",
            text = "Old",
            chapters = listOf(
                ReaderChapter("c1", "Chapter One"),
                ReaderChapter("c2", "Chapter Two"),
            ),
            selectedChapterIndex = 0,
        )

        val result = repository.resolveReaderChapter(session(), current, 1)

        val next = (result as AppResult.Success).value as ReaderTextContent
        assertEquals("Body", next.text)
        assertEquals(current.chapters, next.chapters)
        assertEquals(1, next.selectedChapterIndex)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"chapter_id\":\"c2\""))
    }

    @Test
    fun `text chapter change keeps the reader title`() = runTest {
        server.enqueue(
            response(
                """
                {"status":200,"message":"","data":{
                  "id":"book-1","title":"Chapter Two","media_type":"text","text":"Body"
                }}
                """.trimIndent(),
            ),
        )
        val current = ReaderTextContent.network(
            indexerId = 11,
            resourceId = "book-1",
            title = "Book",
            text = "Old",
            chapters = listOf(
                ReaderChapter("c1", "Chapter One"),
                ReaderChapter("c2", "Chapter Two"),
            ),
            selectedChapterIndex = 0,
        )

        val result = repository.resolveReaderChapter(session(), current, 1)

        val next = (result as AppResult.Success).value as ReaderTextContent
        assertEquals("Book", next.title)
    }

    @Test
    fun `image chapter change keeps the reader title`() = runTest {
        server.enqueue(
            response(
                """
                {"status":200,"message":"","data":{
                  "id":"comic-1","title":"Chapter Two","media_type":"image",
                  "images":["two.jpg"]
                }}
                """.trimIndent(),
            ),
        )
        val current = ReaderImageContent.network(
            indexerId = 11,
            resourceId = "comic-1",
            chapterId = "c1",
            title = "Comic",
            images = listOf("one.jpg"),
            imageCount = 1,
            chapters = listOf(
                ReaderChapter("c1", "Chapter One"),
                ReaderChapter("c2", "Chapter Two"),
            ),
            selectedChapterIndex = 0,
        )

        val result = repository.resolveReaderChapter(session(), current, 1)

        val next = (result as AppResult.Success).value as ReaderImageContent
        assertEquals("Comic", next.title)
    }

    @Test
    fun `null resource details remain invalid for initial resolution`() = runTest {
        for (type in listOf(NetworkMediaType.Video, NetworkMediaType.Image, NetworkMediaType.Text)) {
            server.enqueue(response("""{"status":200,"message":"","data":null}"""))

            val resolved = repository.resolveResource(
                session = session(),
                indexerId = 11,
                result = result("resource-1", type),
                preferredDefinition = TranscodeResolution.P1080,
            )

            assertTrue("type=$type", (resolved as AppResult.Failure).error is AppError.InvalidData)
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `null first chapter details remain invalid for every media type`() = runTest {
        for (type in listOf(NetworkMediaType.Video, NetworkMediaType.Image, NetworkMediaType.Text)) {
            server.enqueue(
                response(
                    """
                    {"status":200,"message":"","data":{
                      "id":"resource-1","media_type":"${type.name.lowercase()}",
                      "chapters":[{"id":"c1","title":"Chapter One"}]
                    }}
                    """.trimIndent(),
                ),
            )
            server.enqueue(response("""{"status":200,"message":"","data":null}"""))

            val resolved = repository.resolveResource(
                session = session(),
                indexerId = 11,
                result = result("resource-1", type),
                preferredDefinition = TranscodeResolution.P1080,
            )

            assertTrue("type=$type", (resolved as AppResult.Failure).error is AppError.InvalidData)
        }
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `null chapter details remain invalid when switching video or reader content`() = runTest {
        val chapters = listOf(
            ReaderChapter("c1", "Chapter One"),
            ReaderChapter("c2", "Chapter Two"),
        )
        val readerContents = listOf(
            ReaderImageContent.network(
                indexerId = 11,
                resourceId = "resource-1",
                chapterId = "c1",
                title = "Comic",
                images = listOf("one.jpg"),
                imageCount = 1,
                chapters = chapters,
                selectedChapterIndex = 0,
            ),
            ReaderTextContent.network(
                indexerId = 11,
                resourceId = "resource-1",
                chapterId = "c1",
                title = "Book",
                text = "Chapter One",
                chapters = chapters,
                selectedChapterIndex = 0,
            ),
        )
        for (content in readerContents) {
            server.enqueue(response("""{"status":200,"message":"","data":null}"""))

            val resolved = repository.resolveReaderChapter(session(), content, 1)

            assertTrue((resolved as AppResult.Failure).error is AppError.InvalidData)
        }
        val video = NetworkPlaybackSource(
            indexerId = 11,
            resourceId = "resource-1",
            title = "Chapter One",
            url = "https://cdn.example/one.m3u8",
            videoType = NetworkVideoType.Hls,
            danmakus = emptyList(),
            chapters = chapters.map { NetworkChapter(it.id, null, it.title, it.volume) },
            selectedChapterIndex = 0,
        )
        server.enqueue(response("""{"status":200,"message":"","data":null}"""))

        val resolved = repository.resolveVideoChapter(session(), video, 1, TranscodeResolution.P1080)

        assertTrue((resolved as AppResult.Failure).error is AppError.InvalidData)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `empty image page responses mark exhaustion only for successful envelopes`() = runTest {
        val current = ReaderImageContent.network(
            indexerId = 11,
            resourceId = "comic-1",
            chapterId = "c1",
            title = "Comic",
            images = listOf("one.jpg", "two.jpg"),
            imageCount = 5,
            chapters = listOf(ReaderChapter("c1", "Chapter One")),
            selectedChapterIndex = 0,
        )
        for (data in listOf("null", "{}", """{"images":null}""", """{"images":[]}""")) {
            server.enqueue(response("""{"status":200,"message":"","data":$data}"""))

            val result = repository.loadImagePage(session(), current)

            val page = (result as AppResult.Success).value
            assertTrue("data=$data", page.images.isEmpty())
            assertEquals(5, page.imageCount)
            assertTrue("data=$data", page.exhausted)
            val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals(JsonPrimitive("comic-1"), body["id"])
            assertEquals(JsonPrimitive("c1"), body["chapter_id"])
            assertEquals(JsonPrimitive(3), body["page"])
        }
        server.enqueue(response("""{"status":500,"message":"fixture-error","data":null}"""))

        val failed = repository.loadImagePage(session(), current)

        assertTrue((failed as AppResult.Failure).error is AppError.InvalidData)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `image page starts after loaded count deduplicates and detects exhaustion`() = runTest {
        server.enqueue(
            response(
                """
                {"status":200,"message":"","data":{
                  "id":"comic-1","media_type":"image",
                  "images":["two.jpg","three.jpg","three.jpg"],"image_count":3
                }}
                """.trimIndent(),
            ),
        )
        val current = ReaderImageContent.network(
            indexerId = 11,
            resourceId = "comic-1",
            chapterId = "c1",
            title = "Chapter One",
            images = listOf("one.jpg", "two.jpg"),
            imageCount = 5,
            chapters = listOf(ReaderChapter("c1", "Chapter One")),
            selectedChapterIndex = 0,
        )

        val result = repository.loadImagePage(session(), current)

        val page = (result as AppResult.Success).value
        assertEquals(listOf("three.jpg"), page.images)
        assertEquals(3, page.imageCount)
        assertTrue(page.exhausted)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"chapter_id\":\"c1\""))
        assertTrue(body.contains("\"page\":3"))
    }

    private fun result(
        id: String,
        type: NetworkMediaType,
        videoTypeHint: NetworkVideoType = NetworkVideoType.Unknown,
    ) = NetworkSearchResult(
        id = id,
        title = "Result",
        coverPath = null,
        rating = null,
        category = null,
        uploader = null,
        uploadedAt = null,
        mediaType = type,
        videoTypeHint = videoTypeHint,
    )

    private fun session() = Session(
        server = SavedServer(
            id = "server-id",
            name = "Home",
            origin = server.url("/").toString().removeSuffix("/"),
        ),
        token = "fixture-token",
        user = SessionUser(1, "tv", "user"),
    )

    private fun response(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
