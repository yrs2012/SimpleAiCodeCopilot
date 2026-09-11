package com.aicodecopilot.plugin.provider

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Settings → Tools → AiCodeCopilot → Prompts & Memory
 *
 * 上半部：系统提示词模板列表（★ 为当前激活）+ 增 / 改 / 删 / 设为默认。
 * 下半部：永久 Memory 多行文本域。
 * 所有修改立即持久化（PromptManager 直接落盘），无需再点 Apply。
 */
class PromptsMemoryConfigurable : Configurable {

    override fun getDisplayName(): String = "Prompts & Memory"

    private var panel: JComponent? = null
    private var list: JList<PromptTemplate>? = null
    private var memoryArea: JBTextArea? = null

    private val manager: PromptManager get() = PromptManager.getInstance()

    override fun createComponent(): JComponent {
        // ---- 模板列表 ----
        val model = DefaultListModel<PromptTemplate>()
        val theList = JList<PromptTemplate>(model)
        theList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        theList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                listOf: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                val label = super.getListCellRendererComponent(listOf, value, index, isSelected, cellHasFocus)
                val t = value as? PromptTemplate
                if (t != null && label is JLabel) {
                    val active = t.id == manager.activeTemplate()?.id
                    label.text = (if (active) "★ " else "  ") + t.name
                }
                return label
            }
        }
        this.list = theList

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        buttons.add(button("Add…") { showDialog(null) })
        buttons.add(button("Edit…") { showDialog(selected()) })
        buttons.add(button("Remove") { removeSelected() })
        buttons.add(button("Set as Default") { setDefault() })

        val templatesTitle = JBLabel("System Prompt Templates")
        val templatesPanel = JPanel(BorderLayout(0, 4)).apply {
            add(templatesTitle, BorderLayout.NORTH)
            add(JBScrollPane(theList), BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }

        // ---- 永久 Memory ----
        val area = JBTextArea(6, 40).apply {
            text = manager.memory
            lineWrap = true
            wrapStyleWord = true
        }
        this.memoryArea = area
        area.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = saveMemory()
            override fun removeUpdate(e: DocumentEvent) = saveMemory()
            override fun changedUpdate(e: DocumentEvent) = saveMemory()
        })

        val memoryTitle = JBLabel("Permanent Memory")
        val memoryHint = JBLabel(
            "Appended to the system prompt for every conversation. Keep under ~8000 characters."
        ).apply { foreground = JBColor.GRAY }
        val memoryPanel = JPanel(BorderLayout(0, 4)).apply {
            add(memoryTitle, BorderLayout.NORTH)
            add(JBScrollPane(area), BorderLayout.CENTER)
            add(memoryHint, BorderLayout.SOUTH)
        }

        // ---- 组合：上下各占一半 ----
        val p = JPanel(GridLayout(2, 1, 0, 12)).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        }
        p.add(templatesPanel)
        p.add(memoryPanel)

        panel = p
        reload()
        return p
    }

    private fun button(label: String, action: () -> Unit): JButton =
        JButton(label).apply { addActionListener { action() } }

    private fun saveMemory() {
        manager.setMemory(memoryArea?.text ?: "")
    }

    private fun reload() {
        val model = list?.model as? DefaultListModel<PromptTemplate> ?: return
        model.clear()
        manager.all().forEach { model.addElement(it) }
        val activeId = manager.activeTemplate()?.id
        val idx = (0 until model.size()).firstOrNull { model.get(it).id == activeId }
        if (idx != null) list?.selectedIndex = idx
        else if (model.size > 0) list?.selectedIndex = 0
    }

    private fun selected(): PromptTemplate? = list?.selectedValue

    private fun showDialog(existing: PromptTemplate?) {
        val dialog = PromptTemplateDialog(existing)
        dialog.setOKActionListener {
            val t = dialog.buildResult()
            if (existing == null) manager.add(t) else manager.update(t)
            reload()
        }
        dialog.show()
    }

    private fun removeSelected() {
        val t = selected() ?: return
        val parent = panel ?: return
        if (Messages.showYesNoDialog(
                parent,
                "Remove prompt template “${t.name}”?",
                "AiCodeCopilot Settings",
                Messages.getWarningIcon()
            ) != Messages.YES
        ) return
        manager.remove(t.id)
        reload()
    }

    private fun setDefault() {
        val t = selected() ?: return
        manager.setActive(t.id)
        reload()
    }

    override fun isModified(): Boolean = false

    override fun apply() {
        // 所有变更在发生时已直接持久化到 PromptManager
    }

    override fun reset() {
        reload()
        if (memoryArea?.text != manager.memory) memoryArea?.text = manager.memory
    }
}
