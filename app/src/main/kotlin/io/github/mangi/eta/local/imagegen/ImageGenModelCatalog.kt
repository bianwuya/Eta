package io.github.mangi.eta.local.imagegen

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.repository.RemoteModelFetcher
import io.github.mangi.eta.agent.model.AgentHttpClient
import okhttp3.OkHttpClient

/** Read-only catalog for an independent Images connection; never stores a chat provider. */
internal object ImageGenModelCatalog {
    fun catalogBase(input: String): String {
        val endpoint = ImageGenEndpoint.resolve(input)
        return endpoint.newBuilder().encodedPath(endpoint.encodedPath.removeSuffix("/images/generations")).build().toString().trimEnd('/')
    }

    suspend fun fetch(input: String, key: String, client: OkHttpClient = AgentHttpClient.client): Result<List<Model>> {
        if (key.isBlank()) return Result.failure(IllegalArgumentException("请先填写独立生图 Key"))
        val base = try { catalogBase(input) } catch (_: IllegalArgumentException) {
            return Result.failure(IllegalArgumentException("请填写有效的根 URL、/v1 或标准生图 endpoint"))
        }
        return RemoteModelFetcher.fetchOpenAiImageModels(base, key, client)
    }
}
