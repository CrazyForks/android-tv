package org.kaloscope.tv.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.kaloscope.tv.core.model.Session

/**
 * Formats Kaloscope's token scheme after a caller has bound or validated the request origin.
 */
internal fun Session.authorizationHeader(): String = "Token $token"

/**
 * Prevents a Kaloscope token from being attached to third-party resource URLs.
 */
object OriginAuthPolicy {
    fun shouldAttachToken(
        serverOrigin: String,
        requestUrl: String,
    ): Boolean {
        val server = serverOrigin.toOrigin() ?: return false
        val request = requestUrl.toOrigin() ?: return false
        return server == request
    }
}

private data class Origin(
    val scheme: String,
    val host: String,
    val port: Int,
)

private fun String.toOrigin(): Origin? {
    // Use the HTTP client's parser so valid resource paths do not lose authorization.
    val url = toHttpUrlOrNull() ?: return null
    return Origin(scheme = url.scheme, host = url.host, port = url.port)
}
