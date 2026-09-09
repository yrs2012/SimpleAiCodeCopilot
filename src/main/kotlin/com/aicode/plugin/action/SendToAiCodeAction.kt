package com.aicode.plugin.action

import com.aicode.plugin.chat.AiCodeUiService
import com.aicode.plugin.chat.ChatPanel
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 编辑器右键菜单 / Tools 菜单中的 “Send to AiCode”：
 * - 把当前文件加入上下文；
 * - 如果有选区，把选中的代码作为 SNIPPET 一并加入；
 * - 激活 AiCode 工具窗口。
 */
class SendToAiCodeAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val vf = psiFile.virtualFile
        val editor = e.getData(CommonDataKeys.EDITOR)

        val service = AiCodeUiService.getInstance(project)

        val sel = editor?.selectionModel
        if (sel != null && sel.selectionEnd > sel.selectionStart) {
            val doc = editor.document
            val startLine = doc.getLineNumber(sel.selectionStart) + 1
            val endLine = doc.getLineNumber(sel.selectionEnd) + 1
            val label = "${psiFile.name} (L$startLine-L$endLine)"
            val text = sel.selectedText ?: return
            service.addSnippet(label, text)
            service.addFile(vf.path)
        } else {
            service.addFile(vf.path)
        }

        // 激活（并首次创建）工具窗口
        ToolWindowManager.getInstance(project).getToolWindow(ChatPanel.TOOL_WINDOW_ID)?.activate(null)

        Notifications.Bus.notify(
            Notification("AiCode", "AiCode", "已把 ${psiFile.name} 加入上下文", NotificationType.INFORMATION)
        )
    }

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabled = project != null && editor != null
        e.presentation.isVisible = project != null
    }
}
