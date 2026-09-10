package com.aicodecopilot.plugin.chat

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm

/**
 * 跟踪选中编辑器 + 选中区，驱动 FollowState。
 * 全部监听以 project 为 parent Disposable，随项目关闭自动释放，无需 stop()。
 * 回调 onChange 保证在 EDT 上执行。
 */
class EditorFollowTracker(
    private val project: Project,
    private val state: FollowState,
    private val onChange: () -> Unit
) {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)

    private fun schedule() {
        alarm.cancelAllRequests()
        alarm.addRequest({ recompute() }, 300)
    }

    private fun recompute() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val vf = editor?.virtualFile
        val target: FollowTarget? =
            if (editor == null || vf == null || !vf.isInLocalFileSystem || vf.isDirectory) {
                null
            } else {
                val sel = editor.selectionModel
                if (sel.selectionEnd > sel.selectionStart) {
                    val doc = editor.document
                    val sl = doc.getLineNumber(sel.selectionStart) + 1
                    val el = doc.getLineNumber(sel.selectionEnd) + 1
                    FollowTarget(
                        kind = ContextKind.SNIPPET,
                        path = vf.path,
                        label = "${vf.name} ($sl-$el)",
                        startLine = sl,
                        endLine = el,
                        content = sel.selectedText
                    )
                } else {
                    FollowTarget(ContextKind.FILE, vf.path, vf.name)
                }
            }
        if (state.update(target)) onChange()
    }

    init {
        project.messageBus.connect(project)
            .subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                object : FileEditorManagerListener {
                    override fun selectionChanged(e: FileEditorManagerEvent) = schedule()
                }
            )
        EditorFactory.getInstance().eventMulticaster
            .addCaretListener(object : CaretListener {
                override fun caretPositionChanged(e: CaretEvent) {
                    if (e.editor.project == project) schedule()
                }
            }, project)
        // 面板打开时立刻算一次
        ApplicationManager.getApplication().invokeLater { recompute() }
    }
}
