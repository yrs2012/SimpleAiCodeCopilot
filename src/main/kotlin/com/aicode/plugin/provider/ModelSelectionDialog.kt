package com.aicode.plugin.provider

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 模型多选对话框（复选框列表）：
 * - 单击某一行 = 选中该行（☑）；再次单击 = 取消选中（☐）；
 * - 连续点击多个行 = 累加选中，互不排斥；
 * - 勾选状态由 [checked] 集合维护（与 JList 原生选中机制解耦，
 *   避免 BasicListUI 默认“单击独占选中”行为），渲染器按集合画 ☑/☐。
 *
 * 由 ProviderConfigDialog 的“自动添加模型”在拉取到模型列表后弹出。
 *
 * @param parent     父窗组件（Provider 对话框的 rootPane），保证对话框显示在其前方
 * @param models     服务端返回的模型 id 列表
 * @param preChecked 初始勾选的模型（通常为已保存的模型）
 */
class ModelSelectionDialog(
    parent: Component,
    private val models: List<String>,
    preChecked: Collection<String>
) : DialogWrapper(parent, true) {

    private val list = JList<String>(DefaultListModel<String>())

    /** 勾选状态（与行索引无关，按模型 id 维护）。 */
    private val checked: MutableSet<String> = LinkedHashSet(preChecked.filter { it in models })

    init {
        title = "Select Models"
        val model = list.model as DefaultListModel<String>
        models.forEach { model.addElement(it) }

        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                listOf: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                val label = super.getListCellRendererComponent(listOf, value, index, isSelected, cellHasFocus)
                val id = value?.toString().orEmpty()
                if (label is JLabel) {
                    label.text = (if (checked.contains(id)) "☑ " else "☐ ") + id
                }
                return label
            }
        }
        // 单击 = 切换该行勾选；连续点击多行 = 累加（不互斥）
        list.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val idx = list.locationToIndex(e.point)
                if (idx < 0 || idx >= models.size) return
                val id = models[idx]
                if (checked.contains(id)) checked.remove(id) else checked.add(id)
                list.ensureIndexIsVisible(idx)
                list.repaint()
            }
        })
        init()
    }

    override fun createCenterPanel(): JPanel {
        val hint = JBLabel(
            "<html>勾选要添加的模型：<b>单击选中 / 再次单击取消</b>，可连续点击多个模型（互不排斥）。</html>"
        ).apply { border = JBUI.Borders.empty(8, 8, 4, 8) }
        val scroll = JBScrollPane(list)
        scroll.preferredSize = Dimension(460, 280)
        val panel = JPanel(BorderLayout(0, 8))
        panel.add(hint, BorderLayout.NORTH)
        panel.add(scroll, BorderLayout.CENTER)
        panel.border = JBUI.Borders.empty(8)
        return panel
    }

    /** 用户点 OK 后返回勾选的模型 id（按列表顺序）。 */
    fun selectedModels(): List<String> = models.filter { it in checked }
}
