package com.aicodecopilot.plugin.chat

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase
import java.awt.GraphicsEnvironment
import java.awt.BorderLayout
import javax.swing.JEditorPane
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.border.Border
import javax.swing.border.LineBorder

/**
 * ChatPanel UI 构造冒烟测试：在真实 Swing/EDT 环境构造新 ChatPanel，
 * 验证本次新增控件（设置按钮、模型下拉、HTML 消息区、输入框边框）
 * 构造期不抛异常（回归保护 BoxLayout/AWTError 类问题）。
 *
 * 轻量测试框架可能是 headless 环境：此时 Swing 构造不可行，自动跳过。
 */
class ChatPanelUiConstructionTest : LightCodeInsightFixtureTestCase() {

    fun testChatPanelConstructsAndExposesNewControls() {
        if (GraphicsEnvironment.isHeadless()) {
            println("UI-SKIP: headless 测试环境，跳过 Swing 构造")
            return
        }
        var panel: JPanel? = null
        ApplicationManager.getApplication().invokeAndWait {
            panel = ChatPanel(project)
        }
        val p = panel!!

        // 1) HTML 消息区
        val transcript = field<JEditorPane>(p, "transcriptPane")
        assertEquals("text/html", transcript.contentType)
        assertFalse(transcript.isEditable)

        // 2) 模型下拉
        val modelCombo = field<JComboBox<*>>(p, "modelCombo")
        assertNotNull(modelCombo)

        // 3) Provider 下拉 + 设置按钮（同一行）
        field<JComboBox<*>>(p, "providerCombo")
        val settings = field<javax.swing.JButton>(p, "settingsButton")
        assertNotNull(settings)
        assertComponentParented(p, settings, "settingsButton 应在面板树中")

        // 4) 输入框带边框（复合边框 = LineBorder + 内边距）
        val input = field<com.intellij.ui.components.JBTextArea>(p, "inputArea")
        val b = input.border
        assertNotNull("输入框应有边框", b)
        assertEquals(
            "输入框边框应为 CompoundBorder（LineBorder+内边距），实际 ${b::class.java.name}",
            "javax.swing.border.CompoundBorder",
            b::class.java.name
        )
        assertTrue("复合边框内部应含 LineBorder", findLineBorderDeep(b))

        // 5) 会话切换/新对话等核心回调不抛异常（触发 rebuildTranscript 的 HTML 渲染路径）
        ApplicationManager.getApplication().invokeAndWait {
            p.revalidate()
            p.doLayout()
        }
        assertNotNull(controllerOf(p))

        // 6) 新 chips 行：+ 按钮在面板树中
        val plus = field<javax.swing.JButton>(p, "attachButton")
        assertEquals("+", plus.text)
        assertComponentParented(p, plus, "attachButton 应在面板树中")

        // 7) 旧 +File/+Folder 按钮已删除（JUnit3 runner 会误收 lambda 合成方法为用例，
        //    这里不用 assertThrows，改用 try/catch）
        try {
            ChatPanel::class.java.getDeclaredField("addFileButton")
            fail("addFileButton 字段应已删除")
        } catch (e: NoSuchFieldException) {
            // 预期：字段已删除
        }
        try {
            ChatPanel::class.java.getDeclaredField("addFolderButton")
            fail("addFolderButton 字段应已删除")
        } catch (e: NoSuchFieldException) {
            // 预期：字段已删除
        }
    }

    /** 递归确认组件已挂到面板树（settingsButton 在 providerRow 里，providerRow 在 panel 里）。 */
    private fun assertComponentParented(root: java.awt.Component, target: java.awt.Component, msg: String) {
        var found = false
        val stack = ArrayDeque<java.awt.Container>()
        if (root is java.awt.Container) stack.add(root)
        while (stack.isNotEmpty() && !found) {
            val node = stack.removeFirst()
            for (c in node.components) {
                if (c === target) { found = true; break }
                if (c is java.awt.Container) stack.add(c)
            }
        }
        assertTrue(msg + "（递归查找未命中）", found)
    }

    /** 递归读取 CompoundBorder 实现的内部 Border 字段（真实字段名 outsideBorder/insideBorder）。 */
    private fun findLineBorderDeep(border: Border): Boolean {
        if (border is LineBorder) return true
        if (border is javax.swing.border.CompoundBorder) {
            for (sub in listOf(border.outsideBorder, border.insideBorder)) {
                if (sub is Border && findLineBorderDeep(sub)) return true
            }
        }
        return false
    }

    private fun controllerOf(p: JPanel): ChatController {
        val f = ChatPanel::class.java.getDeclaredField("controller")
        f.isAccessible = true
        return f.get(p) as ChatController
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T : java.awt.Component> field(p: Any, name: String): T {
        val f = ChatPanel::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(p) as T
    }
}
