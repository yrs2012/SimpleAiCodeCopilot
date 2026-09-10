# AiCodeCopilot 架构设计

## 总览

```
┌────────────────────────────────────────────────────────────────┐
│  Android Studio (IntelliJ Platform)                            │
│                                                                │
│  ┌──────────────┐   ┌──────────────────────────────────────┐  │
│  │ Editor 右键   │   │  AiCodeCopilot 工具窗口 (右侧)               │  │
│  │ Tools 菜单    │   │  ┌────────────────────────────────┐  │  │
│  │ SendToAiCode │──▶│  │ ChatPanel (Swing UI)            │  │  │
│  │   Action     │   │  │  会话下拉/Provider/上下文/消息区   │  │  │
│  └──────────────┘   │  └───────────────┬────────────────┘  │  │
│        │            │                  │                    │  │
│        ▼            │  ┌───────────────▼────────────────┐   │  │
│  ┌──────────────┐   │  │ ChatController                │   │  │
│  │ AiCodeUi     │──▶│  │  会话状态 · 流式任务 · 上下文   │   │  │
│  │ Service      │   │  └──────┬──────────────┬─────────┘   │  │
│  │ (project)    │   │         │              │              │  │
│  └──────────────┘   │         ▼              ▼              │  │
│                     │  ┌────────────┐  ┌────────────────┐   │  │
│                     │  │SessionRepo │  │  LlmClient     │   │  │
│                     │  │(JSON 持久化)│  │ (HTTP + SSE)   │   │  │
│                     │  └────────────┘  └───────┬────────┘   │  │
│                     │                          │            │  │
│                     └──────────────────────────┼────────────┘  │
│                                                ▼               │
│                                     OpenAI 兼容 API            │
```

## 模块职责

### 1. Provider 层（`provider/`）

- **`ProviderConfig`**：单个 Provider 的不可变值对象（id/name/baseUrl/apiKey/
  model/temperature/maxTokens/extraHeaders/enabled/`models`）。`models` 是该
  Provider 下可用模型 id 列表（由“自动添加模型”从网络拉取后填充），随
  `@State` 持久化，向后兼容旧配置（缺省为空）。
- **`ProviderManager`**：应用级服务（`@State` + `PersistentStateComponent`），
  持久化到 `<config>/options/aicode.xml`。首次启动自动注入 OpenAI / DeepSeek /
  Ollama 三个预置。任何变更立即落盘（设置页因此无需 Apply）。
  @State 载体为**顶层类** `ProviderManagerState`（不能 inner：平台
  `KotlinAwareBeanBinding` 经 Kotlin 反射 `callBy` 调无参构造器，inner 类构造器
  带隐式外部实例参数 `instance` → 序列化抛 `IllegalArgumentException`、配置落盘失败）。
- **`LlmClient`**：OpenAI Chat Completions 客户端。
  - 使用 JDK `java.net.http.HttpClient`，无第三方 HTTP 依赖；
  - **强制直连**（`.proxy(ProxySelector.of(null))`）：不走系统/OS 代理，
    避免内网 base_url 经代理不可达而长时间挂起；
  - **硬超时**：建连 15s（chat 默认 30s）、请求 30s（testConnection）/
    60s（chat 10min / listModels），`HttpTimeoutException`/`ConnectException`/
    `UnknownHostException` 统一映射为带明确文案的 `LlmException`；
  - **全程日志**（`AiCodeCopilot:` 前缀，`idea.log`）：请求发起（URL/model/超时）、
    响应状态 + 耗时、完成字符数、各类失败，用于定位“卡住/无提示”类问题；
  - JSON 使用平台内置的 `org.json`；
  - 支持 SSE 流式（`data: {...}` 行协议，`[DONE]` 结束），并对不返回
    `Content-Type: text/event-stream` 的服务做“首行嗅探”兜底；
  - 通过 `AtomicBoolean` 支持取消（停止按钮）；
  - `listModels(provider)`：`GET {baseUrl}/models`（`Bearer` 鉴权 + 自定义头，
    60s 超时），解析 `data[].id` 为**去重保序**的模型列表；非 2xx 抛
    `LlmException(httpStatus)`。兼容 OpenAI / DeepSeek / OpenRouter / Ollama
    等 OpenAI 风格端点。
- **`ProviderConfigDialog`**：Provider 设置对话框。含 **“Auto Add Models”**
  按钮：先校验 name/baseUrl，后台线程（`executeOnPooledThread`）调
  `listModels`，结果 `invokeLater` 回 EDT 后：
  - **成功且列表非空** → 直接弹出 `ModelSelectionDialog`（父窗 = 本对话框
    `getRootPane()`，保证显示在前方）；确认后把所选模型写回对话框的模型列表
    （`models`），Model 字段为空或不在所选列表时回填第一个；
  - **失败** → 错误弹窗（含具体原因，如超时/连接被拒/DNS/HTTP 状态码）；
  - **运行中再次点击按钮 = 取消**（按钮文案 “Fetching… (click to cancel)”）；
  - 对话框在等待期间被关闭 → 结果丢弃并记日志（避免“关窗后才弹框”的怪象）。
  “Test Connection” 同模型（“Testing… (click to cancel)”），成功/失败均弹窗。
  模型列表支持多选删除（“Remove Selected”）。
- **`ModelSelectionDialog`**：`DialogWrapper` + 复选框风格 `JList`
  （`☑/☐` 前缀渲染器）。勾选状态由独立 `Set<String>` 维护（与 JList 原生
  选中机制解耦，绕开 BasicListUI 普通单击“独占选中”行为）：
  **单击某行 = 选中；再次单击 = 取消；连续点击多行 = 累加，互不排斥**。
  返回按列表顺序勾选的模型 id。

### 2. 会话层（`chat/Session*.kt`）

- **`Session`**：一次完整对话 = 消息列表 + 上下文条目 + 所用 Provider id
  + 所选模型 id（`modelId`）。
- **`StoredMessage`** / **`StoredContextEntry`**：持久化的消息与上下文条目。
- **`SessionRepository`**：每个会话一个 JSON 文件，存放于
  `<system>/aicode/sessions/<id>.json`（应用级目录，跨项目共享）。
  写入采用“临时文件 + 原子替换”避免半截文件。
- 会话与项目解耦（只记录 `projectName` 用于展示），因此更换工作区也能找回
  历史对话；上下文中的文件路径在恢复时重新解析，失效文件标注 “not found”。
- **`Session.modelId`**：会话级模型选择（可选）。持久化于会话 JSON 的
  `modelId` 字段（`optString` 缺省 `""`，向后兼容）。切换 Provider 时清空，
  避免跨 Provider 的陈旧模型 id。

### 3. 上下文层（`chat/ContextResolver.kt`）

把上下文条目解析为 Prompt 文本块：

| 类型 | 行为 |
| --- | --- |
| FILE | 请求时实时读取文件最新内容（单文件 60,000 字符截断） |
| FOLDER | 递归 BFS 扫描；跳过 `.git`/`build`/`node_modules` 等目录与二进制文件；上限 200 文件 / 400,000 字符 |
| SNIPPET | “Send to AiCodeCopilot” 选区的内容（内联保存，不随文件变化丢失） |

所有上下文以 system 消息的形式附加在请求中（独立于系统提示词，便于模型区分）。

### 4. 控制层（`chat/ChatController.kt`）

每个 ChatPanel 持有一个 Controller：

- 会话生命周期：`newChat()` / `loadSession()` / `deleteCurrentSession()`
- 模型选择：`setModel(id)`（写入会话 `modelId` 并落盘）、`activeModel()`
  （会话所选 ∈ `provider.models` → 之；否则 `provider.models` 首个；再否则
  `provider.model`）、`availableModels()`（`models` 非空则用之，否则
  `[provider.model]`）。`setProvider()` 会清空会话模型选择。
- 发送流程（`send()`）：
  1. 取当前 Provider（会话指定 > 全局默认）；模型取 `activeModel()`
     （与会话/Provider 默认不同时 `copy(model=...)` 覆盖请求）；
  2. 追加 user 消息；首个消息时生成会话标题并落盘；
  3. 组装请求：`system 提示词` + `上下文块` + `最近 40 条历史`；
  4. 预插入一条空 assistant 消息占位；
  5. 后台线程执行流式请求，增量追加到占位消息（`synchronized` 保护）；
  6. 结束/失败/停止后统一落盘，并通过 `ChatUiListener` 通知 UI（切 EDT）。
  全流程有 `AiCodeCopilot:` 日志：发送开始（provider/model/baseUrl/历史条数/上下文项数）、
  流式完成（耗时 + 字符数）、被停止、失败（耗时 + 原因），便于排查“无响应”。
- **线程模型**：请求在独立 `Thread("aicodecopilot-chat")` 上运行；所有 UI 回调经
  `ApplicationManager.invokeLater` 切回 EDT。停止通过 `AtomicBoolean` 协作取消。

### 5. UI 层（`chat/ChatPanel.kt`）

纯 Swing（不依赖 JCEF，避免渲染依赖问题）：

- 消息区为单个 `JEditorPane`（`contentType=text/html` + `HTMLEditorKit`，
  非可编辑、透明背景 + 正文样式表）：每个 delta 重渲染整段转录并滚动到底部；
  工具窗宽度变化时经 `ComponentListener` 重设 pane 尺寸让 HTML 按可视宽度换行。
  （252 平台已移除 `JBHtmlArea`；`JEditorPane` 对现有内联 CSS 的兼容零平台风险。
  历史上该区域是普通 `JTextArea` 直接显示 HTML 源码——错误气泡里会出现
  裸 `<html>` 标签，即本次修复的“显示一串 html 标签”问题。）
- 极简 Markdown：` ``` ` 围栏代码块 → `<pre>`、`` `code` `` → `<code>`，其余转义；
- Provider 行尾部有 **⚙ 设置按钮**（`AllIcons.General.GearPlain`）：打开
  `ProviderConfigDialog` 编辑当前 Provider 的 Base URL / API Key，并可
  “自动添加模型”；
- 输入框（`JBTextArea`）带 **`CompoundBorder`（LineBorder + 内边距）**，
  暗色主题下输入区轮廓清晰可见；
- 输入区上方为 **Model 下拉**（`JComboBox`）：先选 Provider 再选模型，
  选择写入会话（`setModel`），随 Provider 切换 / 会话切换自动刷新；
- 上下文以“chips”展示，可单独移除；
- `CardLayout` 在 Send / Stop 按钮间切换。

### 6. 动作与桥接

- **`AiCodeCopilotUiService`**（project 服务）：持有当前 ChatPanel 引用。
  工具窗口是惰性创建的——用户未打开窗口就触发 “Send to AiCodeCopilot” 时，
  待添加的片段/文件先暂存在服务里，面板创建后自动应用。
- **`SendToAiCodeCopilotAction`**：读取当前 PSI 文件与编辑器选区，写入服务并激活工具窗口。

## 数据流（一次发送）

```
用户输入
  └─▶ ChatPanel.onSendClicked()
        └─▶ ChatController.send()
              ├─ Session.messages += user
              ├─ SessionRepository.save()          (立即落盘)
              ├─ ContextResolver.resolve(entries)  (读取文件/文件夹)
              ├─ LlmClient.chat(stream=true)
              │     └─ onDelta ─▶ 追加到占位 assistant 消息 ─▶ UI 刷新 (EDT)
              ├─ SessionRepository.save()          (结束落盘)
              └─ ChatUiListener 回调 ─▶ 刷新会话列表/按钮状态
```

## 设计取舍

| 决策 | 理由 |
| --- | --- |
| Swing 而非 JCEF 聊天界面 | 少一个渲染依赖，行为可预测；代码量小、易维护 |
| 每次请求携带完整（截断后）历史 | 实现简单、行为可预期；40 条上限控制 token 消耗 |
| 上下文“请求时读取”而非“添加时快照” | AI 永远看到文件最新内容；SNIPPET 除外（选区是瞬时的） |
| 会话存应用级目录而非项目级 | 换工作区/重新打开项目后历史仍可继续 |
| `org.json` + `java.net.http` | `java.net.http` 为 JDK 内置；`org.json`（78KB）平台核心未内置，随插件打包，无其他第三方运行时依赖 |
| LLM 请求强制直连（不走系统代理） | base_url 多为自部署/内网；系统代理常拦不住内网地址导致请求挂起。若端点必须经代理，需系统层配直连例外 |
| sinceBuild=242 | 覆盖 2024.2 之后所有主流 Android Studio 版本 |

## 测试（`./gradlew test`，共 45 例）

| 测试类 | 框架 | 覆盖 |
| --- | --- | --- |
| `ProviderConfigTest` | JUnit 4（纯逻辑） | URL 拼接（含尾部 `/`）、`toString`、`copy` 保留 id、默认值 |
| `LlmClientTest` | JUnit 4 + 本机 `HttpServer` 假 LLM | 非流式 JSON、请求体/鉴权头/自定义头、SSE 流式解析（含无 `content-type` 嗅探）、`stream` 标志与 `Accept` 头、HTTP 401 → `LlmException(401)`、响应体 `error` 键 → 异常、流式中途取消（断言线程退出且耗时收敛）、`testConnection` 固定消息、`listModels`（`/models` 解析 `data[].id`、去重保序、空数组、HTTP 错误） |
| `ProviderManagerTest` | 平台 fixture（JUnit 3/4 风格） | 预置项与默认激活、激活切换、移除激活项回退到第一个启用项、原地更新、`enabled()` 过滤、新增不改激活、**`testStateXmlRoundTrip`（平台 `XmlSerializer` 对 `ProviderManagerState` 做真实序列化往返，回归保护 inner-class State 无法落盘 bug）** |
| `SessionRepositoryTest` | 平台 fixture | 保存/加载往返（含 `modelId`）、列表按 `updatedAt` 降序 + 删除、损坏 JSON 静默跳过、缺失文件返回 null、草稿不落盘 |
| `ContextResolverTest` | 平台 fixture + 真实磁盘临时目录 | 文件内容、二进制跳过、超长截断、文件夹递归扫描（隐藏/构建目录跳过 + 计数）、片段内联、文件/文件夹缺失标记 |
| `ChatControllerModelTest` | 平台 fixture | 模型回退链（会话所选 ∈ `models` → `models` 首个 → `provider.model`）、`availableModels`、换 Provider 清空陈旧模型选择 |
| `AsyncResultDeliveryTest` | 平台 fixture | 验证“后台线程 `executeOnPooledThread` → `invokeLater` 回 EDT”结果回传链可达（Test Connection / Auto Add Models 的线程模型回归保护；用 `waitWithEventsDispatching` 泵 EDT，规避“测试线程本身在 EDT 上不能阻塞等待”） |
| `ToolWindowSmokeTest` | 平台 fixture（工具窗冒烟） | 工具窗注册、工厂调用后 `contentManager.contents` 非空、`show()` 后内容存在；轻量测试 IDE 不加载插件 `toolWindow` EP 时自动跳过（JUnit3 `return` 语义） |
| `ChatPanelUiConstructionTest` | 平台 fixture + 真实 Swing/EDT | 真实构造新 `ChatPanel`：HTML 消息区 `contentType=text/html`、模型/Provider 下拉、设置按钮在组件树、输入框 `CompoundBorder` 内含 `LineBorder`；headless 环境自动跳过 |

说明：

- fixture 测试继承 `LightCodeInsightFixtureTestCase`
  （252 平台中位于 `com.intellij.testFramework.fixtures`，
  `LightPlatformTestCase` 已移出不带 fixtures 子包且无 `myFixture`）。
- `ContextResolverTest` 特意使用**磁盘**临时目录：fixture 的
  `findFileInTempDir` 位于内存版 `TempFileSystem`，而生产路径来自
  `JFileChooser`（真实磁盘），`LocalFileSystem` 才能解析。
- 轻量测试 IDE 只通过注解扫描注册插件的 `@Service`/`@State` 服务，
  **不处理 plugin.xml 的扩展点**（如 `toolWindow`），因此
  `ToolWindowSmokeTest` 在该环境下 `getToolWindow("AiCodeCopilot")` 为 null，
  用例以 `return` 跳过；在完整加载插件 EP 的环境（runIde 沙箱）中生效。
- 252 的 `ToolWindowFactory` 为 Kotlin 接口：`createToolWindowContent(Project, ToolWindow)`
  是**唯一抽象方法**（平台经 `ToolWindowImpl.createContentIfNeeded` 调用），
  其余（`manage`/`init`/`shouldBeAvailable`/`isApplicableAsync`/`getAnchor`/`getIcon`）
  均有默认实现。工厂内抛出的任何异常都会使内容创建中断，
  表现为工具窗 "Nothing to show"——见 `ChatPanel` 的 BoxLayout 容器参数
  （必须传实际挂载的 panel，否则 `AWTError: BoxLayout can't be shared`）。
