package org.kaloscope.tv.feature.reader.image

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.model.SessionUser

class ReaderImageRequestFactoryTest {

    @Test
    fun remoteReaderImageUsesServerProxyAndSameOriginAuthorization() {
        val request = ReaderImageRequestFactory.resolve(
            session = Session(
                server = SavedServer("server-one", "Test", "https://media.example"),
                token = "token-one",
                user = SessionUser(1, "tv_user", "user"),
            ),
            rawUrl = "https://images.example/page-2.jpg",
        )

        checkNotNull(request)
        assertEquals(
            "https://media.example/_api/image/proxy?store=false&url=" +
                "https%3A%2F%2Fimages.example%2Fpage-2.jpg",
            request.url,
        )
        assertEquals("Token token-one", request.authorization)
    }

    @Test
    fun protocolRelativeReaderImageUsesAnAbsoluteProxyTarget() {
        val request = ReaderImageRequestFactory.resolve(
            session = Session(
                server = SavedServer("server-one", "Test", "https://media.example:8443"),
                token = "token-one",
                user = SessionUser(1, "tv_user", "user"),
            ),
            rawUrl = "//images.example:9443/page-2.jpg?signature=a%2Fb",
        )

        checkNotNull(request)
        assertEquals(
            "https://media.example:8443/_api/image/proxy?store=false&url=" +
                "https%3A%2F%2Fimages.example%3A9443%2Fpage-2.jpg%3Fsignature%3Da%252Fb",
            request.url,
        )
        assertEquals("Token token-one", request.authorization)
    }

    @Test
    fun sameOriginReaderImageWithSpacesRetainsAuthorization() {
        val request = ReaderImageRequestFactory.resolve(
            session = Session(
                server = SavedServer("server-one", "Test", "https://media.example"),
                token = "token-one",
                user = SessionUser(1, "tv_user", "user"),
            ),
            rawUrl = "/_api/covers/page 1.webp?variant=full size",
        )

        checkNotNull(request)
        assertEquals("Token token-one", request.authorization)
        val httpRequest = Request.Builder().url(request.url).build()
        assertEquals("/_api/covers/page%201.webp", httpRequest.url.encodedPath)
        assertEquals("full size", httpRequest.url.queryParameter("variant"))
    }
}
