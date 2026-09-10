package com.aicodecopilot.plugin.provider

import com.intellij.openapi.diagnostic.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenAI 兼容 Chat Completions 客户端。
 * 使用 JDK 自带的 java.net.http，无第三方 HTTP 依赖。
 */
object LlmClient {

    private val log = Logger.getInstance(LlmClient::class.java)

    data class ChatMessage(val role: String, val content: String)

    class LlmException(message: String, val httpStatus: Int = -1) : Exception(message)

    /**
     * 构造“直连”HttpClient（不走系统/OS 代理）。
     * LLM 端点是用户显式填写的 base_url（多为自部署/内网/直连公网 API），
     * 若被系统代理拦截，内网地址往往无法经代理触达 → 表现为请求长时间挂起。
     * 因此这里显式 [ProxySelector] 置空，保证直连。
     */
    private fun newHttpClient(connectTimeout: Duration): HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .proxy(ProxySelector.of(null))
            .build()

    /**
     * 发起一次对话补全。
     *
     * @param stream   是否使用 SSE 流式输出
     * @param cancel   取消标志：置位后读取循环尽快退出，[onDelta] 中已累计的部分内容仍会返回
     * @param onDelta  流式增量回调（在调用线程上执行，UI 需自行切 EDT）
     * @param connectTimeout 建连超时
     * @param requestTimeout 请求（首字节/整包）超时
     * @return 完整回复文本
     */
    fun chat(
        provider: ProviderConfig,
        messages: List<ChatMessage>,
        stream: Boolean,
        cancel: AtomicBoolean,
        connectTimeout: Duration = Duration.ofSeconds(30),
        requestTimeout: Duration = Duration.ofMinutes(10),
        onDelta: (String) -> Unit
    ): String {
        val url = provider.chatCompletionsUrl()
        val request = buildRequest(provider, messages, stream, requestTimeout)
        val client = newHttpClient(connectTimeout)
        val start = System.currentTimeMillis()
        log.warn("AiCodeCopilot: 发起 chat 请求 $url (model=${provider.model}, stream=$stream, 请求超时=${requestTimeout.toSeconds()}s)")
        log.warn("AiCodeCopilot: 本次调用 LLM —— provider=${provider.name}, model=${provider.model}")
        // 用 sendAsync + get(timeout) 强制限制整条链路（DNS + 建连 + 收头 + 收体）的墙钟时间，
        // 避免 connectTimeout / requestTimeout 不覆盖 DNS 解析或 body 读取导致的无限挂起。
        val future: CompletableFuture<HttpResponse<InputStream>> = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
        val response: HttpResponse<InputStream> = try {
            future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            log.warn("AiCodeCopilot: chat 请求超时（${System.currentTimeMillis() - start}ms）: $url", e)
            throw LlmException("请求超时（${requestTimeout.toSeconds()}s 无响应）：$url")
        } catch (e: ExecutionException) {
            future.cancel(true)
            val cause = e.cause
            when (cause) {
                is java.net.http.HttpTimeoutException -> throw LlmException("请求超时（${requestTimeout.toSeconds()}s 无响应）：$url")
                is java.net.ConnectException -> throw LlmException("无法连接到 $url（${cause.message ?: "连接被拒绝或超时，请检查 base_url 是否正确、服务是否可达"}）")
                is java.net.UnknownHostException -> throw LlmException("域名解析失败：${cause.message}")
                else -> throw cause ?: LlmException("chat 请求失败：${e.message}")
            }
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw LlmException("chat 请求被中断")
        }
        log.warn("AiCodeCopilot: chat 响应 HTTP ${response.statusCode()}（${System.currentTimeMillis() - start}ms）")

        if (response.statusCode() !in 200..299) {
            val errBody = runCatching { response.body().reader(StandardCharsets.UTF_8).readText() }
                .getOrDefault("").take(500)
            response.body().close()
            log.warn("AiCodeCopilot: chat 请求失败 HTTP ${response.statusCode()}：$errBody")
            throw LlmException("HTTP ${response.statusCode()} 请求失败：${errBody.ifBlank { "(无响应体)" }}", response.statusCode())
        }

        val contentType = response.headers().firstValue("Content-Type").orElse("")
        val result = if (stream && contentType.contains("text/event-stream")) {
            readSse(response.body(), cancel, onDelta)
        } else if (stream) {
            // 有些服务器流式时不设置 Content-Type，按首行嗅探
            sniffAndRead(response.body(), cancel, onDelta)
        } else {
            val text = response.body().reader(StandardCharsets.UTF_8).readText()
            response.body().close()
            readPlainJson(text)
        }
        log.warn("AiCodeCopilot: chat 完成（共 ${System.currentTimeMillis() - start}ms，回复 ${result.length} 字符）")
        return result
    }

    /** 连通性测试：发一个极小的请求，返回模型回复（或抛异常）。超时较短，快速给出成败。 */
    fun testConnection(provider: ProviderConfig): String {
        log.warn("AiCodeCopilot: 测试连接 ${provider.name} (${provider.baseUrl}) model=${provider.model}")
        val messages = listOf(ChatMessage("user", "Reply with the single word: ok"))
        return chat(
            provider, messages, stream = false, AtomicBoolean(), onDelta = {},
            connectTimeout = Duration.ofSeconds(15),
            requestTimeout = Duration.ofSeconds(15)
        )
    }

    /**
     * 拉取服务端的模型列表（OpenAI 兼容 `GET {baseUrl}/models`）。
     * 返回模型 id 列表（保序、去重）；非 2xx 时抛 [LlmException]。
     */
    fun listModels(provider: ProviderConfig): List<String> {
        val client = newHttpClient(Duration.ofSeconds(15))
        val url = provider.baseUrl.trimEnd('/') + "/models"
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("Accept", "application/json")
        if (provider.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${provider.apiKey.trim()}")
        }
        provider.extraHeaders.forEach { (k, v) ->
            if (k.isNotBlank()) runCatching { builder.header(k.trim(), v) }
                .onFailure { log.warn("AiCodeCopilot: 忽略非法请求头 $k", it) }
        }
        val start = System.currentTimeMillis()
        log.warn("AiCodeCopilot: 获取模型列表 $url")
        val future: CompletableFuture<HttpResponse<String>> = client.sendAsync(builder.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        val response: HttpResponse<String> = try {
            future.get(60, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            log.warn("AiCodeCopilot: 获取模型列表超时（${System.currentTimeMillis() - start}ms）: $url", e)
            throw LlmException("获取模型列表超时（60s 无响应）：$url")
        } catch (e: ExecutionException) {
            future.cancel(true)
            val cause = e.cause
            when (cause) {
                is java.net.http.HttpTimeoutException -> throw LlmException("获取模型列表超时（60s 无响应）：$url")
                is java.net.ConnectException -> throw LlmException("无法连接到 $url（${cause.message ?: "连接被拒绝或超时，请检查 base_url 是否正确、服务是否可达"}）")
                is java.net.UnknownHostException -> throw LlmException("域名解析失败：${cause.message}")
                else -> throw cause ?: LlmException("获取模型列表失败：${e.message}")
            }
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw LlmException("获取模型列表被中断")
        }
        log.warn("AiCodeCopilot: 模型列表响应 HTTP ${response.statusCode()}（${System.currentTimeMillis() - start}ms）")

        if (response.statusCode() !in 200..299) {
            val errBody = response.body().take(500)
            log.warn("AiCodeCopilot: 获取模型列表失败 HTTP ${response.statusCode()}：$errBody")
            throw LlmException("HTTP ${response.statusCode()} 获取模型列表失败：${errBody.ifBlank { "(无响应体)" }}", response.statusCode())
        }
        val obj = JSONObject(response.body())
        val arr = obj.optJSONArray("data") ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val id = m.optString("id", "")
            if (id.isNotBlank()) out.add(id)
        }
        log.warn("AiCodeCopilot: 获取模型列表成功，共 ${out.size} 个模型（${System.currentTimeMillis() - start}ms）")
        return out.toList()
    }

    // ------------------------------------------------------------------
    // 请求构造
    // ------------------------------------------------------------------

    private fun buildRequest(
        provider: ProviderConfig,
        messages: List<ChatMessage>,
        stream: Boolean,
        requestTimeout: Duration = Duration.ofMinutes(10)
    ): HttpRequest {
        val root = JSONObject()
        root.put("model", provider.model)
        val arr = JSONArray()
        messages.forEach { m ->
            arr.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        root.put("messages", arr)
        if (provider.temperature >= 0.0) root.put("temperature", provider.temperature)
        if (provider.maxTokens > 0) root.put("max_tokens", provider.maxTokens * 1024)
        root.put("stream", stream)

        val builder = HttpRequest.newBuilder()
            .uri(URI.create(provider.chatCompletionsUrl()))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .header("Accept", if (stream) "text/event-stream" else "application/json")

        if (provider.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${provider.apiKey.trim()}")
        }
        provider.extraHeaders.forEach { (k, v) ->
            if (k.isNotBlank()) runCatching { builder.header(k.trim(), v) }
                .onFailure { log.warn("AiCodeCopilot: 忽略非法请求头 $k", it) }
        }
        return builder.POST(HttpRequest.BodyPublishers.ofString(root.toString(), StandardCharsets.UTF_8)).build()
    }

    // ------------------------------------------------------------------
    // 响应解析
    // ------------------------------------------------------------------

    private fun readPlainJson(text: String): String {
        val obj = JSONObject(text)
        if (obj.has("error")) {
            throw LlmException("服务端返回错误：${obj.get("error")}")
        }
        val choices = obj.optJSONArray("choices") ?: return ""
        if (choices.length() == 0) return ""
        val first = choices.getJSONObject(0)
        return first.optJSONObject("message")?.optString("content") ?: ""
    }

    /**
     * 读取 SSE 流。某些服务（含 Ollama 的 OpenAI 兼容模式）可能返回普通 JSON，
     * 这里做兜底处理。
     */
    private fun readSse(body: InputStream, cancel: AtomicBoolean, onDelta: (String) -> Unit): String {
        val sb = StringBuilder()
        val reader = BufferedReader(InputStreamReader(body, StandardCharsets.UTF_8))
        try {
            var first = true
            line@ while (true) {
                if (cancel.get()) break
                val line = reader.readLine() ?: break
                val t = line.trim()
                if (t.isEmpty()) continue
                when {
                    t.startsWith("data:") -> {
                        val data = t.removePrefix("data:").trim()
                        if (data == "[DONE]") break@line
                        appendDelta(data)?.let {
                            sb.append(it)
                            onDelta(it)
                        }
                    }
                    first && t.startsWith("{") -> {
                        // 实际是整包 JSON（非 SSE），按普通响应处理
                        val obj = JSONObject(t)
                        val content = obj.optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?.optString("content") ?: ""
                        if (content.isNotEmpty()) {
                            sb.append(content)
                            onDelta(content)
                        }
                    }
                }
                first = false
            }
        } finally {
            runCatching { reader.close() }
        }
        return sb.toString()
    }

    private fun sniffAndRead(body: InputStream, cancel: AtomicBoolean, onDelta: (String) -> Unit): String {
        // 先缓冲读取开头 64KB，判断是 SSE 还是整包 JSON
        val buffer = java.io.ByteArrayOutputStream()
        val source = body.reader(StandardCharsets.UTF_8)
        val chunk = CharArray(8192)
        var total = 0
        while (total < 65536) {
            val n = source.read(chunk, 0, chunk.size)
            if (n < 0) break
            buffer.write(String(chunk, 0, n).toByteArray(StandardCharsets.UTF_8))
            total += n
        }
        val head = buffer.toString(StandardCharsets.UTF_8)
        return if (head.trimStart().startsWith("data:")) {
            // 把已读部分放回，整体按 SSE 处理
            readSse(java.io.SequenceInputStream(head.byteInputStream(StandardCharsets.UTF_8), body), cancel, onDelta)
        } else {
            val rest = StringBuilder()
            var n = source.read(chunk)
            while (n >= 0) {
                rest.append(chunk, 0, n)
                if (cancel.get()) break
                n = source.read(chunk)
            }
            readPlainJson(head + rest)
        }
    }

    private fun appendDelta(data: String): String? {
        return try {
            val obj = JSONObject(data)
            if (obj.has("error")) {
                throw LlmException("服务端返回错误：${obj.get("error")}")
            }
            val choices = obj.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val choice = choices.getJSONObject(0)
            choice.optJSONObject("delta")?.optString("content")?.takeIf { it.isNotEmpty() }
                ?: choice.optJSONObject("message")?.optString("content")?.takeIf { it.isNotEmpty() }
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            null // 心跳/注释行等，忽略
        }
    }
}
