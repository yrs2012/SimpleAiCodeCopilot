package com.aicodecopilot.plugin.provider

import com.aicodecopilot.plugin.chat.ChatController
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.io.Serializable
import java.util.UUID

/**
 * 一套系统提示词模板。
 */
data class PromptTemplate(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var content: String = ""
) : Serializable {
    override fun toString(): String = name
}

/**
 * 持久化状态（[PromptManager] 的 @State 载体）。
 *
 * 必须是**顶层类**（不能是 inner/nested）：平台的 KotlinAwareBeanBinding
 * 通过 Kotlin 反射 `callBy` 调无参构造器实例化它，inner 类的构造器带隐式的
 * 外部实例参数，反射无法提供 → 序列化时抛
 * `IllegalArgumentException: No argument provided for a required parameter: instance`
 * （同 [ProviderManagerState] 的约束）。
 */
class PromptManagerState : Serializable {
    var templates: MutableList<PromptTemplate> = mutableListOf()
    var activeTemplateId: String? = null
    var memory: String = ""
}

/**
 * 系统提示词模板 + 永久 Memory 管理器（应用级服务）。
 * 配置持久化在 <config>/options/aicodeprompts.xml。
 */
@State(
    name = "AiCodePromptSettings",
    storages = [Storage("aicodeprompts.xml")]
)
class PromptManager : PersistentStateComponent<PromptManagerState> {

    @Volatile
    private var state: PromptManagerState? = null

    override fun getState(): PromptManagerState? = ensureState()

    override fun loadState(state: PromptManagerState) {
        this.state = state
    }

    // ------------------------------------------------------------------
    // 模板
    // ------------------------------------------------------------------

    @Synchronized
    fun all(): List<PromptTemplate> = ensureState().templates.toList()

    @Synchronized
    fun byId(id: String?): PromptTemplate? = id?.let { all().firstOrNull { t -> t.id == it } }

    /** 当前激活的模板；未激活任何模板时返回 null（发送时回退内置默认提示词）。 */
    @Synchronized
    fun activeTemplate(): PromptTemplate? = byId(activeTemplateId())

    @Synchronized
    fun setActive(id: String) {
        val s = ensureState()
        s.activeTemplateId = id
        commit(s)
    }

    @Synchronized
    fun add(template: PromptTemplate) {
        val s = ensureState()
        s.templates.add(template)
        if (s.activeTemplateId == null) s.activeTemplateId = template.id
        commit(s)
    }

    @Synchronized
    fun update(template: PromptTemplate) {
        val s = ensureState()
        val index = s.templates.indexOfFirst { it.id == template.id }
        if (index >= 0) s.templates[index] = template
        commit(s)
    }

    @Synchronized
    fun remove(id: String) {
        val s = ensureState()
        s.templates.removeAll { it.id == id }
        if (s.activeTemplateId == id) s.activeTemplateId = null
        commit(s)
    }

    // ------------------------------------------------------------------
    // 永久 Memory
    // ------------------------------------------------------------------

    @get:Synchronized
    val memory: String
        get() = ensureState().memory

    @Synchronized
    fun setMemory(text: String) {
        val s = ensureState()
        s.memory = text
        commit(s)
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun activeTemplateId(): String? = ensureState().activeTemplateId

    private fun ensureState(): PromptManagerState = state ?: createDefaultState().also { state = it }

    private fun createDefaultState(): PromptManagerState = PromptManagerState().apply {
        templates = mutableListOf(
            PromptTemplate(name = "Default", content = ChatController.SYSTEM_PROMPT)
        )
        activeTemplateId = templates.first().id
    }

    private fun commit(s: PromptManagerState) {
        state = s
    }

    companion object {
        fun getInstance(): PromptManager =
            ApplicationManager.getApplication().getService(PromptManager::class.java)

        /**
         * 文件/文件夹上下文的引导语。
         * 与 [com.aicodecopilot.plugin.chat.ChatController.buildRequest] 合并 system 消息时共用。
         */
        const val CONTEXT_PREAMBLE =
            "The user has shared the following files and code from their Android Studio project. " +
                "Use them as context for the conversation that follows."

        /**
         * 组装发送给 LLM 的 system prompt：
         * 模板内容（空白/缺失回退内置默认）+ 可选的 Permanent Memory 段。
         */
        fun buildSystemPrompt(templateContent: String?, memory: String): String {
            val base = templateContent?.takeIf { it.isNotBlank() }
                ?: ChatController.SYSTEM_PROMPT
            return if (memory.isBlank()) base
            else base + "\n\n# Permanent Memory\n" + memory.trim()
        }

        /**
         * 组装**单条** system 消息（某些 OpenAI 兼容后端要求 system 消息只能有 1 条且必须在最前，
         * 多条 system 会返回 400 "System message must be at the beginning."）。
         *
         * 顺序：基础段（模板/默认）→ Permanent Memory 段（memory 非空时）→
         * 文件上下文段（[context] 非空时，以 [CONTEXT_PREAMBLE] 引导）。
         */
        fun buildSingleSystemMessage(templateContent: String?, memory: String, context: String): String {
            val base = buildSystemPrompt(templateContent, memory)
            val trimmedContext = context.trim()
            return if (trimmedContext.isEmpty()) base
            else base + "\n\n" + CONTEXT_PREAMBLE + "\n\n" + trimmedContext
        }
    }
}
