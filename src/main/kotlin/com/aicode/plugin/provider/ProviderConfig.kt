package com.aicode.plugin.provider

import java.io.Serializable
import java.util.UUID

/**
 * 一个 LLM Provider 的完整配置。
 *
 * 兼容所有实现 OpenAI Chat Completions 协议的接口：
 * - OpenAI:        https://api.openai.com/v1
 * - DeepSeek:      https://api.deepseek.com/v1
 * - OpenRouter:    https://openrouter.ai/api/v1
 * - Ollama 本地:   http://localhost:11434/v1
 * - vLLM / 自部署: http://<host>:8000/v1
 */
data class ProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var baseUrl: String = "https://api.openai.com/v1",
    var apiKey: String = "",
    var model: String = "gpt-4o-mini",
    var temperature: Double = 0.7,
    var maxTokens: Int = 128,
    /** 额外的请求头，每行 "Key: Value" 解析而来。 */
    var extraHeaders: Map<String, String> = emptyMap(),
    var enabled: Boolean = true,
    /** 通过“自动添加模型”从服务端拉取并勾选保存的模型列表（可为空）。 */
    var models: MutableList<String> = mutableListOf()
) : Serializable {

    /** Chat Completions 完整端点。 */
    fun chatCompletionsUrl(): String = baseUrl.trimEnd('/') + "/chat/completions"

    override fun toString(): String = name
}
