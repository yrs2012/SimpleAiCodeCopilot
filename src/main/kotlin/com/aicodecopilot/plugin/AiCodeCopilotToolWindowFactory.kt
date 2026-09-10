package com.aicodecopilot.plugin

import com.aicodecopilot.plugin.chat.ChatPanel
import com.aicodecopilot.plugin.chat.AiCodeCopilotUiService
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

private val log = Logger.getInstance(AiCodeCopilotToolWindowFactory::class.java)

/**
 * 创建右侧 “AiCodeCopilot” 工具窗口。
 */
class AiCodeCopilotToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (project.isDisposed) return
        try {
            val service = AiCodeCopilotUiService.getInstance(project)
            val panel = ChatPanel(project)
            service.onPanelReady(panel)

            val content = toolWindow.contentManager.factory.createContent(panel, "", false)
            content.isCloseable = true
            toolWindow.contentManager.addContent(content)
        } catch (t: Throwable) {
            log.error("Failed to create AiCodeCopilot tool window content", t)
            throw t
        }
    }
}
