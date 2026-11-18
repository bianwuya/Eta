package io.github.mangi.eta.local.imagegen

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Only the ordinary Images route and its sibling Models route. No custom request override. */
internal object ImageGenEndpoint {
    private const val IMAGES_PATH = "/images/generations"
    private val loopback = setOf("localhost", "127.0.0.1", "::1")

    fun resolve(input: String): HttpUrl? {
        val raw = input.trim()
        if (raw.length > 2048 || raw.any { it.isISOControl() || it == '\\' }) return null
        if (!raw.startsWith("https://", ignoreCase = true) && !raw.startsWith("http://", ignoreCase = true)) return null
        val authority = raw.substringAfter("://").substringBefore('/')
        if (authority.isEmpty() || '@' in authority || authority.any(Char::isWhitespace)) return null
        val url = raw.toHttpUrlOrNull() ?: return null
        if (!url.isHttps && (url.scheme != "http" || url.host !in loopback)) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
            return null
        }
        val path = url.encodedPath.trimEnd('/')
        val generationPath = when {
            path.isEmpty() || path == "/v1" -> "/v1$IMAGES_PATH"
            path.endsWith(IMAGES_PATH) && !path.removeSuffix(IMAGES_PATH).contains(IMAGES_PATH) &&
                "/v1/v1/" !in path -> path
            else -> return null
        }
        return url.newBuilder().encodedPath(generationPath).build()
    }

    fun models(endpoint: HttpUrl): HttpUrl = endpoint.newBuilder()
        .encodedPath(endpoint.encodedPath.removeSuffix(IMAGES_PATH) + "/models")
        .build()
}
