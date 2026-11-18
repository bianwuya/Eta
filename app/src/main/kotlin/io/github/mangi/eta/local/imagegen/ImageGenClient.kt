package io.github.mangi.eta.local.imagegen

import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject

internal class ImageGenClient(
    private val saveImage: (String, AgentRunController) -> ImageGenOutput.Saved,
    client: OkHttpClient = AgentHttpClient.client,
) {
    private val http = client.imageGenBuilder()
        .readTimeout(300, TimeUnit.SECONDS)
        .callTimeout(360, TimeUnit.SECONDS)
        .build()

    fun generate(prompt: String, controller: AgentRunController, connection: ImageGenConnection): JSONObject {
        controller.throwIfCancelled()
        val body = JSONObject().put("prompt", prompt).put("model", connection.model).put("n", 1)
        if (connection.model.startsWith("dall-e", ignoreCase = true)) body.put("response_format", "b64_json")
        val jsonBody = body.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(connection.endpoint)
            .header("Authorization", "Bearer ${connection.apiKey}")
            .header("Accept", "application/json")
            .post(object : RequestBody() {
                // OkHttp can otherwise follow up on some server responses even with connection retry off.
                override fun isOneShot(): Boolean = true
                override fun contentType() = jsonBody.contentType()
                override fun contentLength() = jsonBody.contentLength()
                override fun writeTo(sink: BufferedSink) = jsonBody.writeTo(sink)
            })
            .build()
        val call = http.newCall(request)
        val cancellation = controller.register(call::cancel)
        return try {
            controller.throwIfCancelled()
            call.execute().use { response ->
                controller.throwIfCancelled()
                if (!response.isSuccessful) {
                    val unknown = response.code == 408 || response.code >= 500
                    return imageGenFailure(
                        if (unknown) "IMAGE_GEN_OUTCOME_UNKNOWN" else "IMAGE_GEN_REJECTED",
                        "生图接口返回 HTTP ${response.code}，未自动重试。" +
                            if (unknown) "服务端可能已扣额，请先核对。" else "请检查独立 API Key、模型和服务端权限。",
                        unknown,
                    )
                }
                val data = JSONObject(requireNotNull(response.body).boundedImageGenJson(MAX_RESPONSE_BYTES)).getJSONArray("data")
                val encoded = (data.optJSONObject(0)?.opt("b64_json") as? String).orEmpty()
                require(data.length() == 1 && encoded.isNotBlank())
                val saved = saveImage(encoded, controller)
                JSONObject().put("ok", true).put("uri", saved.uri).put("location", saved.location)
                    .put("mime", saved.mime).put("width", saved.width).put("height", saved.height)
                    .put("model", connection.model)
                    .put("note", "图片已保存到下载目录。请告知用户 location，不要编造远程图片链接。")
            }
        } catch (_: IOException) {
            controller.throwIfCancelled()
            imageGenFailure("IMAGE_GEN_OUTCOME_UNKNOWN", "生图传输或保存中断，服务端可能已扣额。未自动重试，请先核对。", true)
        } catch (_: Exception) {
            controller.throwIfCancelled()
            imageGenFailure("IMAGE_GEN_RESULT_FAILED", "生图响应不是一张可保存的 Base64 图片，或保存失败。服务端可能已扣额，未自动重试。", true)
        } finally {
            cancellation.close()
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = ImageGenOutput.MAX_BASE64.toLong() + 1024 * 1024
    }
}

internal fun imageGenFailure(code: String, message: String, unknown: Boolean = false): JSONObject = JSONObject()
    .put("ok", false).put("code", code).put("message", message)
    .put("retryable", false).put("outcome_unknown", unknown)
