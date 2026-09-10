package com.aicodecopilot.plugin.action

import com.aicodecopilot.plugin.chat.AiCodeCopilotUiService
import com.aicodecopilot.plugin.chat.ChatPanel
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 编辑器右键菜单 / Tools 菜单中的 “Send to AiCodeCopilot”：
 * - 如果有选区，把选中的代码作为 SNIPPET 加入（文件本身由自动跟随覆盖，不再重复加）；
 * - 无选区时不添加任何条目；
 * - 激活 AiCodeCopilot 工具窗口。
 */
class SendToAiCodeCopilotAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)

        val service = AiCodeCopilotUiService.getInstance(project)

        val sel = editor?.selectionModel
        if (sel != null && sel.selectionEnd > sel.selectionStart) {
            val doc = editor.document
            val startLine = doc.getLineNumber(sel.selectionStart) + 1
            val endLine = doc.getLineNumber(sel.selectionEnd) + 1
            val label = "${psiFile.name} (L$startLine-L$endLine)"
            val text = sel.selectedText ?: return
            service.addSnippet(label, text)
        }

        // 激活（并首次创建）工具窗口
        ToolWindowManager.getInstance(project).getToolWindow(ChatPanel.TOOL_WINDOW_ID)?.activate(null)
    }

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabled = project != null && editor != null
        e.presentation.isVisible = project != null
    }
}
