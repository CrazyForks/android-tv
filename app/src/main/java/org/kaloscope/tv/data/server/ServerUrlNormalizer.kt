package org.kaloscope.tv.data.server

import java.net.URI
import java.net.URISyntaxException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class ServerUrlError {
    Empty,
    UnsupportedScheme,
    MissingHost,
    CredentialsNotAllowed,
    InvalidPort,
    PathNotAllowed,
}

class InvalidServerUrl(
    val reason: ServerUrlError,
) : IllegalArgumentException(reason.name)

/**
 * Produces a server origin that is safe to append the fixed API path to.
 */
object ServerUrlNormalizer {
    fun normalize(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isEmpty()) {
            throw InvalidServerUrl(ServerUrlError.Empty)
        }

        val uri = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            throw InvalidServerUrl(ServerUrlError.MissingHost)
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw InvalidServerUrl(ServerUrlError.UnsupportedScheme)
        }
        if (uri.host.isNullOrBlank()) {
            throw InvalidServerUrl(ServerUrlError.MissingHost)
        }
        if (uri.userInfo != null) {
            // Credentials in URLs are easy to leak through logs and UI state.
            throw InvalidServerUrl(ServerUrlError.CredentialsNotAllowed)
        }
        if (uri.port == 0 || uri.port > 65_535) {
            throw InvalidServerUrl(ServerUrlError.InvalidPort)
        }
        if (uri.path?.isNotEmpty() == true || uri.query != null || uri.fragment != null) {
            // Kaloscope API paths must always be resolved from the server origin.
            throw InvalidServerUrl(ServerUrlError.PathNotAllowed)
        }

        // URI.host already includes the brackets required for an IPv6 origin.
        val host = uri.host
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val origin = "$scheme://${host.lowercase()}$port"
        // URI also accepts scoped IPv6 hosts that the HTTP client cannot use.
        if (origin.toHttpUrlOrNull() == null) {
            throw InvalidServerUrl(ServerUrlError.MissingHost)
        }
        return origin
    }
}
