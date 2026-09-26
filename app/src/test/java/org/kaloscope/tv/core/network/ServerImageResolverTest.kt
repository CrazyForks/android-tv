package org.kaloscope.tv.core.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.model.SessionUser

class ServerImageResolverTest {
    @Test
    fun `relative server image receives current token`() {
        val request = ServerImageResolver.resolve(
            session = session(),
            rawValue = "posters/series.webp",
        )

        checkNotNull(request)
        assertEquals("https://media.example/_api/posters/series.webp", request.url)
        assertEquals("Token token-one", request.authorization)
    }

    @Test
    fun `root relative server image preserves its API path`() {
        val request = ServerImageResolver.resolve(
            session = session(),
            rawValue = "/_api/image/content?id=poster",
        )

        checkNotNull(request)
        assertEquals(
            "https://media.example/_api/image/content?id=poster",
            request.url,
        )
        assertEquals("Token token-one", request.authorization)
    }

    @Test
    fun `proxied remote image is routed through current server`() {
        val request = ServerImageResolver.resolve(
            session = session(),
            rawValue = "https://covers.example/a.webp?proxy=store",
        )

        checkNotNull(request)
        assertEquals(
            "https://media.example/_api/image/proxy?store=true&url=" +
                "https%3A%2F%2Fcovers.example%2Fa.webp%3Fproxy%3Dstore",
            request.url,
        )
        assertEquals("Token token-one", request.authorization)
    }

    @Test
    fun `auto policy proxies unmarked remote image on Android`() {
        val request = ServerImageResolver.resolve(
            session = session(),
            rawValue = "https://covers.example/a.webp",
        )

        checkNotNull(request)
        assertEquals(
            "https://media.example/_api/image/proxy?store=false&url=" +
                "https%3A%2F%2Fcovers.example%2Fa.webp",
            request.url,
        )
        assertEquals("Token token-one", request.authorization)
    }

    @Test
    fun `direct policy keeps remote image off the Kaloscope server`() {
        val request = ServerImageResolver.resolve(
            session = session(),
            rawValue = "https://covers.example/a.webp?proxy=store",
            policy = ServerImagePolicy.Direct,
        )

        checkNotNull(request)
        assertEquals(
            "https://covers.example/a.webp?proxy=store",
            request.url,
        )
        assertNull(request.authorization)
    }

    @Test
    fun `direct protocol relative images inherit only the server scheme`() {
        val raw = "//covers.example:9443/a%20b.webp?signature=a%2Fb%2Bc"
        for (scheme in listOf("http", "https")) {
            val request = ServerImageResolver.resolve(
                session = session("$scheme://media.example:8443"),
                rawValue = raw,
                policy = ServerImagePolicy.Direct,
            )

            checkNotNull(request)
            assertEquals("$scheme:$raw", request.url)
            assertNull(request.authorization)
        }
    }

    @Test
    fun `protocol relative images preserve proxy and storage policies`() {
        val cases = listOf(
            Triple(ServerImagePolicy.Auto, "", false),
            Triple(ServerImagePolicy.Auto, "&proxy=true", false),
            Triple(ServerImagePolicy.Auto, "&proxy=store", true),
            Triple(ServerImagePolicy.Proxy, "&proxy=store", false),
            Triple(ServerImagePolicy.Store, "", true),
            Triple(ServerImagePolicy.Store, "&proxy=false", true),
        )
        for (scheme in listOf("http", "https")) {
            val origin = "$scheme://media.example:8443"
            for ((policy, marker, store) in cases) {
                val raw = "//covers.example:9443/a%20b.webp?signature=a%2Fb%2Bc$marker"
                val request = ServerImageResolver.resolve(session(origin), raw, policy)

                checkNotNull(request)
                val url = request.url.toHttpUrl()
                assertEquals(
                    "$origin/_api/image/proxy",
                    url.newBuilder().query(null).build().toString(),
                )
                assertEquals(setOf("store", "url"), url.queryParameterNames)
                assertEquals(store.toString(), url.queryParameter("store"))
                assertEquals("$scheme:$raw", url.queryParameter("url"))
                assertEquals("Token token-one", request.authorization)
            }
        }
    }

    @Test
    fun `direct protocol relative images keep tokens bound to the resolved origin`() {
        val expectedAuthorization = mapOf(
            "media.example:8443" to "Token token-one",
            "media.example" to null,
            "media.example:9443" to null,
            "covers.example:8443" to null,
        )
        for ((authority, authorization) in expectedAuthorization) {
            val request = ServerImageResolver.resolve(
                session = session("https://media.example:8443"),
                rawValue = "//$authority/a.webp",
                policy = ServerImagePolicy.Direct,
            )

            checkNotNull(request)
            assertEquals("https://$authority/a.webp", request.url)
            assertEquals(authorization, request.authorization)
        }
    }

    @Test
    fun `proxy policy routes remote image without server storage`() {
        val request = ServerImageResolver.resolve(
            session = session(),
            rawValue = "https://covers.example/a.webp",
            policy = ServerImagePolicy.Proxy,
        )

        checkNotNull(request)
        assertEquals(
            "https://media.example/_api/image/proxy?store=false&url=" +
                "https%3A%2F%2Fcovers.example%2Fa.webp",
            request.url,
        )
        assertEquals("Token token-one", request.authorization)
    }

    @Test
    fun `store policy routes remote image with server storage`() {
        val request = ServerImageResolver.resolve(
            session = session(),
            rawValue = "https://covers.example/a.webp",
            policy = ServerImagePolicy.Store,
        )

        checkNotNull(request)
        assertEquals(
            "https://media.example/_api/image/proxy?store=true&url=" +
                "https%3A%2F%2Fcovers.example%2Fa.webp",
            request.url,
        )
        assertEquals("Token token-one", request.authorization)
    }

    @Test
    fun `server avatar bypasses remote proxy for every policy`() {
        val request = ServerImageResolver.resolve(
            session = session(),
            rawValue = "avatars/user.webp",
            policy = ServerImagePolicy.Store,
        )

        checkNotNull(request)
        assertEquals(
            "https://media.example/_api/avatars/user.webp",
            request.url,
        )
        assertEquals("Token token-one", request.authorization)
    }

    @Test
    fun `blank image is ignored`() {
        assertNull(ServerImageResolver.resolve(session(), "  "))
    }

    private fun session(origin: String = "https://media.example") = Session(
        server = SavedServer("server-one", "家庭服务器", origin),
        token = "token-one",
        user = SessionUser(1, "tv_user", "user"),
    )
}
