# 设计文档：配置界面增加系统提示词与永久 Memory 设置

日期：2026-09-11
状态：已确认（用户批准）

## 背景与目标

AiCodeCopilot 当前系统提示词硬编码在 `ChatController.SYSTEM_PROMPT`，
Settings → Tools → AiCodeCopilot 只能管理 Provider。本设计在设置界面增加：

1. **多套系统提示词模板**：添加、编辑、删除、激活切换（对应 plan.txt 第 1 条）
2. **永久 Memory**：全局多行文本，对所有会话生效（对应 plan.txt 第 3 条）

## 需求决策记录

| 决策点 | 选择 |
| --- | --- |
| 系统提示词形式 | 多套模板管理（未激活任何模板时回退内置默认） |
| 永久 Memory 形式 | 全局单文本域（不做多条可开关） |
| 设置页布局 | 子 Configurable（Providers / Prompts & Memory 两个子页） |
| 持久化架构 | 新建独立 `PromptManager` 应用级服务（方案 A） |

## 1. 数据模型与持久化

新文件：`src/main/kotlin/com/aicodecopilot/plugin/provider/PromptManager.kt`

```kotlin
class PromptTemplate(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var content: String = ""
)

class PromptManagerState : Serializable {
    var templates: MutableList<PromptTemplate> = mutableListOf()
    var activeTemplateId: String? = null
    var memory: String = ""
}

@State(name = "AiCodePromptSettings", storages = [Storage("aicodeprompts.xml")])
class PromptManager : PersistentStateComponent<PromptManagerState>
```

- 顶层类（不能用 inner/nested，同 `ProviderManagerState` 的反射约束）
- API：`all() / byId(id) / add(t) / update(t) / remove(id) / setActive(id) /
  activeTemplate(): PromptTemplate? / memory / setMemory(text)`
- 每次变更立即落盘（`state = s`，平台负责持久化），与 ProviderManager 一致
- 首次启动预置 1 个模板 "Default"，内容 = 现有 `ChatController.SYSTEM_PROMPT`
- 删除当前激活模板时 `activeTemplateId` 置 null → 发送时回退内置默认提示词
- `getInstance()` 通过 `ApplicationManager.getApplication().getService(...)`

## 2. 设置界面（子 Configurable）

### plugin.xml

- 现有 `AiCodeCopilotSettingsConfigurable` 注册处加 `parentId="aicodecopilot.settings.root"`
  并提供 `com.intellij.ConfigurableEP` 根节点（id=`aicodecopilot.settings.root`，
  displayName="AiCodeCopilot"，nonConfigurable=true）→ 变成设置树中的父节点
- 新建 `PromptsMemoryConfigurable` 挂到根节点下（parentId 同上）

### PromptsMemoryConfigurable（包 `provider/` 下）

布局仿现有 Provider 页风格（BorderLayout + JBList + 按钮行 + 即时持久化）：

- **上半部 — System Prompt Templates**
  - 模板列表（JBList，cellRenderer 用 "★ " 标激活项，同 Provider 页）
  - 按钮：Add… / Edit… / Remove / Set as Default
  - 编辑对话框：name 单行文本框 + content 多行文本域（JBScrollPane），
    复用 `ProviderConfigDialog` 的 `setOKActionListener + show()` 非模态模式
- **下半部 — Permanent Memory**
  - JBLabel 说明："Appended to the system prompt for every conversation."
  - 多行文本域（可换行、带滚动），document listener 即时保存到 PromptManager
  - UI 提示建议控制在 8000 字符内（不硬限制）

### 交互约定

- 所有修改即时持久化（同现有 Provider 页），`isModified()` 返回 false，
  `apply()` 为空操作，`reset()` 重新 reload
- 删除模板需 Yes/No 确认（同现有 Remove Provider）
- 模板名允许重复（靠 id 区分）；空名保存时截断为 "(unnamed)" 提示处理

## 3. 发送时组装（ChatController 改造）

`buildRequest` 中第一条 system 消息由纯函数生成：

```kotlin
fun buildSystemPrompt(templateContent: String?, memory: String): String
```

规则：

1. 基础段 = `templateContent`（激活模板的 content）；当模板内容为 null 或空白时，回退内置 `ChatController.SYSTEM_PROMPT`
2. 当 `memory` 非空时追加 `"\n\n# Permanent Memory\n" + memory`
3. 返回拼接结果

- 上下文块逻辑不变，仍作为独立的第二条 system 消息
- `buildSystemPrompt` 放在 PromptManager 的 companion object，供 ChatController 与单测直接调用

## 4. 错误处理

| 场景 | 处理 |
| --- | --- |
| 模板名为空 | 保存时截为 "(unnamed)" |
| 删除激活中的模板 | activeTemplateId 置 null，发送回退内置默认 |
| memory 超长 | 不硬限制，UI 提示建议 ≤8000 字符 |
| 模板 content 为空 | 允许保存；发送时模板内容视为空白 → 拼接结果以内置默认提示词作为开头（即空白模板等同未配置），memory 仍照常追加 |

## 5. 测试

- `PromptManagerTest`（参考 `ProviderManagerTest`）：
  - 默认状态含 1 个 "Default" 模板且激活
  - add/update/remove、setActive 切换
  - 删除激活模板后 activeTemplate() 返回 null
  - memory 读写
- `buildSystemPrompt` 单测：
  - null 模板 + 空 memory → 内置默认
  - 模板 + memory → 两段拼接
  - 模板 + 空 memory → 只有模板
  - 空白模板内容 + memory → 内置默认 + memory
- 现有测试保持通过（`./gradlew test`）

## 6. 不做的事（YAGNI）

- 不做模板变量/占位符替换
- 不做 memory 的多条可开关管理
- 不做项目级（per-project）提示词，全部应用级
- 不做 Agent.md / Memory.md 文件导入
