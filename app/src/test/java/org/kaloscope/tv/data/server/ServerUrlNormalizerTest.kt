package org.kaloscope.tv.data.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerUrlNormalizerTest {
    @Test
    fun `normalizes whitespace and trailing slashes`() {
        assertEquals(
            "http://192.168.1.2:8000",
            ServerUrlNormalizer.normalize("  http://192.168.1.2:8000///  "),
        )
    }

    @Test
    fun `IPv6 origins retain a single pair of brackets and their explicit port`() {
        val cases = mapOf(
            " http://[::1]/// " to "http://[::1]",
            "HTTP://[2001:DB8::1]:8000/" to "http://[2001:db8::1]:8000",
            "https://[2001:DB8::1]" to "https://[2001:db8::1]",
            "https://[2001:DB8::1]:443" to "https://[2001:db8::1]:443",
            "https://[2001:db8:0:0:0:0:0:1]:8443" to
                "https://[2001:db8:0:0:0:0:0:1]:8443",
        )
        for ((input, expected) in cases) {
            val origin = ServerUrlNormalizer.normalize(input)

            assertEquals(expected, origin)
            assertEquals(origin, ServerUrlNormalizer.normalize(origin))
        }
    }

    @Test
    fun `IPv6 origins still reject credentials paths queries fragments and invalid ports`() {
        val cases = mapOf(
            "http://user:password@[2001:db8::1]" to ServerUrlError.CredentialsNotAllowed,
            "http://[2001:db8::1]/dashboard" to ServerUrlError.PathNotAllowed,
            "http://[2001:db8::1]?page=1" to ServerUrlError.PathNotAllowed,
            "http://[2001:db8::1]#setup" to ServerUrlError.PathNotAllowed,
            "http://[2001:db8::1]:0" to ServerUrlError.InvalidPort,
            "http://[2001:db8::1]:65536" to ServerUrlError.InvalidPort,
            "http://2001:db8::1" to ServerUrlError.MissingHost,
            "http://[[2001:db8::1]]" to ServerUrlError.MissingHost,
        )
        for ((input, expected) in cases) {
            val error = assertThrows(InvalidServerUrl::class.java) {
                ServerUrlNormalizer.normalize(input)
            }

            assertEquals(input, expected, error.reason)
        }
    }

    @Test
    fun `rejects paths beyond the server origin`() {
        assertThrows(InvalidServerUrl::class.java) {
            ServerUrlNormalizer.normalize("https://media.example.com/dashboard")
        }
    }

    @Test
    fun `rejects unsupported schemes`() {
        assertThrows(InvalidServerUrl::class.java) {
            ServerUrlNormalizer.normalize("ftp://media.example.com")
        }
    }
}
