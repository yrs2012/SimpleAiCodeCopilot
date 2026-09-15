package com.aicodecopilot.plugin.provider

import com.aicodecopilot.plugin.chat.ChatController
import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase
import com.intellij.util.xmlb.XmlSerializer

/**
 * PromptManager 应用级服务测试（需要平台测试环境）。
 * 每个测试用例使用独立的测试 Application，互不干扰。
 */
class PromptManagerTest : LightCodeInsightFixtureTestCase() {

    private val manager: PromptManager get() = PromptManager.getInstance()

    /**
     * 回归测试：@State 载体必须能被平台 XML 序列化器实例化/序列化。
     * 走真实 [XmlSerializer] serialize + deserialize 往返（即平台存储的同一代码路径）。
     */
    fun testStateXmlRoundTrip() {
        val state = PromptManagerState().apply {
            templates = mutableListOf(
                PromptTemplate(name = "Default", content = "be brief"),
                PromptTemplate(name = "Reviewer", content = "review code")
            )
            activeTemplateId = templates.first().id
            memory = "user prefers Kotlin"
        }
        val element = XmlSerializer.serialize(state)
        val restored = XmlSerializer.deserialize(element, PromptManagerState::class.java)
        assertEquals(2, restored.templates.size)
        assertEquals("Default", restored.templates.first().name)
        assertEquals("be brief", restored.templates.first().content)
        assertEquals(state.activeTemplateId, restored.activeTemplateId)
        assertEquals("user prefers Kotlin", restored.memory)
    }

    fun testDefaultStateHasOneActiveTemplate() {
        val all = manager.all()
        assertTrue("首次启动应预置 Default 模板", all.size >= 1)
        assertNotNull("Default 模板应激活", manager.activeTemplate())
    }

    fun testAddUpdateRemove() {
        val t = PromptTemplate(name = "T", content = "c")
        manager.add(t)
        assertEquals("T", manager.byId(t.id)?.name)

        manager.update(t.copy(name = "T2", content = "c2"))
        assertEquals("T2", manager.byId(t.id)?.name)
        assertEquals("c2", manager.byId(t.id)?.content)

        val before = manager.all().size
        manager.remove(t.id)
        assertNull(manager.byId(t.id))
        assertEquals(before - 1, manager.all().size)
    }

    fun testSetActiveAndRemoveActiveFallsBackToNull() {
        val a = PromptTemplate(name = "A", content = "a")
        manager.add(a)
        manager.setActive(a.id)
        assertEquals(a.id, manager.activeTemplate()?.id)

        manager.remove(a.id)
        assertNull("删除激活模板后应回退为 null（发送时用内置默认）", manager.activeTemplate())
    }

    fun testUpdateNonexistentIdIsNoOp() {
        val ghost = PromptTemplate(name = "Ghost", content = "x")
        val before = manager.all().size
        manager.update(ghost)
        assertNull("不存在的 id 更新应为 no-op", manager.byId(ghost.id))
        assertEquals(before, manager.all().size)
    }

    fun testRemoveAllTemplatesYieldsNullActive() {
        val ids = manager.all().map { it.id }
        ids.forEach { manager.remove(it) }
        assertTrue("全部删除后应为空", manager.all().isEmpty())
        assertNull("无模板时激活模板应为 null", manager.activeTemplate())

        // 本测试类的各用例共享同一个 Application（服务实例不重置），
        // 恢复 Default 模板，避免影响依赖预置模板的其他用例。
        val restored = PromptTemplate(name = "Default", content = ChatController.SYSTEM_PROMPT)
        manager.add(restored)
        assertEquals(restored.id, manager.activeTemplate()?.id)
    }

    fun testMemoryReadWrite() {
        manager.setMemory("hello memory")
        assertEquals("hello memory", manager.memory)
        manager.setMemory("")
        assertEquals("", manager.memory)
    }

    fun testBuildSystemPromptDefaultOnly() {
        val p = PromptManager.buildSystemPrompt(null, "")
        assertEquals(ChatController.SYSTEM_PROMPT, p)
    }

    fun testBuildSystemPromptTemplateOnly() {
        val p = PromptManager.buildSystemPrompt("You are a reviewer.", "")
        assertEquals("You are a reviewer.", p)
    }

    fun testBuildSystemPromptTemplateAndMemory() {
        val p = PromptManager.buildSystemPrompt("You are a reviewer.", "user prefers Kotlin")
        assertEquals("You are a reviewer.\n\n# Permanent Memory\nuser prefers Kotlin", p)
    }

    fun testBuildSystemPromptBlankTemplateFallsBack() {
        val p = PromptManager.buildSystemPrompt("   \n ", "note")
        assertEquals(ChatController.SYSTEM_PROMPT + "\n\n# Permanent Memory\nnote", p)
    }

    fun testBuildSystemPromptMemoryOnly() {
        val p = PromptManager.buildSystemPrompt(null, "note")
        assertEquals(ChatController.SYSTEM_PROMPT + "\n\n# Permanent Memory\nnote", p)
    }

    fun testBuildSystemPromptBlankAndTrimmedMemory() {
        assertEquals(ChatController.SYSTEM_PROMPT, PromptManager.buildSystemPrompt(null, "   "))
        assertEquals(
            ChatController.SYSTEM_PROMPT + "\n\n# Permanent Memory\nnote",
            PromptManager.buildSystemPrompt(null, "  note  ")
        )
    }

    // --- 单条 system 消息：基础段 + 可选 Memory 段 + 可选 Context 段 合并 ---

    fun testBuildSingleSystemMessageWithoutContext() {
        assertEquals(
            "You are a reviewer.\n\n# Permanent Memory\nuser prefers Kotlin",
            PromptManager.buildSingleSystemMessage("You are a reviewer.", "user prefers Kotlin", "")
        )
    }

    fun testBuildSingleSystemMessageWithContext() {
        val ctx = "FILE: a.kt\n```\nval x = 1\n```"
        val p = PromptManager.buildSingleSystemMessage("You are a reviewer.", "user prefers Kotlin", ctx)
        assertTrue("应以 preamble 引入上下文", p.contains(PromptManager.CONTEXT_PREAMBLE))
        assertTrue("应包含上下文原文", p.contains(ctx))
        assertTrue("上下文应位于 preamble 之后", p.indexOf(PromptManager.CONTEXT_PREAMBLE) < p.indexOf(ctx))
    }

    fun testBuildSingleSystemMessageContextTrimmed() {
        val p = PromptManager.buildSingleSystemMessage("base", "", "  FILE: a.kt  ")
        assertEquals("base\n\n" + PromptManager.CONTEXT_PREAMBLE + "\n\nFILE: a.kt", p)
    }

    fun testBuildContextPreambleIsStable() {
        assertEquals(
            "The user has shared the following files and code from their Android Studio project. " +
                "Use them as context for the conversation that follows.",
            PromptManager.CONTEXT_PREAMBLE
        )
    }
}
