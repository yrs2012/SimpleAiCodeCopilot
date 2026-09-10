package com.aicodecopilot.plugin.provider

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 验证"后台线程执行 → invokeLater 回 EDT"这条结果回传链在真实平台环境可达：
 * 复现 ProviderConfigDialog 的 testConnection/fetchModels 的线程模型
 * （executeOnPooledThread + invokeLater），确保结果回调确实被 EDT 执行。
 *
 * 平台测试方法本身运行在 EDT 上，不能直接阻塞等待（会卡死 EDT 自身）。
 * 因此用 [PlatformTestUtil.dispatchAllInvocationEvents] 泵掉排队中的 invokeLater，
 * 循环直到回调置位或超时。
 */
class AsyncResultDeliveryTest : LightCodeInsightFixtureTestCase() {

    fun testPooledThreadInvokeLaterReachesEdt() {
        val done = AtomicBoolean(false)
        ApplicationManager.getApplication().executeOnPooledThread {
            // 模拟网络耗时
            Thread.sleep(50)
            ApplicationManager.getApplication().invokeLater {
                done.set(true)
            }
        }
        // 边派发达 EDT 事件边等待回调执行（最多 10s）
        PlatformTestUtil.waitWithEventsDispatching("invokeLater 未送达 EDT", { done.get() }, 10_000)
        assertTrue("invokeLater 回调未能在 EDT 上执行（10s 超时）", done.get())
    }
}
