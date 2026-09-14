package com.aicodecopilot.plugin.provider

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import javax.swing.JComponent

/**
 * Settings → Tools → AiCodeCopilot 根节点。
 *
 * 仅作容器，本身无配置面板（返回空面板、非修改态）。
 * 子页：Providers（[AiCodeCopilotSettingsConfigurable]）与
 * Prompts & Memory（[PromptsMemoryConfigurable]）。
 *
 * 说明：新版平台（2025.2）的 ConfigurableEP 不再支持 `nonConfigurable` 属性，
 * 容器节点必须给出一个真实的 Configurable 类，否则报
 * "configurable class name is not set"。
 */
class AiCodeCopilotGroupConfigurable : SearchableConfigurable {

    override fun getDisplayName(): String = "AiCodeCopilot"

    override fun getId(): String = "aicodecopilot.group"

    override fun createComponent(): JComponent =
        com.intellij.ui.components.JBLabel("See the sub-pages on the left.")

    override fun isModified(): Boolean = false

    override fun apply() {
        // 容器节点，无状态
    }

    override fun reset() {
        // 容器节点，无状态
    }

    override fun disposeUIResources() {
        // 无资源
    }
}
