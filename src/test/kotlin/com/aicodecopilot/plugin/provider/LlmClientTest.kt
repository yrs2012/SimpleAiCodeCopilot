package com.aicodecopilot.plugin.provider

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.After
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList

/**
 * LlmClient 集成测试：用 JDK 内置 HttpServer 在 127.0.0.1 起一个假 LLM 服务，
 * 验证请求构造、SSE 解析、整包 JSON 兜底、错误处理与取消逻辑。
 * 不依赖 IDE 环境。
 */
class LlmClientTest {

    private lateinit var server: HttpServer
    private var port = 0

    // 每个测试可配置的假服务端行为
    private var status = 200
    private var contentType = "application/json"
    private var body = ""
    private var chunkDelayMs = 0L

    private val receivedBodies = CopyOnWriteArrayList<String>()
    private val receivedHeaderSnaps = CopyOnWriteArrayList<Map<String, String?>>()

    @Before
    fun setUp() {
        status = 200
        contentType = "application/json"
        body = ""
        chunkDelayMs = 0L
        receivedBodies.clear()
        receivedHeaderSnaps.clear()

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange ->
            val reqBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            receivedBodies.add(reqBody)
            receivedHeaderSnaps.add(
                mapOf(
                    "authorization" to exchange.requestHeaders.getFirst("Authorization"),
                    "content-type" to exchange.requestHeaders.getFirst("Content-Type"),
                    "accept" to exchange.requestHeaders.getFirst("Accept"),
                    "x-custom" to exchange.requestHeaders.getFirst("X-Custom")
                )
            )
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", contentType)
            val out = exchange.responseBody
            try {
                if (chunkDelayMs > 0) {
                    // 分块发送（SSE 模拟，块之间有延迟）
                    exchange.sendResponseHeaders(status, 0L)
                    body.lineSequence().forEach { line ->
                        out.write(("$line\n").toByteArray(StandardCharsets.UTF_8))
                        out.flush()
                        Thread.sleep(chunkDelayMs)
                    }
                } else {
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    out.write(bytes)
                }
            } finally {
                out.close()
            }
        }
        server.start()
        port = server.address.port
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun provider(extraHeaders: Map<String, String> = emptyMap()) = ProviderConfig(
        name = "Fake",
        baseUrl = "http://127.0.0.1:$port/v1",
        apiKey = "sk-test",
        model = "test-model",
        extraHeaders = extraHeaders
    )

    private val sseBody =
        "data: {\"choices\":[{\"delta\":{\"content\":\"he\"}}]}" + "\n" +
        "data: {\"choices\":[{\"delta\":{\"content\":\"llo\"}}]}" + "\n" +
        "data: [DONE]"

    @Test
    fun nonStream_jsonResponse() {
        body = """{"choices":[{"message":{"content":"hello world"}}]}"""
        val result = LlmClient.chat(
            provider(),
            listOf(LlmClient.ChatMessage("user", "hi")),
            stream = false,
            AtomicBoolean()
        ) { }
        assertEquals("hello world", result)
    }

    @Test
    fun requestBodyAndHeaders() {
        body = """{"choices":[{"message":{"content":"ok"}}]}"""
        LlmClient.chat(
            provider(extraHeaders = mapOf("X-Custom" to "abc")),
            listOf(LlmClient.ChatMessage("system", "sys"), LlmClient.ChatMessage("user", "hi there")),
            stream = false,
            AtomicBoolean()
        ) { }

        assertEquals(1, receivedBodies.size)
        val req = JSONObject(receivedBodies[0])
        assertEquals("test-model", req.getString("model"))
        assertEquals(false, req.getBoolean("stream"))
        assertEquals(2, req.getJSONArray("messages").length())
        assertEquals("system", req.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("hi there", req.getJSONArray("messages").getJSONObject(1).getString("content"))

        val h = receivedHeaderSnaps[0]
        assertEquals("Bearer sk-test", h["authorization"])
        assertEquals("application/json", h["content-type"])
        assertEquals("application/json", h["accept"])
        assertEquals("abc", h["x-custom"])
    }

    @Test
    fun sse_streamResponse() {
        contentType = "text/event-stream"
        body = sseBody
        val deltas = CopyOnWriteArrayList<String>()
        val result = LlmClient.chat(
            provider(),
            listOf(LlmClient.ChatMessage("user", "hi")),
            stream = true,
            AtomicBoolean()
        ) { deltas.add(it) }
        assertEquals("hello", result)
        assertEquals(listOf("he", "llo"), deltas)
    }

    @Test
    fun sse_withoutContentType_sniffsAndParses() {
        // 部分服务流式时不设置 text/event-stream，客户端应能嗅探
        contentType = "application/octet-stream"
        body = sseBody
        val result = LlmClient.chat(
            provider(),
            listOf(LlmClient.ChatMessage("user", "hi")),
            stream = true,
            AtomicBoolean()
        ) { }
        assertEquals("hello", result)
    }

    @Test
    fun sse_streamIncludesStreamFlag() {
        contentType = "text/event-stream"
        body = sseBody
        LlmClient.chat(provider(), listOf(LlmClient.ChatMessage("user", "hi")), stream = true, AtomicBoolean()) { }
        val req = JSONObject(receivedBodies[0])
        assertEquals(true, req.getBoolean("stream"))
        assertEquals("text/event-stream", receivedHeaderSnaps[0]["accept"])
    }

    @Test
    fun httpError_throwsWithStatus() {
        status = 401
        body = """{"error":{"message":"Invalid API key"}}"""
        try {
            LlmClient.chat(provider(), listOf(LlmClient.ChatMessage("user", "hi")), false, AtomicBoolean()) { }
            fail("expected LlmException")
        } catch (e: LlmClient.LlmException) {
            assertEquals(401, e.httpStatus)
            assertTrue(e.message!!.contains("401"))
            assertTrue(e.message!!.contains("Invalid API key"))
        }
    }

    @Test
    fun serverErrorInBody_throws() {
        body = """{"error":{"message":"boom"}}"""
        try {
            LlmClient.chat(provider(), listOf(LlmClient.ChatMessage("user", "hi")), false, AtomicBoolean()) { }
            fail("expected LlmException")
        } catch (e: LlmClient.LlmException) {
            assertTrue(e.message!!.contains("boom"))
        }
    }

    @Test
    fun cancel_stopsReadingEarly() {
        contentType = "text/event-stream"
        // 5 行 × 400ms：不取消的话要读完 3rd/4th 行才结束（≥1600ms）
        body = "data: {\"choices\":[{\"delta\":{\"content\":\"he\"}}]}" + "\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"llo\"}}]}" + "\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\" wo\"}}]}" + "\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"rld\"}}]}" + "\n" +
            "data: [DONE]"
        chunkDelayMs = 400L
        val cancel = AtomicBoolean(false)

        val start = System.nanoTime()
        val future = Thread {
            try {
                LlmClient.chat(provider(), listOf(LlmClient.ChatMessage("user", "hi")), true, cancel) { }
            } catch (_: Exception) {
                // 忽略
            }
        }.apply { start() }

        Thread.sleep(150)
        cancel.set(true)
        future.join(5000)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

        assertFalse("cancel 未生效：线程仍在运行", future.isAlive)
        // 取消后在读完第 2 行（~450ms）即应退出；不取消则需 ≥1600ms
        assertTrue("取消后应尽快返回，实际 ${elapsedMs}ms", elapsedMs < 1200)
    }

    @Test
    fun testConnection_usesSmallRequest() {
        body = """{"choices":[{"message":{"content":"ok"}}]}"""
        val reply = LlmClient.testConnection(provider())
        assertEquals("ok", reply)
        val req = JSONObject(receivedBodies[0])
        // 连通性测试发送一条固定的极小消息
        assertEquals(
            "Reply with the single word: ok",
            req.getJSONArray("messages").getJSONObject(0).getString("content")
        )
        assertEquals(false, req.getBoolean("stream"))
    }

    @Test
    fun listModels_parsesDataArray_dedupKeepOrder() {
        body = """{"data":[{"id":"m1"},{"id":"m2"},{"id":"m1"},{"id":"m3"}]}"""
        val models = LlmClient.listModels(provider())
        assertEquals(listOf("m1", "m2", "m3"), models)
    }

    @Test
    fun listModels_emptyDataArray_returnsEmpty() {
        body = """{"data":[]}"""
        assertTrue(LlmClient.listModels(provider()).isEmpty())
    }

    @Test
    fun listModels_httpError_throwsWithStatus() {
        status = 401
        body = """{"error":{"message":"no key"}}"""
        try {
            LlmClient.listModels(provider())
            fail("expected LlmException")
        } catch (e: LlmClient.LlmException) {
            assertEquals(401, e.httpStatus)
            assertTrue(e.message!!.contains("401"))
        }
    }
}
