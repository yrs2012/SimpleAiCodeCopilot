package com.aicodecopilot.plugin.provider

import org.junit.Assert.assertEquals
import org.junit.Test

/** ProviderConfig 纯逻辑测试（无需 IDE 环境）。 */
class ProviderConfigTest {

    @Test
    fun chatCompletionsUrl_appendsPath() {
        val p = ProviderConfig(baseUrl = "https://api.openai.com/v1")
        assertEquals("https://api.openai.com/v1/chat/completions", p.chatCompletionsUrl())
    }

    @Test
    fun chatCompletionsUrl_trimsTrailingSlash() {
        val p = ProviderConfig(baseUrl = "http://localhost:11434/v1/")
        assertEquals("http://localhost:11434/v1/chat/completions", p.chatCompletionsUrl())
    }

    @Test
    fun chatCompletionsUrl_multipleTrailingSlashes() {
        val p = ProviderConfig(baseUrl = "https://host/v1///")
        assertEquals("https://host/v1/chat/completions", p.chatCompletionsUrl())
    }

    @Test
    fun toString_returnsName() {
        assertEquals("My Provider", ProviderConfig(name = "My Provider").toString())
    }

    @Test
    fun copy_preservesId() {
        val original = ProviderConfig(name = "A")
        val copied = original.copy(name = "B")
        assertEquals(original.id, copied.id)
        assertEquals("B", copied.name)
    }

    @Test
    fun defaults() {
        val p = ProviderConfig()
        assertEquals("https://api.openai.com/v1", p.baseUrl)
        assertEquals("gpt-4o-mini", p.model)
        assertEquals(0.7, p.temperature, 0.001)
        assertEquals(128, p.maxTokens)
        assertEquals(true, p.enabled)
        assertEquals(emptyMap<String, String>(), p.extraHeaders)
    }
}
