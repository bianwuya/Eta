package io.github.mangi.eta.agent.model

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 模块全局 OkHttp 客户端。
 *
 * 模型流与普通 HTTP 请求共享连接池，但独立设置读取等待与重试策略。
 */
internal object AgentHttpClient {

    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val READ_TIMEOUT_MS = 60_000L
    private const val WRITE_TIMEOUT_MS = 30_000L

    const val MODEL_READ_TIMEOUT_MS = 300_000L

    val modelClient: OkHttpClient by lazy {
        client.newBuilder()
            .addInterceptor(io.github.mangi.eta.local.subagentstats.SubagentRequestStats.interceptor)
            .readTimeout(MODEL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val compactionClient: OkHttpClient by lazy {
        modelClient.newBuilder()
            .callTimeout(MODEL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    private val http1ModelClient: OkHttpClient by lazy {
        modelClient.newBuilder().protocols(listOf(okhttp3.Protocol.HTTP_1_1)).build()
    }
    private val http1CompactionClient: OkHttpClient by lazy {
        compactionClient.newBuilder().protocols(listOf(okhttp3.Protocol.HTTP_1_1)).build()
    }

    fun modelClient(purpose: ProviderRequestPurpose, forceHttp1: Boolean = false): OkHttpClient = when {
        forceHttp1 && purpose == ProviderRequestPurpose.COMPACTION -> http1CompactionClient
        forceHttp1 -> http1ModelClient
        purpose == ProviderRequestPurpose.COMPACTION -> compactionClient
        else -> modelClient
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }
}
