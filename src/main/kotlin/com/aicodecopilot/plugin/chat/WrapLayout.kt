package com.aicodecopilot.plugin.chat

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager

/**
 * 可换行的 chips 布局。
 *
 * FlowLayout 虽然会换行，但首选高度永远按单行算——放在纵向 Box 里时，
 * 第二行 chips  wrap 出来却没有分配高度，直接被输入框区域裁掉。
 * 这个按容器实际宽度算换行后的首选/最小尺寸，多几个文件就长高几行。
 */
class WrapLayout(private val hgap: Int = 6, private val vgap: Int = 2) : LayoutManager {

    override fun addLayoutComponent(name: String?, comp: Component?) {}
    override fun removeLayoutComponent(comp: Component?) {}
    override fun preferredLayoutSize(parent: Container): Dimension = layoutSize(parent, true)
    override fun minimumLayoutSize(parent: Container): Dimension = layoutSize(parent, false)

    override fun layoutContainer(parent: Container) {
        synchronized(parent.treeLock) {
            val insets = parent.insets
            val maxW = (parent.width - insets.left - insets.right).coerceAtLeast(1)
            var y = insets.top
            val row = ArrayList<Component>()
            var rowW = 0
            fun flush() {
                if (row.isEmpty()) return
                // 同行按最高者居中，避免 + 按钮和 chips 上下错位
                val rowH = row.maxOf { it.preferredSize.height }
                var x = insets.left
                for (c in row) {
                    val d = c.preferredSize
                    c.setBounds(x, y + (rowH - d.height) / 2, d.width, d.height)
                    x += d.width + hgap
                }
                y += rowH + vgap
                row.clear()
                rowW = 0
            }
            for (c in parent.components) {
                if (!c.isVisible) continue
                val d = c.preferredSize
                if (rowW + d.width > maxW && row.isNotEmpty()) flush()
                row.add(c)
                rowW += d.width + hgap
            }
            flush()
        }
    }

    private fun layoutSize(parent: Container, preferred: Boolean): Dimension {
        synchronized(parent.treeLock) {
            val insets = parent.insets
            val avail = parent.width.takeIf { it > 0 }?.minus(insets.left + insets.right)
                ?: Int.MAX_VALUE
            var x = 0
            var y = insets.top + insets.bottom
            var rowH = 0
            var maxX = 0
            for (c in parent.components) {
                if (!c.isVisible) continue
                val d = if (preferred) c.preferredSize else c.minimumSize
                if (x + d.width > avail && x > 0) {
                    y += rowH + vgap
                    x = 0
                    rowH = 0
                }
                x += d.width + hgap
                maxX = maxOf(maxX, x)
                rowH = maxOf(rowH, d.height)
            }
            return Dimension(maxX + insets.left + insets.right, y + rowH)
        }
    }
}
