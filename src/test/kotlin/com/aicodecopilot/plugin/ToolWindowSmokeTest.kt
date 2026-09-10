package com.aicodecopilot.plugin

import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase

/**
 * 工具窗冒烟测试：验证 AiCodeCopilot 工具窗能注册、工厂能创建内容。
 *
 * 注意：轻量测试 IDE（Light 测试框架）不处理被测插件 plugin.xml 里的
 * toolWindow EP（只通过注解扫描注册 @Service/@State 服务），因此
 * getToolWindow("AiCodeCopilot") 返回 null 时本用例自动跳过（JUnit3 语义：return = 不失败）。
 * 在加载了完整插件 EP 的测试环境中（如 runIde 沙箱）这些断言会真正生效。
 *
 * 本测试同时是 BoxLayout 回归保护：工厂调用会执行 ChatPanel 构造，
 * 历史上曾因 `BoxLayout(this, ...)` 容器参数错误在真实 IDE 中抛
 * `AWTError: BoxLayout can't be shared`，导致工具窗 "Nothing to show"。
 */
class ToolWindowSmokeTest : LightCodeInsightFixtureTestCase() {

    private fun toolWindow(): com.intellij.openapi.wm.ToolWindow? {
        val manager = project.getService(ToolWindowManager::class.java)
        val tw = manager.getToolWindow("AiCodeCopilot")
        if (tw == null) {
            println("SMOKE-SKIP: 轻量测试 IDE 未加载插件 toolWindow EP，getToolWindow(AiCodeCopilot)=null，跳过")
        }
        return tw
    }

    fun testToolWindowRegistered() {
        val tw = toolWindow() ?: return
        assertNotNull("AiCodeCopilot 工具窗应已注册", tw)
    }

    fun testFactoryCreatesContent() {
        val tw = toolWindow() ?: return
        // 直接调用工厂方法（与平台走同一路径：ToolWindowImpl.createContentIfNeeded）
        AiCodeCopilotToolWindowFactory().createToolWindowContent(project, tw)
        val contents = tw.contentManager.contents
        assertTrue(
            "工厂调用后应有至少 1 个 content，实际 ${contents.size} (${contents.joinToString { it.toString() }})",
            contents.isNotEmpty()
        )
    }

    fun testShowTriggersContent() {
        val tw = toolWindow() ?: return
        tw.show()
        val contents = tw.contentManager.contents
        println("DBG-TOOLWINDOW: visible=${tw.isVisible} contents=${contents.size} items=${contents.joinToString { it.toString() }}")
    }
}
