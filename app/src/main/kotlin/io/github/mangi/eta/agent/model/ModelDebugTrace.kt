package io.github.mangi.eta.agent.model

import android.content.Context
import android.util.Log
import io.github.mangi.eta.BuildConfig
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.UUID
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject

/** Debug only, bounded private log. Never stores headers, prompt/body text, IPs or exception messages. */
internal class ModelDebugTrace private constructor(private val id: String) : EventListener() {
    private val started = System.nanoTime()

    fun stage(name: String, fields: JSONObject = JSONObject()) {
        if (!BuildConfig.DEBUG) return
        fields.put("event", name).put("request", id)
            .put("elapsed_ms", (System.nanoTime() - started) / 1_000_000)
        write(fields)
    }

    fun failure(name: String, error: Throwable) {
        val types = JSONArray()
        val seen = mutableSetOf<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current) && types.length() < 8) {
            types.put(current.javaClass.name)
            current = current.cause
        }
        stage(name, JSONObject().put("exceptions", types)
            .put("reason_code", (error as? AgentModelFailure)?.code))
    }

    override fun callStart(call: Call) = stage("call_start")
    override fun dnsStart(call: Call, domainName: String) = stage("dns_start")
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) =
        stage("dns_end", JSONObject().put("addresses", inetAddressList.size))
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = stage("connect_start")
    override fun secureConnectStart(call: Call) = stage("tls_start")
    override fun secureConnectEnd(call: Call, handshake: Handshake?) =
        stage("tls_end", JSONObject().put("tls", handshake?.tlsVersion?.javaName))
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) =
        stage("connect_end", JSONObject().put("protocol", protocol?.toString()))
    override fun connectionAcquired(call: Call, connection: Connection) =
        stage("connection_acquired", JSONObject().put("protocol", connection.protocol().toString()))
    override fun requestHeadersEnd(call: Call, request: Request) = stage("request_headers_sent")
    override fun requestBodyEnd(call: Call, byteCount: Long) = stage("request_body_sent", JSONObject().put("bytes", byteCount))
    override fun responseHeadersStart(call: Call) = stage("response_headers_start")
    override fun responseHeadersEnd(call: Call, response: Response) = stage("response_headers",
        JSONObject().put("status", response.code).put("content_type", response.header("Content-Type")?.substringBefore(';'))
            .put("host", response.request.url.host).put("path", response.request.url.encodedPath))
    override fun responseBodyEnd(call: Call, byteCount: Long) = stage("response_body_end", JSONObject().put("bytes", byteCount))
    override fun responseFailed(call: Call, ioe: IOException) = failure("response_failed", ioe)
    override fun callFailed(call: Call, ioe: IOException) = failure("call_failed", ioe)
    override fun callEnd(call: Call) = stage("call_end")

    companion object {
        private val current = ThreadLocal<ModelDebugTrace>()
        private var directory: File? = null
        private val writeLock = Any()
        private const val MAX_BYTES = 1024 * 1024

        fun initialize(context: Context) {
            if (BuildConfig.DEBUG) directory = context.applicationContext.filesDir
        }

        fun begin(request: Request): ModelDebugTrace? {
            if (!BuildConfig.DEBUG) return null
            return runCatching {
                val trace = ModelDebugTrace(UUID.randomUUID().toString().take(12))
                val meta = JSONObject().put("host", request.url.host).put("path", request.url.encodedPath)
                val body = request.body
                if (body != null && !body.isOneShot() && !body.isDuplex()) {
                    val buffer = Buffer()
                    body.writeTo(buffer)
                    val raw = buffer.readUtf8()
                    val json = JSONObject(raw)
                    val messages = json.optJSONArray("messages") ?: json.optJSONArray("input") ?: JSONArray()
                    val systems = JSONArray()
                    for (index in 0 until messages.length()) {
                        val message = messages.optJSONObject(index) ?: continue
                        if (message.optString("role") in setOf("system", "developer")) systems.put(message)
                    }
                    meta.put("body_hash", hash(raw)).put("messages", messages.length())
                        .put("system_hash", hash(systems.toString()))
                        .put("tools_hash", hash(json.optJSONArray("tools")?.toString().orEmpty()))
                        .put("cache_key_hash", hash(json.optString("prompt_cache_key")))
                        .put("reasoning_hash", hash(json.opt("reasoning")?.toString().orEmpty() + json.optString("reasoning_effort")))
                        .put("model", json.optString("model"))
                }
                trace.stage("request_metadata", meta)
                trace
            }.getOrNull()
        }

        fun <T> consume(trace: ModelDebugTrace?, block: () -> T): T {
            if (trace == null) return block()
            val previous = current.get()
            current.set(trace)
            try {
                return block().also { trace.stage("provider_complete") }
            } catch (failure: Throwable) {
                trace.failure("provider_failed", failure)
                throw failure
            } finally {
                if (previous == null) current.remove() else current.set(previous)
            }
        }

        fun sse(name: String) { current.get()?.stage(name) }

        private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(20)

        private fun write(fields: JSONObject) {
            runCatching {
                fields.put("time_ms", System.currentTimeMillis()).put("pid", android.os.Process.myPid())
                val line = fields.toString()
                Log.i("EtaModelTrace", line)
                synchronized(writeLock) {
                    val dir = directory ?: return@synchronized
                    val log = File(dir, "model-debug-trace.jsonl")
                    if (log.length() >= MAX_BYTES) {
                        val previous = File(dir, "model-debug-trace.previous.jsonl")
                        if (previous.exists() && !previous.delete()) return@synchronized
                        if (!log.renameTo(previous)) return@synchronized
                    }
                    log.appendText(line + "\n")
                }
            }
        }
    }
}
