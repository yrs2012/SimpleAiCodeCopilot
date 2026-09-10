package com.aicodecopilot.plugin.provider

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Component
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Settings → Tools → AiCodeCopilot
 *
 * 左侧：Provider 列表（★ 为当前默认）。
 * 右侧：选中 Provider 的详细信息 + 添加 / 编辑 / 删除 / 测试 / 设为默认 按钮。
 * 所有修改立即持久化（ProviderManager 直接落盘），无需再点 Apply。
 */
class AiCodeCopilotSettingsConfigurable : Configurable {

    override fun getDisplayName(): String = "AiCodeCopilot"

    private var panel: JComponent? = null
    private var list: JList<ProviderConfig>? = null
    private var infoLabel: JBLabel? = null
    private var testButton: JButton? = null

    private val manager: ProviderManager get() = ProviderManager.getInstance()

    override fun createComponent(): JComponent {
        val model = DefaultListModel<ProviderConfig>()
        val theList = JList<ProviderConfig>(model)
        theList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        theList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                listOf: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                val label = super.getListCellRendererComponent(listOf, value, index, isSelected, cellHasFocus)
                val p = value as? ProviderConfig
                if (p != null && label is JLabel) {
                    val active = p.id == manager.active()?.id
                    label.text = (if (active) "★ " else "  ") + p.name
                    label.foreground = if (p.enabled) null else JBColor.GRAY
                }
                return label
            }
        }
        theList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) updateInfo()
        }
        this.list = theList

        val info = JBLabel("").apply {
            border = JBUI.Borders.empty(12)
        }
        this.infoLabel = info

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        }
        val addButton = button("Add Provider…") { showDialog(null) }
        val editButton = button("Edit…") { showDialog(selected()) }
        val removeButton = button("Remove") { removeSelected() }
        val defaultButton = button("Set as Default") { setDefault() }
        val theTest = button("Test Connection") { testSelected() }
        testButton = theTest
        buttons.add(addButton)
        buttons.add(editButton)
        buttons.add(removeButton)
        buttons.add(defaultButton)
        buttons.add(theTest)

        val infoColumn = JPanel(BorderLayout(0, 8)).apply {
            add(info, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }

        val p = JPanel(BorderLayout(12, 0))
        p.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        theList.preferredSize = java.awt.Dimension(220, 200)
        p.add(
            com.intellij.ui.components.JBScrollPane(theList),
            BorderLayout.WEST
        )
        p.add(infoColumn, BorderLayout.CENTER)

        panel = p
        reload()
        return p
    }

    private fun button(label: String, action: () -> Unit): JButton =
        JButton(label).apply { addActionListener { action() } }

    private fun reload() {
        val model = list?.model as? DefaultListModel<ProviderConfig> ?: return
        model.clear()
        manager.all().forEach { model.addElement(it) }
        val activeId = manager.active()?.id
        val idx = (0 until model.size()).firstOrNull { model.get(it).id == activeId }
        if (idx != null) list?.selectedIndex = idx
        else if (model.size() > 0) list?.selectedIndex = 0
        updateInfo()
    }

    private fun selected(): ProviderConfig? = list?.selectedValue

    private fun updateInfo() {
        val label = infoLabel ?: return
        val p = selected()
        if (p == null) {
            label.text = "<html>No provider selected.<br>Add one to get started.</html>"
            return
        }
        val keyDisplay = p.apiKey.ifBlank { "(empty)" }
            .let { if (it.length <= 8) it else "••••${it.takeLast(4)}" }
        val headers = p.extraHeaders.entries.joinToString("\n") { "      ${it.key}: ${it.value}" }
        label.text = buildString {
            append("<html><pre>")
            appendLine("Name:        ${p.name}${if (p.id == manager.active()?.id) "   ★ default" else ""}")
            appendLine("Base URL:    ${p.baseUrl}")
            appendLine("Model:       ${p.model}")
            appendLine("API Key:     $keyDisplay")
            appendLine("Temperature: ${p.temperature}")
            appendLine("Max Tokens(KB):  ${p.maxTokens}")
            if (headers.isNotBlank()) {
                appendLine("Headers:")
                appendLine(headers)
            }
            appendLine("Enabled:     ${if (p.enabled) "yes" else "no"}")
            append("</pre></html>")
        }
    }

    private fun showDialog(existing: ProviderConfig?) {
        val dialog = ProviderConfigDialog(existing)
        
        // 设置对话框关闭时的回调
        dialog.setOKActionListener {
            val cfg = dialog.buildResult()
            if (existing == null) manager.add(cfg) else manager.update(cfg)
            reload()
        }
        
        // 显示非模态对话框
        dialog.show()
    }

    private fun removeSelected() {
        val p = selected() ?: return
        val parent = panel ?: return
        if (Messages.showYesNoDialog(
                parent,
                "Remove provider “${p.name}”?",
                "AiCodeCopilot Settings",
                Messages.getWarningIcon()
            ) != Messages.YES
        ) return
        manager.remove(p.id)
        reload()
    }

    private fun setDefault() {
        val p = selected() ?: return
        if (!p.enabled) {
            p.enabled = true
            manager.update(p)
        }
        manager.setActive(p.id)
        reload()
    }

    private fun testSelected() {
        val p = selected() ?: return
        val parent = panel ?: return
        testButton?.isEnabled = false
        testButton?.text = "Testing…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome: Pair<Boolean, String> = try {
                val reply = LlmClient.testConnection(p).trim().take(120)
                true to "Connection OK. Model replied: $reply"
            } catch (e: Exception) {
                false to (e.message ?: e.javaClass.simpleName)
            }
            ApplicationManager.getApplication().invokeLater {
                testButton?.isEnabled = true
                testButton?.text = "Test Connection"
                if (outcome.first) {
                    Messages.showMessageDialog(parent, outcome.second, "AiCodeCopilot — Test Connection", Messages.getInformationIcon())
                } else {
                    Messages.showErrorDialog(parent, outcome.second, "AiCodeCopilot — Test Connection Failed")
                }
            }
        }
    }

    override fun isModified(): Boolean = false

    override fun apply() {
        // 所有变更在发生时已直接持久化到 ProviderManager
    }

    override fun reset() {
        reload()
    }
}
