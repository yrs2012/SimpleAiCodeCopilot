package com.aicodecopilot.plugin.chat

import com.aicodecopilot.plugin.provider.ProviderConfig
import com.aicodecopilot.plugin.provider.ProviderManager
import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase

/**
 * ChatController 模型选择逻辑：
 * 会话所选（在 models 内） > provider.models 首个 > provider.model。
 */
class ChatControllerModelTest : LightCodeInsightFixtureTestCase() {

    private val noop = object : ChatUiListener {
        override fun onMessagesChanged() {}
        override fun onStreamDelta() {}
        override fun onStreamFinished() {}
        override fun onSessionsChanged() {}
        override fun onContextChanged() {}
        override fun onError(message: String) {}
    }

    fun testModelFallbackChain() {
        val mgr = ProviderManager.getInstance()
        val p = ProviderConfig(
            name = "T", baseUrl = "http://fake/v1", model = "default-m",
            models = mutableListOf("a", "b")
        )
        mgr.add(p)
        mgr.setActive(p.id)
        val c = ChatController(project, noop)
        c.newChat()

        // 未选模型 → provider.models 首个
        assertEquals("a", c.activeModel())
        assertEquals(listOf("a", "b"), c.availableModels())

        // 会话选择 models 内的模型 → 使用会话选择
        c.setModel("b")
        assertEquals("b", c.activeModel())

        // 会话选择不在 models 中 → 回退首个
        c.setModel("zzz")
        assertEquals("a", c.activeModel())

        // 清空选择 → 回退首个
        c.setModel("")
        assertEquals("a", c.activeModel())
    }

    fun testModelFallsBackToProviderModelWhenNoList() {
        val mgr = ProviderManager.getInstance()
        val p = ProviderConfig(name = "T2", baseUrl = "http://fake/v1", model = "manual-m")
        mgr.add(p)
        mgr.setActive(p.id)
        val c = ChatController(project, noop)
        c.newChat()

        assertEquals("manual-m", c.activeModel())
        assertEquals(listOf("manual-m"), c.availableModels())
    }

    fun testSetProviderClearsStaleModelSelection() {
        val mgr = ProviderManager.getInstance()
        val p1 = ProviderConfig(
            name = "P1", baseUrl = "http://fake1/v1", model = "m1",
            models = mutableListOf("m1", "m2")
        )
        val p2 = ProviderConfig(name = "P2", baseUrl = "http://fake2/v1", model = "x1")
        mgr.add(p1)
        mgr.add(p2)
        mgr.setActive(p2.id)
        val c = ChatController(project, noop)
        c.newChat()

        c.setProvider(p1.id)
        c.setModel("m2")
        assertEquals("m2", c.activeModel())

        // 换 Provider 后旧模型选择被清空（P2 没有 models 列表）
        c.setProvider(p2.id)
        assertEquals("x1", c.activeModel())
    }
}
