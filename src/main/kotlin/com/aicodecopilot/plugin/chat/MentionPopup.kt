package com.aicodecopilot.plugin.chat

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.Component
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList

/** @ 选择结果：文件直接关联；目录可进入或整体关联。 */
sealed class MentionPick {
    class File(val path: String) : MentionPick()
    class Dir(val path: String) : MentionPick()
}

private sealed class Row {
    class Up(val path: String) : Row()                       // 返回上级
    class AttachDir(val path: String) : Row()                // 关联当前目录
    class Entry(val name: String, val path: String, val isDir: Boolean) : Row()
}

/**
 * @ 文件选择器：起点项目根；打字过滤；目录可进入；可直接关联目录。
 * 由 ChatPanel 驱动：show → updateFilter* → close；选中回调 onPick。
 */
class MentionPopup(private val project: Project) {

    private val log = Logger.getInstance(MentionPopup::class.java)

    private var popup: JBPopup? = null
    private var onPick: ((MentionPick) -> Unit)? = null
    private val model = DefaultListModel<Row>()
    private val list = JBList(model).apply { visibleRowCount = 10 }

    private var baseDir: String = project.basePath ?: "/"
    private var curDir: String = baseDir
    private var filter: String = ""

    fun isOpen(): Boolean = popup?.isDisposed?.not() ?: false

    fun show(anchor: JComponent, initialFilter: String, onPick: (MentionPick) -> Unit) {
        close()
        this.onPick = onPick
        this.curDir = baseDir
        list.cellRenderer = rowRenderer()
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) activate(list.selectedValue)
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    e.consume()
                    activate(list.selectedValue)
                }
            }
        })
        val scroll = JBScrollPane(list).apply { preferredSize = Dimension(380, 260) }
        try {
            popup = JBPopupFactory.getInstance().createComponentPopupBuilder(scroll, null)
                .setFocusable(true)
                .setRequestFocus(false)
                .setShowBorder(true)
                .setCancelOnClickOutside(true)
                .setCancelKeyEnabled(true)
                .createPopup()
            popup?.showUnderneathOf(anchor)
        } catch (t: Throwable) {
            log.warn("AiCodeCopilot: @选择器弹出失败", t)
            popup = null
            return
        }
        updateFilter(initialFilter)
    }

    fun updateFilter(f: String) {
        if (!isOpen()) return
        filter = f
        val dir = curDir
        ApplicationManager.getApplication().executeOnPooledThread {
            val rows = listRows(dir, f)
            ApplicationManager.getApplication().invokeLater {
                if (!isOpen() || dir != curDir || f != filter) return@invokeLater
                model.clear()
                rows.forEach { model.addElement(it) }
                if (model.size() > 0) list.selectedIndex = 0
            }
        }
    }

    fun close() {
        popup?.cancel()
        popup = null
    }

    /** 供输入框按键转发：上下移动选中（循环），并滚动可见。 */
    fun moveSelection(delta: Int) {
        if (!isOpen() || model.size() == 0) return
        val next = (list.selectedIndex + delta).mod(model.size())
        list.selectedIndex = next
        list.ensureIndexIsVisible(next)
    }

    /** 供输入框回车转发：确认当前选中。 */
    fun confirmSelected() {
        if (!isOpen()) return
        activate(list.selectedValue)
    }

    private fun activate(row: Row?) {
        when (row) {
            is Row.Entry ->
                if (row.isDir) {
                    curDir = row.path
                    updateFilter(filter)
                } else {
                    val cb = onPick
                    close()
                    cb?.invoke(MentionPick.File(row.path))
                }
            is Row.AttachDir -> {
                val cb = onPick
                val dir = row.path
                close()
                cb?.invoke(MentionPick.Dir(dir))
            }
            is Row.Up -> {
                File(curDir).parent?.let {
                    curDir = it
                    updateFilter(filter)
                }
            }
            null -> {}
        }
    }

    private fun listRows(dir: String, f: String): List<Row> {
        // 只读不刷新：refresh 需要写动作，后台线程里调会炸；VFS 已有索引数据足够列目录
        val vf = runCatching {
            ApplicationManager.getApplication().runReadAction<VirtualFile?> {
                LocalFileSystem.getInstance().findFileByPath(dir)
            }
        }.onFailure { log.warn("AiCodeCopilot: @列目录失败 $dir", it) }.getOrNull()
            ?: return emptyList()
        val out = mutableListOf<Row>()
        if (dir != baseDir) out.add(Row.Up(dir))
        out.add(Row.AttachDir(dir))
        val q = f.trim().lowercase()
        val children = vf.children?.toList().orEmpty()
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        var count = 0
        for (c in children) {
            if (count >= 500) break
            if (q.isNotEmpty() && !c.name.lowercase().contains(q) &&
                !c.path.lowercase().contains(q)
            ) continue
            out.add(Row.Entry(if (c.isDirectory) c.name + "/" else c.name, c.path, c.isDirectory))
            count++
        }
        return out
    }

    private fun rowRenderer() = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            listOf: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val label = super.getListCellRendererComponent(
                listOf, displayOf(value), index, isSelected, cellHasFocus
            ) as JLabel
            label.icon = when (value) {
                is Row.Entry -> if (value.isDir) AllIcons.Nodes.Folder else AllIcons.FileTypes.Text
                is Row.AttachDir -> AllIcons.Nodes.Folder
                else -> AllIcons.General.ArrowUp
            }
            return label
        }
    }

    private fun displayOf(v: Any?): String = when (v) {
        is Row.Entry -> v.name
        is Row.AttachDir -> "关联整个目录：${v.path}  ⏎"
        is Row.Up -> "← 返回上级"
        else -> ""
    }
}
