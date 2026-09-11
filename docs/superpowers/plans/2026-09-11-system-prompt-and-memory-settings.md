# 系统提示词模板与永久 Memory 设置 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Settings → Tools → AiCodeCopilot 下新增 "Prompts & Memory" 子页，支持多套系统提示词模板的增删改与激活切换，以及全局永久 Memory 文本；发送消息时按"激活模板 + Memory"组装 system prompt。

**Architecture:** 新建应用级服务 `PromptManager`（`PersistentStateComponent`，独立 `aicodeprompts.xml`），仿照现有 `ProviderManager` 模式。设置页改为树形结构：现有 Provider 页挂到新的非配置根节点下，新子页 `PromptsMemoryConfigurable` 同级挂载。`ChatController.buildRequest` 改为调用纯函数 `buildSystemPrompt(template, memory)` 组装第一条 system 消息。

**Tech Stack:** Kotlin, IntelliJ Platform SDK (Swing UI, `Configurable`, `PersistentStateComponent`, `DialogWrapper`), JUnit (LightCodeInsightFixtureTestCase)

**设计文档:** `docs/superpowers/specs/2026-09-11-system-prompt-and-memory-settings-design.md`

---

## 文件结构

| 动作 | 路径 | 职责 |
| --- | --- | --- |
| Create | `src/main/kotlin/com/aicodecopilot/plugin/provider/PromptManager.kt` | PromptTemplate 模型 + PromptManagerState + PromptManager 服务 + buildSystemPrompt 纯函数 |
| Create | `src/main/kotlin/com/aicodecopilot/plugin/provider/PromptTemplateDialog.kt` | 模板添加/编辑对话框 |
| Create | `src/main/kotlin/com/aicodecopilot/plugin/provider/PromptsMemoryConfigurable.kt` | 设置子页：模板列表 + Memory 文本域 |
| Modify | `src/main/resources/META-INF/plugin.xml` | 注册根节点 + 两个子 Configurable + PromptManager 服务 |
| Modify | `src/main/kotlin/com/aicodecopilot/plugin/chat/ChatController.kt:294-307` | buildRequest 使用 buildSystemPrompt |
| Create | `src/test/kotlin/com/aicodecopilot/plugin/provider/PromptManagerTest.kt` | 服务 + 序列化 + buildSystemPrompt 单测 |

依赖 `ChatController.SYSTEM_PROMPT`（内置默认提示词，保持不动，作为回退值）。

**注意：** `PromptManager.kt` 中的 `PromptTemplate`、`PromptManagerState` 必须是**顶层类**（不能 inner/nested）——平台 KotlinAwareBeanBinding 通过 Kotlin 反射 `callBy` 调无参构造器，inner 类会抛 `IllegalArgumentException`（同 `ProviderManagerState` 的历史教训，见其注释）。

---

### Task 1: PromptManager — 模型、服务与序列化

**Files:**
- Create: `src/main/kotlin/com/aicodecopilot/plugin/provider/PromptManager.kt`
- Test: `src/test/kotlin/com/aicodecopilot/plugin/provider/PromptManagerTest.kt`

- [ ] **Step 1: 写失败测试（服务行为 + 序列化往返）**

创建 `src/test/kotlin/com/aicodecopilot/plugin/provider/PromptManagerTest.kt`：

```kotlin
package com.aicodecopilot.plugin.provider

import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase
import com.intellij.util.xmlb.XmlSerializer

/**
 * PromptManager 应用级服务测试（需要平台测试环境）。
 * 每个测试用例使用独立的测试 Application，互不干扰。
 */
class PromptManagerTest : LightCodeInsightFixtureTestCase() {

    private val manager: PromptManager get() = PromptManager.getInstance()

    /** @State 载体走真实 XmlSerializer 往返（平台存储的同一代码路径）。 */
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

    fun testMemoryReadWrite() {
        manager.setMemory("hello memory")
        assertEquals("hello memory", manager.memory)
        manager.setMemory("")
        assertEquals("", manager.memory)
    }
}
```

- [ ] **Step 2: 运行测试确认编译失败**

Run: `./gradlew test --tests "com.aicodecopilot.plugin.provider.PromptManagerTest"`
Expected: 编译错误 `Unresolved reference: PromptManager`

- [ ] **Step 3: 实现 PromptManager**

创建 `src/main/kotlin/com/aicodecopilot/plugin/provider/PromptManager.kt`：

```kotlin
package com.aicodecopilot.plugin.provider

import com.aicodecopilot.plugin.chat.ChatController
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.io.Serializable
import java.util.UUID

/**
 * 一套系统提示词模板。
 */
data class PromptTemplate(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var content: String = ""
) : Serializable {
    override fun toString(): String = name
}

/**
 * 持久化状态（[PromptManager] 的 @State 载体）。
 *
 * 必须是**顶层类**（不能是 inner/nested）：平台的 KotlinAwareBeanBinding
 * 通过 Kotlin 反射 `callBy` 调无参构造器实例化它（同 [ProviderManagerState] 的约束）。
 */
class PromptManagerState : Serializable {
    var templates: MutableList<PromptTemplate> = mutableListOf()
    var activeTemplateId: String? = null
    var memory: String = ""
}

/**
 * 系统提示词模板 + 永久 Memory 管理器（应用级服务）。
 * 配置持久化在 <config>/options/aicodeprompts.xml。
 */
@State(
    name = "AiCodePromptSettings",
    storages = [Storage("aicodeprompts.xml")]
)
class PromptManager : PersistentStateComponent<PromptManagerState> {

    @Volatile
    private var state: PromptManagerState? = null

    override fun getState(): PromptManagerState? = state ?: createDefaultState().also { state = it }

    override fun loadState(state: PromptManagerState) {
        this.state = state
    }

    // ------------------------------------------------------------------
    // 模板
    // ------------------------------------------------------------------

    fun all(): List<PromptTemplate> = state?.templates?.toList() ?: ensureDefault().templates.toList()

    fun byId(id: String?): PromptTemplate? = id?.let { all().firstOrNull { t -> t.id == it } }

    /** 当前激活的模板；未激活任何模板时返回 null（发送时回退内置默认提示词）。 */
    fun activeTemplate(): PromptTemplate? = byId(activeTemplateId())

    fun setActive(id: String) {
        val s = ensureState()
        s.activeTemplateId = id
        commit(s)
    }

    fun add(template: PromptTemplate) {
        val s = ensureState()
        s.templates.add(template)
        if (s.activeTemplateId == null) s.activeTemplateId = template.id
        commit(s)
    }

    fun update(template: PromptTemplate) {
        val s = ensureState()
        val index = s.templates.indexOfFirst { it.id == template.id }
        if (index >= 0) s.templates[index] = template
        commit(s)
    }

    fun remove(id: String) {
        val s = ensureState()
        s.templates.removeAll { it.id == id }
        if (s.activeTemplateId == id) s.activeTemplateId = null
        commit(s)
    }

    // ------------------------------------------------------------------
    // 永久 Memory
    // ------------------------------------------------------------------

    var memory: String
        get() = ensureState().memory
        set(value) = setMemory(value)

    fun setMemory(text: String) {
        val s = ensureState()
        s.memory = text
        commit(s)
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun activeTemplateId(): String? = ensureState().activeTemplateId

    private fun ensureState(): PromptManagerState = state ?: ensureDefault()

    private fun ensureDefault(): PromptManagerState {
        val s = state
        if (s != null) return s
        return createDefaultState().also { state = it }
    }

    private fun createDefaultState(): PromptManagerState = PromptManagerState().apply {
        templates = mutableListOf(
            PromptTemplate(name = "Default", content = ChatController.SYSTEM_PROMPT)
        )
        activeTemplateId = templates.first().id
    }

    private fun commit(s: PromptManagerState) {
        state = s
    }

    companion object {
        fun getInstance(): PromptManager =
            ApplicationManager.getApplication().getService(PromptManager::class.java)
    }
}
```

同时确认 `ChatController.SYSTEM_PROMPT` 是 public（当前 companion 中已是 `const val`，无需改动）。

- [ ] **Step 4: 在 plugin.xml 注册服务**

`src/main/resources/META-INF/plugin.xml` 的 `<extensions>` 中、现有 `applicationService` 之后加：

```xml
        <!-- 系统提示词模板 + 永久 Memory（应用级） -->
        <applicationService serviceImplementation="com.aicodecopilot.plugin.provider.PromptManager"/>
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew test --tests "com.aicodecopilot.plugin.provider.PromptManagerTest"`
Expected: 全部 PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/aicodecopilot/plugin/provider/PromptManager.kt \
        src/test/kotlin/com/aicodecopilot/plugin/provider/PromptManagerTest.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "Add PromptManager service with prompt templates and permanent memory"
```

---

### Task 2: buildSystemPrompt 纯函数 + ChatController 接入

**Files:**
- Modify: `src/main/kotlin/com/aicodecopilot/plugin/provider/PromptManager.kt`（companion 增加纯函数）
- Modify: `src/main/kotlin/com/aicodecopilot/plugin/chat/ChatController.kt:294-307`
- Test: `src/test/kotlin/com/aicodecopilot/plugin/provider/PromptManagerTest.kt`

- [ ] **Step 1: 写失败测试（追加到 PromptManagerTest 类末尾）**

```kotlin
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
```

测试文件顶部补充 import：

```kotlin
import com.aicodecopilot.plugin.chat.ChatController
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests "com.aicodecopilot.plugin.provider.PromptManagerTest"`
Expected: 编译错误 `Unresolved reference: buildSystemPrompt`

- [ ] **Step 3: 实现 buildSystemPrompt**

`PromptManager.kt` 的 `companion object` 改为：

```kotlin
    companion object {
        fun getInstance(): PromptManager =
            ApplicationManager.getApplication().getService(PromptManager::class.java)

        /**
         * 组装发送给 LLM 的 system prompt：
         * 模板内容（空白/缺失回退内置默认）+ 可选的 Permanent Memory 段。
         */
        fun buildSystemPrompt(templateContent: String?, memory: String): String {
            val base = templateContent?.takeIf { it.isNotBlank() }
                ?: ChatController.SYSTEM_PROMPT
            return if (memory.isBlank()) base
            else base + "\n\n# Permanent Memory\n" + memory.trim()
        }
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests "com.aicodecopilot.plugin.provider.PromptManagerTest"`
Expected: 全部 PASS

- [ ] **Step 5: 改造 ChatController.buildRequest**

`src/main/kotlin/com/aicodecopilot/plugin/chat/ChatController.kt` 中 `buildRequest` 的第一条 system 消息（当前第 295 行 `LlmClient.ChatMessage(Role.SYSTEM, SYSTEM_PROMPT)`）改为：

```kotlin
    private fun buildRequest(s: Session, contextBlocks: List<String>): List<LlmClient.ChatMessage> {
        val mgr = PromptManager.getInstance()
        val systemPrompt = PromptManager.buildSystemPrompt(
            mgr.activeTemplate()?.content,
            mgr.memory
        )
        val out = mutableListOf<LlmClient.ChatMessage>(LlmClient.ChatMessage(Role.SYSTEM, systemPrompt))
```

（`if (contextBlocks.isNotEmpty()) {...}` 及之后的逻辑不动。）

顶部补充 import：

```kotlin
import com.aicodecopilot.plugin.provider.PromptManager
```

- [ ] **Step 6: 全量测试确认无回归**

Run: `./gradlew test`
Expected: 全部 PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/aicodecopilot/plugin/provider/PromptManager.kt \
        src/main/kotlin/com/aicodecopilot/plugin/chat/ChatController.kt \
        src/test/kotlin/com/aicodecopilot/plugin/provider/PromptManagerTest.kt
git commit -m "Wire buildSystemPrompt into chat requests (active template + permanent memory)"
```

---

### Task 3: PromptTemplateDialog 编辑对话框

**Files:**
- Create: `src/main/kotlin/com/aicodecopilot/plugin/provider/PromptTemplateDialog.kt`

纯 UI 组件，无逻辑分支值得单测（沿用 `ProviderConfigDialog` 无测试的先例）；行为由 Task 4 的设置页集成验证。

- [ ] **Step 1: 实现对话框**

创建 `src/main/kotlin/com/aicodecopilot/plugin/provider/PromptTemplateDialog.kt`：

```kotlin
package com.aicodecopilot.plugin.provider

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 添加 / 编辑系统提示词模板的对话框。
 * 字段：模板名 + 提示词内容（多行）。
 */
class PromptTemplateDialog(private val existing: PromptTemplate?) : DialogWrapper(null, true) {

    private val source = existing ?: PromptTemplate()

    private val nameField = JBTextField(source.name)
    private val contentArea = JBTextArea(10, 40).apply {
        text = source.content
        lineWrap = true
        wrapStyleWord = true
    }

    /** OK 按钮回调（由 Settings 面板设置，用于非模态下持久化表单结果）。 */
    private var okActionListener: (() -> Unit)? = null

    fun setOKActionListener(listener: () -> Unit) {
        okActionListener = listener
    }

    override fun doOKAction() {
        super.doOKAction()
        okActionListener?.invoke()
    }

    init {
        title = if (existing == null) "Add Prompt Template" else "Edit Prompt Template — ${existing.name}"
        init()
        isModal = false
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.border = JBUI.Borders.empty(8)
        panel.preferredSize = Dimension(520, 380)

        val north = JPanel(BorderLayout(0, 4))
        north.add(JLabel("Name"), BorderLayout.WEST)
        north.add(nameField, BorderLayout.CENTER)

        val center = JPanel(BorderLayout(0, 4))
        center.add(JBLabel("Prompt Content"), BorderLayout.NORTH)
        center.add(JBScrollPane(contentArea), BorderLayout.CENTER)

        panel.add(north, BorderLayout.NORTH)
        panel.add(center, BorderLayout.CENTER)
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) return ValidationInfo("Name is required", nameField)
        return null
    }

    fun buildResult(): PromptTemplate = PromptTemplate(
        id = source.id,
        name = nameField.text.trim().ifBlank { "(unnamed)" },
        content = contentArea.text
    )
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/aicodecopilot/plugin/provider/PromptTemplateDialog.kt
git commit -m "Add PromptTemplateDialog for template add/edit"
```

---

### Task 4: PromptsMemoryConfigurable 设置子页 + plugin.xml 树形结构

**Files:**
- Create: `src/main/kotlin/com/aicodecopilot/plugin/provider/PromptsMemoryConfigurable.kt`
- Modify: `src/main/resources/META-INF/plugin.xml:38-41`

- [ ] **Step 1: 实现设置子页**

创建 `src/main/kotlin/com/aicodecopilot/plugin/provider/PromptsMemoryConfigurable.kt`：

```kotlin
package com.aicodecopilot.plugin.provider

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridLayout

/**
 * Settings → Tools → AiCodeCopilot → Prompts & Memory
 *
 * 上半部：系统提示词模板列表（★ 为当前激活）+ 增 / 改 / 删 / 设为默认。
 * 下半部：永久 Memory 多行文本域。
 * 所有修改立即持久化（PromptManager 直接落盘），无需再点 Apply。
 */
class PromptsMemoryConfigurable : Configurable {

    override fun getDisplayName(): String = "Prompts & Memory"

    private var panel: JComponent? = null
    private var list: JList<PromptTemplate>? = null
    private var memoryArea: JBTextArea? = null

    private val manager: PromptManager get() = PromptManager.getInstance()

    override fun createComponent(): JComponent {
        // ---- 模板列表 ----
        val model = DefaultListModel<PromptTemplate>()
        val theList = JList<PromptTemplate>(model)
        theList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        theList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                listOf: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                val label = super.getListCellRendererComponent(listOf, value, index, isSelected, cellHasFocus)
                val t = value as? PromptTemplate
                if (t != null && label is JLabel) {
                    val active = t.id == manager.activeTemplate()?.id
                    label.text = (if (active) "★ " else "  ") + t.name
                }
                return label
            }
        }
        this.list = theList

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        buttons.add(button("Add…") { showDialog(null) })
        buttons.add(button("Edit…") { showDialog(selected()) })
        buttons.add(button("Remove") { removeSelected() })
        buttons.add(button("Set as Default") { setDefault() })

        val templatesTitle = JBLabel("System Prompt Templates")
        val templatesPanel = JPanel(BorderLayout(0, 4)).apply {
            add(templatesTitle, BorderLayout.NORTH)
            add(JBScrollPane(theList), BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }

        // ---- 永久 Memory ----
        val area = JBTextArea(6, 40).apply {
            text = manager.memory
            lineWrap = true
            wrapStyleWord = true
        }
        this.memoryArea = area
        area.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = saveMemory()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = saveMemory()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = saveMemory()
        })

        val memoryTitle = JBLabel("Permanent Memory")
        val memoryHint = JBLabel(
            "Appended to the system prompt for every conversation. Keep under ~8000 characters.",
            JBColor.GRAY, JBLabel.LEFT
        )
        val memoryPanel = JPanel(BorderLayout(0, 4)).apply {
            add(memoryTitle, BorderLayout.NORTH)
            add(JBScrollPane(area), BorderLayout.CENTER)
            add(memoryHint, BorderLayout.SOUTH)
        }

        // ---- 组合：上下各占一半 ----
        val p = JPanel(GridLayout(2, 1, 0, 12)).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        }
        p.add(templatesPanel)
        p.add(memoryPanel)

        panel = p
        reload()
        return p
    }

    private fun button(label: String, action: () -> Unit): JButton =
        JButton(label).apply { addActionListener { action() } }

    private fun saveMemory() {
        manager.setMemory(memoryArea?.text ?: "")
    }

    private fun reload() {
        val model = list?.model as? DefaultListModel<PromptTemplate> ?: return
        model.clear()
        manager.all().forEach { model.addElement(it) }
        val activeId = manager.activeTemplate()?.id
        val idx = (0 until model.size()).firstOrNull { model.get(it).id == activeId }
        if (idx != null) list?.selectedIndex = idx
        else if (model.size > 0) list?.selectedIndex = 0
    }

    private fun selected(): PromptTemplate? = list?.selectedValue

    private fun showDialog(existing: PromptTemplate?) {
        val dialog = PromptTemplateDialog(existing)
        dialog.setOKActionListener {
            val t = dialog.buildResult()
            if (existing == null) manager.add(t) else manager.update(t)
            reload()
        }
        dialog.show()
    }

    private fun removeSelected() {
        val t = selected() ?: return
        val parent = panel ?: return
        if (Messages.showYesNoDialog(
                parent,
                "Remove prompt template “${t.name}”?",
                "AiCodeCopilot Settings",
                Messages.getWarningIcon()
            ) != Messages.YES
        ) return
        manager.remove(t.id)
        reload()
    }

    private fun setDefault() {
        val t = selected() ?: return
        manager.setActive(t.id)
        reload()
    }

    override fun isModified(): Boolean = false

    override fun apply() {
        // 所有变更在发生时已直接持久化到 PromptManager
    }

    override fun reset() {
        reload()
        memoryArea?.text = manager.memory
    }
}
```

- [ ] **Step 2: plugin.xml 改为树形结构**

`src/main/resources/META-INF/plugin.xml` 中现有 `<applicationConfigurable .../>`（第 38-41 行）替换为：

```xml
        <!-- Settings → Tools → AiCodeCopilot（根节点，不可配置，仅作容器） -->
        <applicationConfigurable
                id="aicodecopilot.settings.root"
                parentId="tools"
                displayName="AiCodeCopilot"
                nonConfigurable="true"/>

        <!-- 子页：Provider 管理 -->
        <applicationConfigurable
                id="tools.aicodecopilot.providers"
                parentId="aicodecopilot.settings.root"
                instance="com.aicodecopilot.plugin.provider.AiCodeCopilotSettingsConfigurable"/>

        <!-- 子页：系统提示词模板 + 永久 Memory -->
        <applicationConfigurable
                id="aicodecopilot.settings.prompts"
                parentId="aicodecopilot.settings.root"
                instance="com.aicodecopilot.plugin.provider.PromptsMemoryConfigurable"/>
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew buildPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 全量测试**

Run: `./gradlew test`
Expected: 全部 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/aicodecopilot/plugin/provider/PromptsMemoryConfigurable.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "Add Prompts & Memory settings page under AiCodeCopilot settings root"
```

---

### Task 5: 文档更新（README）

**Files:**
- Modify: `README.md:7-17`（功能特性表）、`README.md:53-64`（配置说明）

- [ ] **Step 1: 功能特性表加一行**

`README.md` 功能特性表格（`| ⌨ 斜杠命令 |` 行之前）加：

```markdown
| 📝 提示词模板 & Memory | Settings → Tools → AiCodeCopilot → Prompts & Memory：多套系统提示词模板（增删改、激活切换）+ 永久 Memory（追加到每次请求） |
```

- [ ] **Step 2: 配置说明处补充**

"### 3. 配置 Provider" 章节末尾（`5. 用 **Set as Default** 设为默认` 之后）加：

```markdown
### 3.1 配置系统提示词与永久 Memory

1. **Settings → Tools → AiCodeCopilot → Prompts & Memory**
2. 在 **System Prompt Templates** 中添加 / 编辑 / 删除模板，**Set as Default** 激活其中一套（★）
3. 在 **Permanent Memory** 中填写需要长期生效的背景信息（如个人偏好、项目约定），每次请求都会追加到系统提示词
4. 未激活任何模板时自动使用内置默认提示词
```

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "Document prompt templates and permanent memory settings"
```

---

## 验收清单（对照 spec）

- [ ] 多套模板：添加、编辑、删除、Set as Default 激活（★ 标记）
- [ ] 删除激活模板 → activeTemplateId 置 null → 发送回退内置默认
- [ ] 永久 Memory 全局文本域，即时持久化
- [ ] 发送时 system prompt = 激活模板（空白回退默认）+ "# Permanent Memory" 段
- [ ] 设置树：AiCodeCopilot 根节点下 Providers / Prompts & Memory 两个子页
- [ ] `./gradlew test` 全部通过；`./gradlew buildPlugin` 构建成功
