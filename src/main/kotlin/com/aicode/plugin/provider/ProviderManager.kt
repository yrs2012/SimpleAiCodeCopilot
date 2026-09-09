package com.aicode.plugin.provider

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.io.Serializable

/**
 * 持久化状态（[ProviderManager] 的 @State 载体）。
 *
 * 必须是**顶层类**（不能是 inner/nested）：平台的 KotlinAwareBeanBinding
 * 通过 Kotlin 反射 `callBy` 调无参构造器实例化它，inner 类的构造器带隐式的
 * 外部实例参数（`instance`），反射无法提供 → 序列化时抛
 * `IllegalArgumentException: No argument provided for a required parameter: instance`。
 */
class ProviderManagerState : Serializable {
    var providers: MutableList<ProviderConfig> = mutableListOf()
    var activeProviderId: String? = null
}

/**
 * Provider 配置管理器（应用级服务）。
 * 配置持久化在 <config>/options/aicode.xml，重启 IDE 后仍然保留。
 */
@State(name = "AiCodeProviderSettings", storages = [Storage("aicode.xml")])
class ProviderManager : PersistentStateComponent<ProviderManagerState> {

    @Volatile
    private var state: ProviderManagerState? = null

    override fun getState(): ProviderManagerState? = state ?: createDefaultState()

    override fun loadState(state: ProviderManagerState) {
        this.state = state
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    fun all(): List<ProviderConfig> = ensureDefaults().providers.toList()

    fun enabled(): List<ProviderConfig> = all().filter { it.enabled }

    fun byId(id: String?): ProviderConfig? = id?.let { all().firstOrNull { p -> p.id == it } }

    /** 当前激活的 Provider：优先用户选择的，其次第一个启用的。 */
    fun active(): ProviderConfig? {
        val list = ensureDefaults()
        val chosen = list.providers.firstOrNull { it.id == list.activeProviderId }
        return chosen ?: list.providers.firstOrNull { it.enabled }
    }

    // ------------------------------------------------------------------
    // 变更（每次都立即落盘）
    // ------------------------------------------------------------------

    fun add(provider: ProviderConfig) {
        val s = ensureDefaults()
        s.providers.add(provider)
        if (s.activeProviderId == null) s.activeProviderId = provider.id
        commit(s)
    }

    fun update(provider: ProviderConfig) {
        val s = ensureDefaults()
        val index = s.providers.indexOfFirst { it.id == provider.id }
        if (index >= 0) s.providers[index] = provider
        commit(s)
    }

    fun remove(id: String) {
        val s = ensureDefaults()
        s.providers.removeAll { it.id == id }
        if (s.activeProviderId == id) {
            // 回退到第一个启用的 Provider（跳过禁用项，如默认的本地 Ollama）
            s.activeProviderId = s.providers.firstOrNull { it.enabled }?.id
        }
        commit(s)
    }

    fun setActive(id: String) {
        val s = ensureDefaults()
        s.activeProviderId = id
        commit(s)
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun commit(s: ProviderManagerState) {
        state = s
    }

    private fun ensureDefaults(): ProviderManagerState {
        val s = state ?: return createDefaultState().also { state = it }
        if (s.providers.isEmpty()) {
            s.providers = presets().toMutableList()
            s.activeProviderId = s.providers.firstOrNull()?.id
        }
        return s
    }

    private fun createDefaultState(): ProviderManagerState = ProviderManagerState().apply {
        providers = presets().toMutableList()
        activeProviderId = providers.firstOrNull()?.id
    }

    /** 预置的常用 Provider，用户可直接填写 API Key 使用，或自由增删改。 */
    private fun presets(): List<ProviderConfig> = listOf(
        ProviderConfig(
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4o-mini",
            temperature = 0.7,
            maxTokens = 128
        ),
        ProviderConfig(
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
            temperature = 0.7,
            maxTokens = 128
        ),
        ProviderConfig(
            name = "Ollama (本地)",
            baseUrl = "http://localhost:11434/v1",
            model = "llama3.2",
            temperature = 0.7,
            maxTokens = 128,
            enabled = false
        )
    )

    companion object {
        fun getInstance(): ProviderManager =
            ApplicationManager.getApplication().getService(ProviderManager::class.java)
    }
}
