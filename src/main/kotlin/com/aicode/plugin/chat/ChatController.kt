package com.aicode.plugin.chat

import com.aicode.plugin.provider.LlmClient
import com.aicode.plugin.provider.ProviderConfig
import com.aicode.plugin.provider.ProviderManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UI 回调接口。所有回调都在 EDT 上执行（控制器内部已切换线程）。
 */
interface ChatUiListener {
    /** 消息列表整体变化（新增消息 / 切换会话 / 清空）。 */
    fun onMessagesChanged()
    /** 流式增量：最后一条助手消息内容已更新。 */
    fun onStreamDelta()
    /** 一次流式回复结束（无论成功/失败/停止）。 */
    fun onStreamFinished()
    /** 会话列表变化（新建 / 删除 / 首条消息落盘）。 */
    fun onSessionsChanged()
    /** 上下文列表变化。 */
    fun onContextChanged()
    /** 出错。 */
    fun onError(message: String)
}

/**
 * 聊天控制器：会话状态、多轮对话、流式请求、上下文管理。
 * 由 ChatPanel 创建并持有（一个项目一个面板）。
 */
class ChatController(
    private val project: Project,
    private val ui: ChatUiListener
) {

    private val log = Logger.getInstance(ChatController::class.java)

    @Volatile
    private var session: Session? = null

    @Volatile
    private var cancelFlag: AtomicBoolean? = null

    val isStreaming: Boolean
        get() = cancelFlag != null

    fun currentSession(): Session? = session

    fun sessions(): List<SessionMeta> = SessionRepository.list()

    // ------------------------------------------------------------------
    // 会话生命周期
    // ------------------------------------------------------------------

    fun newChat() {
        if (isStreaming) return
        session = newDraft()
        ui.onContextChanged()
        ui.onMessagesChanged()
        ui.onSessionsChanged()
    }

    fun loadSession(meta: SessionMeta) {
        if (isStreaming) return
        val loaded = SessionRepository.load(meta.id) ?: return
        session = loaded
        ui.onContextChanged()
        ui.onMessagesChanged()
        ui.onSessionsChanged()
    }

    fun deleteCurrentSession() {
        if (isStreaming) return
        session?.takeIf { it.persisted }?.let { SessionRepository.delete(it.id) }
        session = null
        newChat()
        ui.onSessionsChanged()
    }

    private fun newDraft(): Session = Session(
        projectName = project.name,
        providerId = ProviderManager.getInstance().active()?.id ?: ""
    )

    // ------------------------------------------------------------------
    // Provider
    // ------------------------------------------------------------------

    fun setProvider(id: String) {
        ProviderManager.getInstance().setActive(id)
        session?.providerId = id
        session?.modelId = "" // 换 Provider 后清空旧模型选择（模型列表不同）
        saveCurrent()
    }

    /** 会话级选择模型（空串 = 恢复默认）。 */
    fun setModel(modelId: String) {
        session?.modelId = modelId
        saveCurrent()
    }

    /**
     * 当前会话要使用的模型：
     * 会话选择（且在 provider.models 中） > provider.models 首个 > provider.model。
     */
    fun activeModel(): String {
        val p = activeProvider() ?: return ""
        val chosen = session?.modelId.orEmpty()
        if (chosen.isNotBlank() && p.models.contains(chosen)) return chosen
        p.models.firstOrNull()?.let { return it }
        return p.model
    }

    /** 供 UI 填充模型下拉的候选列表。 */
    fun availableModels(): List<String> {
        val p = activeProvider() ?: return emptyList()
        return if (p.models.isNotEmpty()) p.models.toList() else listOf(p.model)
    }

    /** 当前会话实际使用的 Provider（会话指定的优先，其次全局激活的）。 */
    fun activeProvider(): ProviderConfig? {
        val mgr = ProviderManager.getInstance()
        return session?.providerId?.let { mgr.byId(it) }
            ?: mgr.active()
    }

    // ------------------------------------------------------------------
    // 上下文
    // ------------------------------------------------------------------

    fun addFileContext(path: String) {
        val s = session ?: return
        val f = File(path)
        if (!f.isFile) return
        if (s.contextEntries.any { it.kind == ContextKind.FILE && it.path == path }) return
        s.contextEntries.add(
            StoredContextEntry(kind = ContextKind.FILE, label = f.name, path = path)
        )
        saveCurrent()
        ui.onContextChanged()
    }

    fun addFolderContext(path: String) {
        val s = session ?: return
        val f = File(path)
        if (!f.isDirectory) return
        if (s.contextEntries.any { it.kind == ContextKind.FOLDER && it.path == path }) return
        s.contextEntries.add(
            StoredContextEntry(kind = ContextKind.FOLDER, label = f.name, path = path)
        )
        saveCurrent()
        ui.onContextChanged()
    }

    fun addSnippetContext(label: String, content: String) {
        val s = session ?: return
        if (content.isBlank()) return
        s.contextEntries.add(
            StoredContextEntry(kind = ContextKind.SNIPPET, label = label, content = content)
        )
        saveCurrent()
        ui.onContextChanged()
    }

    fun removeContextEntry(id: String) {
        val s = session ?: return
        s.contextEntries.removeAll { it.id == id }
        saveCurrent()
        ui.onContextChanged()
    }

    fun clearContext() {
        val s = session ?: return
        s.contextEntries.clear()
        saveCurrent()
        ui.onContextChanged()
    }

    // ------------------------------------------------------------------
    // 发送
    // ------------------------------------------------------------------

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || isStreaming) return

        val provider0 = activeProvider()
        if (provider0 == null) {
            ui.onError("No LLM provider configured. Open Settings → AiCode to add one.")
            return
        }
        // 会话所选模型覆盖 provider 默认模型
        val model = activeModel()
        val provider = if (model.isNotBlank() && model != provider0.model) {
            provider0.copy(model = model)
        } else {
            provider0
        }

        val s = session ?: newDraft().also { session = it }
        if (!s.persisted) {
            s.title = text.lineSequence().firstOrNull()?.take(40) ?: "New chat"
            s.persisted = true
        }

        s.messages.add(StoredMessage(Role.USER, text, System.currentTimeMillis()))
        s.updatedAt = System.currentTimeMillis()
        saveCurrent()

        // 组装请求：系统提示 + 上下文 + 最近 40 轮历史
        val contextBlocks = s.contextEntries.mapNotNull { ContextResolver.resolve(it, project) }
        val requestMessages = buildRequest(s, contextBlocks)

        // 先放一个空的助手消息占位，流式增量直接追加到它上面
        s.messages.add(StoredMessage(Role.ASSISTANT, "", System.currentTimeMillis()))
        ui.onMessagesChanged()

        val cancel = AtomicBoolean(false)
        cancelFlag = cancel

        log.info(
            "AiCode: 发送消息（provider=${provider.name}, model=${provider.model}, " +
                "baseUrl=${provider.baseUrl}, 历史=${requestMessages.size}条, 上下文=${s.contextEntries.size}项）"
        )
        val start = System.currentTimeMillis()

        Thread({
            try {
                LlmClient.chat(provider, requestMessages, stream = true, cancel) { delta: String ->
                    appendToLast(delta)
                    uiEdt { ui.onStreamDelta() }
                }
                if (s.messages.lastOrNull()?.content.isNullOrBlank() && cancel.get()) {
                    s.messages.removeLastOrNull() // 被停止且没有任何内容
                    log.info("AiCode: 流式回复被用户停止（${System.currentTimeMillis() - start}ms）")
                } else {
                    log.info(
                        "AiCode: 流式回复完成（${System.currentTimeMillis() - start}ms，" +
                            "共 ${s.messages.lastOrNull()?.content?.length ?: 0} 字符）"
                    )
                }
            } catch (e: Exception) {
                log.warn("AiCode: 请求失败（${System.currentTimeMillis() - start}ms）：${e.message}", e)
                val last = s.messages.lastOrNull()
                if (last != null && last.content.isBlank()) s.messages.removeLastOrNull()
                uiEdt { ui.onError(e.message ?: e.javaClass.simpleName) }
            } finally {
                s.updatedAt = System.currentTimeMillis()
                saveCurrent()
                cancelFlag = null
                uiEdt {
                    ui.onMessagesChanged()
                    ui.onStreamFinished()
                    ui.onSessionsChanged()
                }
            }
        }, "aicode-chat").start()
    }

    fun stop() {
        cancelFlag?.set(true)
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun appendToLast(delta: String) {
        val s = session ?: return
        synchronized(s.messages) {
            val last = s.messages.lastOrNull() ?: return
            last.content += delta
        }
    }

    private fun buildRequest(s: Session, contextBlocks: List<String>): List<LlmClient.ChatMessage> {
        val out = mutableListOf<LlmClient.ChatMessage>(LlmClient.ChatMessage(Role.SYSTEM, SYSTEM_PROMPT))
        if (contextBlocks.isNotEmpty()) {
            out += LlmClient.ChatMessage(
                Role.SYSTEM,
                "The user has shared the following files and code from their Android Studio project. " +
                    "Use them as context for the conversation that follows.\n\n" +
                    contextBlocks.joinToString("\n\n")
            )
        }
        val history = if (s.messages.size > MAX_HISTORY_MESSAGES) s.messages.takeLast(MAX_HISTORY_MESSAGES) else s.messages
        out += history.map { LlmClient.ChatMessage(it.role, it.content) }
        return out
    }

    private fun saveCurrent() {
        val s = session ?: return
        if (!s.persisted) return
        runCatching { SessionRepository.save(s) }
    }

    private fun uiEdt(block: () -> Unit) {
        if (ApplicationManager.getApplication().isDispatchThread) block()
        else ApplicationManager.getApplication().invokeLater(block)
    }

    companion object {
        const val MAX_HISTORY_MESSAGES = 40

        const val SYSTEM_PROMPT =
            "You are AiCode, an AI coding assistant embedded in Android Studio. " +
                "You help developers write, review, refactor and debug code " +
                "(primarily Android / Kotlin / Java, but any language is fine). " +
                "Be concise and practical. When showing code, use fenced code blocks with a language tag. " +
                "Answer in the same language the user writes in."
    }
}
