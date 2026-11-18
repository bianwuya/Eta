package io.github.mangi.eta.local.imagegen

import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class ImageGenModelCandidate(val modelId: String, val name: String = modelId)

internal object ImageGenModelCatalog {
    private val imageMarkers = listOf("gpt-image", "chatgpt-image", "dall-e", "flux", "stable-diffusion", "wanx", "hidream", "qwen-image", "seedream")
    private const val MAX_CATALOG_BYTES = 4L * 1024 * 1024

    suspend fun fetch(
        endpoint: HttpUrl,
        apiKey: String,
        client: OkHttpClient = AgentHttpClient.client,
    ): List<ImageGenModelCandidate> = withContext(Dispatchers.IO) {
        require(ImageGenEndpoint.resolve(endpoint.toString()) == endpoint && isImageGenApiKey(apiKey))
        suspendCancellableCoroutine { continuation ->
            val controller = AgentRunController()
            continuation.invokeOnCancellation { controller.cancel() }
            try {
                val request = Request.Builder().url(ImageGenEndpoint.models(endpoint))
                    .header("Authorization", "Bearer $apiKey")
                    .header("Accept", "application/json")
                    .get().build()
                val call = client.imageGenBuilder().callTimeout(30, TimeUnit.SECONDS).build().newCall(request)
                val binding = controller.register(call::cancel)
                try {
                    controller.throwIfCancelled()
                    val candidates = call.execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Models HTTP ${response.code}")
                        parse(requireNotNull(response.body).boundedImageGenJson(MAX_CATALOG_BYTES))
                    }
                    continuation.resume(candidates)
                } finally {
                    binding.close()
                }
            } catch (failure: Exception) {
                continuation.resumeWithException(failure)
            }
        }
    }

    internal fun parse(body: String): List<ImageGenModelCandidate> {
        val root = JSONObject(body)
        require(root.isNull("error")) { "Image model directory returned an error" }
        val entries = root.getJSONArray("data")
        return buildList {
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                val id = (entry.opt("id") as? String)?.takeIf(::isImageGenModelId) ?: continue
                val output = entry.outputModalities() ?: entry.optJSONObject("architecture")?.outputModalities()
                val image = if (output == null || output.length() == 0) {
                    imageMarkers.any { id.contains(it, ignoreCase = true) }
                } else {
                    (0 until output.length()).any { output.optString(it).equals("image", ignoreCase = true) }
                }
                if (image) {
                    add(ImageGenModelCandidate(id, (entry.opt("name") as? String)?.takeIf { it.isNotBlank() } ?: id))
                }
            }
        }.distinctBy { it.modelId }
    }

    private fun JSONObject.outputModalities(): JSONArray? =
        optJSONArray("output_modalities") ?: optJSONArray("outputModalities")
}

// Keep the shared connection pool, but never redirect an independent bearer key or retry a paid image request.
internal fun OkHttpClient.imageGenBuilder(): OkHttpClient.Builder = newBuilder()
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)

internal fun ResponseBody.boundedImageGenJson(maxBytes: Long): String {
    require(contentLength() <= maxBytes)
    val source = source()
    require(!source.request(maxBytes + 1))
    return source.readUtf8()
}
