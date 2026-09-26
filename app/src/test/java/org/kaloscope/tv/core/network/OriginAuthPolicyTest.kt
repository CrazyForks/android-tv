package org.kaloscope.tv.core.network

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginAuthPolicyTest {
    @Test
    fun `attaches token to the same origin`() {
        assertTrue(
            OriginAuthPolicy.shouldAttachToken(
                serverOrigin = "http://192.168.1.2:8000",
                requestUrl = "http://192.168.1.2:8000/_api/auth/current",
            ),
        )
    }

    @Test
    fun `treats implicit and explicit default ports as the same origin`() {
        for ((scheme, port) in mapOf("http" to 80, "https" to 443)) {
            assertTrue(
                OriginAuthPolicy.shouldAttachToken(
                    serverOrigin = "$scheme://media.example.com",
                    requestUrl = "$scheme://media.example.com:$port/_api/media/stream",
                ),
            )
        }
    }

    @Test
    fun `does not attach token to a subdomain`() {
        assertFalse(
            OriginAuthPolicy.shouldAttachToken(
                serverOrigin = "https://media.example.com",
                requestUrl = "https://cdn.media.example.com/video.m3u8",
            ),
        )
    }

    @Test
    fun `does not attach token when scheme differs`() {
        assertFalse(
            OriginAuthPolicy.shouldAttachToken(
                serverOrigin = "https://media.example.com",
                requestUrl = "http://media.example.com/_api/image/proxy",
            ),
        )
    }

    @Test
    fun `same origin resources retain authorization before and after HTTP URL encoding`() {
        val origin = "https://media.example.com:8443"
        val paths = listOf(
            "/_api/covers/page 1.webp",
            "/_api/covers/page%final.webp",
            "/_api/covers/封面.webp?variant=full size",
            "/_api/media/stream?path=episode%2F1&signature=a%2Fb+z",
        )
        for (path in paths) {
            val rawUrl = "$origin$path"
            val request = Request.Builder().url(rawUrl).build()

            assertEquals("media.example.com", request.url.host)
            assertEquals(8443, request.url.port)
            assertTrue(rawUrl, OriginAuthPolicy.shouldAttachToken(origin, rawUrl))
            assertTrue(
                rawUrl,
                OriginAuthPolicy.shouldAttachToken(origin, request.url.toString()),
            )
        }
    }

    @Test
    fun `HTTP URL encoding preserves origin boundaries for third party resources`() {
        val origin = "https://media.example.com"
        val urls = listOf(
            "http://media.example.com/video 1.mp4",
            "https://media.example.com:8443/video 1.mp4",
            "https://media.example.com.cdn.example/video 1.mp4",
            "https://media.example.com@cdn.example/video 1.mp4",
            "https://cdn.example/video 1.mp4?origin=https://media.example.com",
            "https://cdn.example/video 1.mp4#https://media.example.com",
        )
        for (url in urls) {
            val request = Request.Builder().url(url).build()

            assertFalse(url, OriginAuthPolicy.shouldAttachToken(origin, url))
            assertFalse(
                url,
                OriginAuthPolicy.shouldAttachToken(origin, request.url.toString()),
            )
        }
    }

    @Test
    fun `invalid relative and non HTTP URLs never receive authorization`() {
        val origin = "https://media.example.com"
        val urls = listOf(
            "",
            "not a URL",
            "https://",
            "//media.example.com/image.webp",
            "/_api/image.webp",
            "file:///image.webp",
            "data:image/png;base64,AAAA",
            "ftp://media.example.com/image.webp",
        )
        for (url in urls) {
            assertFalse(url, OriginAuthPolicy.shouldAttachToken(origin, url))
            assertFalse(url, OriginAuthPolicy.shouldAttachToken(url, origin))
        }
    }
}
