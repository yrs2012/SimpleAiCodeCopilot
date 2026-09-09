package com.aicode.plugin.chat

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * 聊天面板的项目级宿主服务。
 *
 * 工具窗口是惰性创建的；当用户还没打开 AiCode 窗口就触发
 * “Send to AiCode” 动作时，动作会先把待处理的内容暂存到这里，
 * 等面板创建完成后再应用。
 */
@Service(Service.Level.PROJECT)
class AiCodeUiService(private val project: Project) {

    @Volatile
    var panel: ChatPanel? = null
        private set

    private val pendingSnippets = mutableListOf<Pair<String, String>>()
    private val pendingFiles = mutableListOf<String>()

    fun addSnippet(label: String, content: String) {
        val p = panel
        if (p != null) {
            p.addSnippetContext(label, content)
        } else {
            synchronized(pendingSnippets) { pendingSnippets.add(label to content) }
        }
    }

    fun addFile(path: String) {
        val p = panel
        if (p != null) {
            p.addFileContext(path)
        } else {
            synchronized(pendingFiles) { pendingFiles.add(path) }
        }
    }

    fun onPanelReady(panel: ChatPanel) {
        this.panel = panel
        synchronized(pendingSnippets) {
            pendingSnippets.forEach { (label, content) -> panel.addSnippetContext(label, content) }
            pendingSnippets.clear()
        }
        synchronized(pendingFiles) {
            pendingFiles.forEach { panel.addFileContext(it) }
            pendingFiles.clear()
        }
    }

    companion object {
        fun getInstance(project: Project): AiCodeUiService =
            project.getService(AiCodeUiService::class.java)
    }
}
