package com.aicodecopilot.plugin.provider

import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase
import com.intellij.util.xmlb.XmlSerializer

/**
 * ProviderManager 应用级服务测试（需要平台测试环境）。
 * 每个测试用例使用独立的测试 Application，互不干扰。
 */
class ProviderManagerTest : LightCodeInsightFixtureTestCase() {

    private val manager: ProviderManager get() = ProviderManager.getInstance()

    /**
     * 回归测试：@State 载体必须能被平台 XML 序列化器实例化/序列化。
     *
     * 历史 bug：`State` 曾是 ProviderManager 的 **inner class**，
     * KotlinAwareBeanBinding 用 Kotlin 反射 callBy 调其无参构造器时，
     * inner 类构造器带隐式外部实例参数（`instance`）→
     * `IllegalArgumentException: No argument provided for a required parameter: instance`
     * → idea.log 报 `SEVERE - Unable to serialize AiCodeProviderSettings state`，配置无法落盘。
     * 修复后 State 为顶层类 [ProviderManagerState]，本用例走真实
     * [XmlSerializer] serialize + asState 往返（即平台存储的同一代码路径）。
     */
    fun testStateXmlRoundTrip() {
        val state = ProviderManagerState().apply {
            providers = mutableListOf(
                ProviderConfig(name = "X", baseUrl = "http://x/v1", model = "mx", models = mutableListOf("m1", "m2"))
            )
            activeProviderId = providers.first().id
        }
        val element = XmlSerializer.serialize(state) // 修复前此处抛 SerializationException
        val restored = XmlSerializer.deserialize(element, ProviderManagerState::class.java)
        assertEquals(1, restored.providers.size)
        assertEquals("X", restored.providers.first().name)
        assertEquals(listOf("m1", "m2"), restored.providers.first().models)
        assertEquals(state.activeProviderId, restored.activeProviderId)
    }

    fun testPresetsExistAndDefaultActive() {
        val all = manager.all()
        assertTrue("应有预置 Provider", all.size >= 3)
        val active = manager.active()
        assertNotNull(active)
        assertEquals("默认激活第一个预置", all.first().id, active!!.id)
        assertTrue(active.enabled)
    }

    fun testSetActiveAndRemoveActive() {
        val a = ProviderConfig(name = "A", baseUrl = "http://a/v1", model = "m")
        val b = ProviderConfig(name = "B", baseUrl = "http://b/v1", model = "m")
        manager.add(a)
        manager.add(b)

        manager.setActive(b.id)
        assertEquals(b.id, manager.active()?.id)

        manager.remove(b.id)
        assertNull(manager.byId(b.id))
        val after = manager.active()
        assertNotNull("移除激活项后应回退到某个 Provider", after)
        assertFalse("回退结果不应是被移除项", after!!.id == b.id)
        assertTrue("回退结果应为启用项", after.enabled)
    }

    fun testUpdateReplacesInPlace() {
        val p = ProviderConfig(name = "P", baseUrl = "http://p/v1", model = "m")
        manager.add(p)
        val before = manager.all().size

        manager.update(p.copy(name = "P2", model = "m2"))

        assertEquals(before, manager.all().size)
        assertEquals("P2", manager.byId(p.id)?.name)
        assertEquals("m2", manager.byId(p.id)?.model)
    }

    fun testEnabledFiltersDisabled() {
        val p = ProviderConfig(name = "Off", baseUrl = "http://off/v1", model = "m", enabled = false)
        manager.add(p)
        assertFalse(manager.enabled().any { it.id == p.id })
        assertTrue(manager.all().any { it.id == p.id })
        manager.remove(p.id)
    }

    fun testAddKeepsExistingActive() {
        val originalActive = manager.active()?.id
        val n = ProviderConfig(name = "New", baseUrl = "http://n/v1", model = "m")
        manager.add(n)
        assertEquals("已有激活项时，新增不改变激活", originalActive, manager.active()?.id)
        manager.remove(n.id)
    }
}
