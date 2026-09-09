package com.aicode.plugin

import com.aicode.plugin.chat.ChatPanel
import com.aicode.plugin.chat.AiCodeUiService
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

private val log = Logger.getInstance(AiCodeToolWindowFactory::class.java)

/**
 * 创建右侧 “AiCode” 工具窗口。
 */
class AiCodeToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (project.isDisposed) return
        try {
            val service = AiCodeUiService.getInstance(project)
            val panel = ChatPanel(project)
            service.onPanelReady(panel)

            val content = toolWindow.contentManager.factory.createContent(panel, "", false)
            content.isCloseable = true
            toolWindow.contentManager.addContent(content)
        } catch (t: Throwable) {
            log.error("Failed to create AiCode tool window content", t)
            throw t
        }
    }
}
