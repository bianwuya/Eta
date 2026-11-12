package io.github.mangi.eta.local.imagegen

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Standard Images route completion; unusual relay paths require explicit advanced override. */
internal object ImageGenEndpoint {
    fun resolve(input: String, override: String = ""): HttpUrl {
        val raw = override.ifBlank { input }.trim()
        val url = raw.toHttpUrlOrNull() ?: throw IllegalArgumentException("生图地址格式无效")
        require(url.isHttps || (url.scheme == "http" && url.host in setOf("localhost", "127.0.0.1", "::1"))) {
            "生图地址需使用 HTTPS；本机假服务器可用 HTTP"
        }
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "生图地址不能包含账号、密码、查询参数或片段"
        }
        if (override.isNotBlank()) return url
        val path = url.encodedPath.trimEnd('/')
        return when (path) {
            "" -> url.newBuilder().encodedPath("/v1/images/generations").build()
            "/v1" -> url.newBuilder().encodedPath("/v1/images/generations").build()
            else -> {
                require(path.endsWith("/images/generations")) { "特殊中转路径请在高级设置填写完整 endpoint" }
                require(path.indexOf("/images/generations") == path.lastIndexOf("/images/generations") &&
                    !path.contains("/v1/v1/")) { "生图地址包含重复路径" }
                url.newBuilder().encodedPath(path).build()
            }
        }
    }
}
