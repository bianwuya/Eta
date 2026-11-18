package io.github.mangi.eta.ui.app

import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI

/** Only contact the advertised loopback port; never send the fragment token or follow redirects. */
internal fun kimiWebAcceptsLocalHttp(port: Int): Boolean {
    if (port !in 1..65535) return false
    return try {
        val connection = URI("http://127.0.0.1:$port/").toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 400
            connection.readTimeout = 400
            connection.useCaches = false
            connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
