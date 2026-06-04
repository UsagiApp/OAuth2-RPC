package com.discord.oauth2rpc

import com.discord.oauth2rpc.utils.DEFAULTS
import com.discord.oauth2rpc.utils.DEFAULT_SUPER_PROPERTIES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.util.*

class API(val baseURL: String = DEFAULTS.API_BASE) : Closeable {
    private val client = OkHttpClient()

    val api: RouteBuilder get() = RouteBuilder(baseURL, client)

    override fun close() {
        client.dispatcher.executorService.shutdown()
    }
}

class OkHttpResponse(val response: okhttp3.Response) {
    val status: Int get() = response.code
    private var bodyConsumed = false

    fun bodyAsText(): String {
        bodyConsumed = true
        return response.body?.string() ?: ""
    }

    fun close() {
        if (!bodyConsumed) response.close()
    }
}

class RouteBuilder(
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val path: String = ""
) {

    operator fun get(key: String): RouteBuilder {
        val newPath = if (path.isEmpty()) key else "$path/$key"
        return RouteBuilder(baseUrl, client, newPath)
    }

    operator fun invoke(vararg args: Any?): RouteBuilder {
        val filtered = args.filterNotNull().map { it.toString() }
        if (filtered.isEmpty()) return this
        val suffix = filtered.joinToString("/")
        val newPath = if (path.isEmpty()) suffix else "$path/$suffix"
        return RouteBuilder(baseUrl, client, newPath)
    }

    suspend fun get(block: suspend RequestConfig.() -> Unit = {}): OkHttpResponse {
        return execute("GET", block)
    }

    suspend fun post(block: suspend RequestConfig.() -> Unit = {}): OkHttpResponse {
        return execute("POST", block)
    }

    suspend fun delete(block: suspend RequestConfig.() -> Unit = {}): OkHttpResponse {
        return execute("DELETE", block)
    }

    suspend fun patch(block: suspend RequestConfig.() -> Unit = {}): OkHttpResponse {
        return execute("PATCH", block)
    }

    suspend fun put(block: suspend RequestConfig.() -> Unit = {}): OkHttpResponse {
        return execute("PUT", block)
    }

    private suspend fun execute(method: String, block: suspend RequestConfig.() -> Unit): OkHttpResponse {
        val url = baseUrl.trimEnd('/') + "/" + path
        val config = RequestConfig().apply { block() }

        val superPropsBase64 = Base64.getEncoder().encodeToString(
            JsonObjectMapper.mapToJson(DEFAULT_SUPER_PROPERTIES).toByteArray()
        )

        val requestBuilder = Request.Builder().url(url)

        config.headers?.forEach { (k, v) -> requestBuilder.header(k, v) }
        requestBuilder.header("User-Agent", DEFAULTS.USER_AGENT)
        requestBuilder.header("X-Super-Properties", superPropsBase64)

        val contentType = config.headers?.get("Content-Type") ?: "application/json"
        val body = config.body?.let {
            when (it) {
                is String -> it.toRequestBody(contentType.toMediaType())
                is ByteArray -> it.toRequestBody(contentType.toMediaType())
                else -> it.toString().toRequestBody(contentType.toMediaType())
            }
        }

        requestBuilder.method(method, body)

        return withContext(Dispatchers.IO) {
            val response = client.newCall(requestBuilder.build()).execute()
            OkHttpResponse(response)
        }
    }

    override fun toString(): String = path
}

class RequestConfig {
    var headers: Map<String, String>? = null
    var body: Any? = null
}

object JsonObjectMapper {
    fun mapToJson(map: Map<String, Any?>): String {
        val sb = StringBuilder()
        serialize(sb, map)
        return sb.toString()
    }

    private fun serialize(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> {
                sb.append('"')
                value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").also { sb.append(it) }
                sb.append('"')
            }
            is Number -> sb.append(value)
            is Boolean -> sb.append(value)
            is Map<*, *> -> {
                sb.append('{')
                val entries = value.entries.toList()
                for ((i, entry) in entries.withIndex()) {
                    if (i > 0) sb.append(',')
                    serialize(sb, entry.key.toString())
                    sb.append(':')
                    serialize(sb, entry.value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                val list = value.toList()
                for ((i, item) in list.withIndex()) {
                    if (i > 0) sb.append(',')
                    serialize(sb, item)
                }
                sb.append(']')
            }
            else -> sb.append(value)
        }
    }
}
