package com.aicodecopilot.plugin.provider

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 添加 / 编辑系统提示词模板的对话框。
 * 字段：模板名 + 提示词内容（多行）。
 */
class PromptTemplateDialog(private val existing: PromptTemplate?) : DialogWrapper(null, true) {

    private val source = existing ?: PromptTemplate()

    private val nameField = JBTextField(source.name)
    private val contentArea = JBTextArea(10, 40).apply {
        text = source.content
        lineWrap = true
        wrapStyleWord = true
    }

    /** OK 按钮回调（由 Settings 面板设置，用于非模态下持久化表单结果）。 */
    private var okActionListener: (() -> Unit)? = null

    fun setOKActionListener(listener: () -> Unit) {
        okActionListener = listener
    }

    override fun doOKAction() {
        super.doOKAction()
        okActionListener?.invoke()
    }

    init {
        title = if (existing == null) "Add Prompt Template" else "Edit Prompt Template — ${existing.name}"
        init()
        isModal = false
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.border = JBUI.Borders.empty(8)
        panel.preferredSize = Dimension(520, 380)

        val north = JPanel(BorderLayout(0, 4))
        north.add(JLabel("Name"), BorderLayout.WEST)
        north.add(nameField, BorderLayout.CENTER)

        val center = JPanel(BorderLayout(0, 4))
        center.add(JBLabel("Prompt Content"), BorderLayout.NORTH)
        center.add(JBScrollPane(contentArea), BorderLayout.CENTER)

        panel.add(north, BorderLayout.NORTH)
        panel.add(center, BorderLayout.CENTER)
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) return ValidationInfo("Name is required", nameField)
        return null
    }

    fun buildResult(): PromptTemplate = PromptTemplate(
        id = source.id,
        name = nameField.text.trim().ifBlank { "(unnamed)" },
        content = contentArea.text
    )
}
