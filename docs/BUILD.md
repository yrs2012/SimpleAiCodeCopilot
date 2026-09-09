# 构建与故障排查

## 前置条件

| 组件 | 要求 | 说明 |
| --- | --- | --- |
| JDK | 17 或 21 | 运行 Gradle 的 JVM（构建不额外下载 JDK，直接用 `JAVA_HOME`）。Android Studio 自带 JBR 即可，例如：`<AS 安装目录>/jbr` |
| 磁盘 | 约 5GB 空闲 | 平台 SDK（~1.5GB）+ Gradle 缓存 + 构建产物 |
| 网络 | 可访问 JetBrains / Gradle 仓库 | 首次构建自动下载 |

## 构建步骤

```bash
# 1. （如系统 java 低于 17）指定 JDK
#    示例（本机 Corretto 17）：
export JAVA_HOME=~/.jdks/corretto-17.0.20.1/
#    或 Android Studio 自带 JBR：<AS 安装目录>/jbr

# 2. 构建
./gradlew buildPlugin          # Windows: gradlew.bat buildPlugin
```

产物：`build/distributions/AiCode-<version>.zip`

其他常用任务：

| 任务 | 作用 |
| --- | --- |
| `./gradlew buildPlugin` | 编译并打包安装 zip |
| `./gradlew test` | 运行单元测试（LlmClient / 会话持久化 / Provider 管理 / 上下文解析；首次会启动无头测试 IDE，较慢） |
| `./gradlew runIde` | 启动一个加载了本插件的沙箱 IDE 用于开发调试（会下载并启动 Android Studio） |
| `./gradlew verifyPluginProjectConfiguration` | 校验插件配置（plugin.xml 等） |

> 说明：本插件仅依赖平台核心模块（`com.intellij.modules.platform`），
> 不依赖 Android 专属模块，因此 `runIde` 在 IDEA 沙箱中同样可用，
> 但发布目标以 Android Studio 为准（`androidStudio("2025.2.3.9")`，
> 即 Otter 3 Feature Drop，platformBuild 252.28238.7）。
> 字节码目标固定为 JVM 17（与 242+ 平台的 JBR 17 一致），
> 用 JDK 17 或 21 运行 Gradle 均可构建。

## 测试

```bash
./gradlew test
```

共 45 个用例，分三类：

| 类型 | 用例 | 说明 |
| --- | --- | --- |
| 纯逻辑（JUnit 4） | `ProviderConfigTest`、`LlmClientTest` | 不启动 IDE，秒级完成。`LlmClientTest` 用本机 `HttpServer` 起一个假 LLM 端点，覆盖流式（SSE）/非流式、自定义头、错误码、取消、`testConnection`、`listModels`（`/models` 解析/去重/空/HTTP 错误） |
| 平台 fixture（`LightCodeInsightFixtureTestCase`，JUnit 3/4 风格） | `ProviderManagerTest`、`SessionRepositoryTest`、`ContextResolverTest`、`ChatControllerModelTest`、`AsyncResultDeliveryTest` | 启动无头测试 Application/Project，首次运行较慢。`ProviderManagerTest` 含 `testStateXmlRoundTrip`：用平台 `XmlSerializer` 对 @State 载体做真实 serialize/deserialize 往返（回归保护 inner-class State 无法序列化 bug）；`ChatControllerModelTest` 覆盖模型回退链（会话所选 → `models` 首个 → `provider.model`）与换 Provider 清空选择；`AsyncResultDeliveryTest` 验证“后台线程 → `invokeLater` 回 EDT”结果回传链可达（Test Connection / Auto Add Models 的线程模型回归保护） |
| 工具窗冒烟（平台 fixture） | `ToolWindowSmokeTest`、`ChatPanelUiConstructionTest` | `ToolWindowSmokeTest` 验证工具窗注册与工厂创建 content；**轻量测试 IDE 不加载被测插件 plugin.xml 的 `toolWindow` EP，故未注册时自动跳过**（JUnit3 `return` 语义），在加载完整插件 EP 的环境中（如 runIde 沙箱）断言真正生效；同时是 `BoxLayout` 容器参数回归保护。`ChatPanelUiConstructionTest` 在真实 Swing/EDT 环境构造 `ChatPanel`，断言 HTML 消息区、模型/Provider 下拉、设置按钮、输入框边框；headless 环境自动跳过 |

测试注意事项：

- fixture 测试通过平台 `JarRepository` 解析测试类路径上的 Maven 依赖
  （如 `org.jetbrains:annotations`），本地仓库默认为 `~/.m2/repository`。
  构建脚本会自动探测：若 `~/.m2` 实际不可写（无头/沙箱环境），
  会把测试 JVM 的 `user.home` 指到项目内 `.tools/test-home/`，
  否则会报 `IllegalStateException: No roots for 'org.jetbrains:annotations:...'`。
  正常开发机（`~/.m2` 可写）不受任何影响。
- 在 `-PplatformLocalPath`（本机 IDE 目录）模式下，构建脚本会自动把
  `<ide>/lib/testFramework.jar` 挂到测试编译类路径。
  走远程 SDK（`androidStudio(...)`）时，若测试编译报
  `Unresolved reference 'LightCodeInsightFixtureTestCase'`
  （fixture 基类未随 `test-framework` 依赖下发），
  加 `-PplatformLocalPath=/path/to/你的AndroidStudio` 再跑一次即可。

## 首次构建会下载什么

1. **Gradle 发行版**（若 wrapper 未命中缓存，约 140MB）
   —— wrapper 默认走 JetBrains 镜像 `cache-redirector.jetbrains.com`；
2. **Gradle 插件**：`org.jetbrains.intellij.platform`、Kotlin 插件等（数百 MB 以内）；
3. **IntelliJ Platform / Android Studio SDK**（约 1.5GB）：
   构建脚本中 `androidStudio("2025.2.3.9")` 指定的 Otter 3 Feature Drop (2025.2.3)；
   注意这里填的是**发行版本号**（4 段），不是 `AI-` build 号，也不是 platformBuild；
4. 第三方依赖：`org.json:json`（平台核心未内置，会随插件一起打包）。

## 更换目标 Android Studio 版本

编辑 `build.gradle.kts`：

```kotlin
dependencies {
    intellijPlatform {
        androidStudio("<发行版本号>")   // 例如 "2025.2.3.9"
    }
}
```

`发行版本号` 的查找方式：
- https://developer.android.com/studio/releases 对应版本页（4 段数字，如 `2025.2.3.9`）；
- 或 IDE 内 **Help → About** 的 “Version: 2025.2.3.9” 一行。
- 注意：`AI-` 开头的完整 build 号（如 `AI-252.28238.7...`）与
  platformBuild（`252.28238.7`）都**不能**直接填在这里，构建会报
  `Couldn't resolve AndroidStudio download URL`。

同时按需调整 `pluginConfiguration.ideaVersion` 的 `sinceBuild` / `untilBuild`
（纯数字平台号：242 = 2024.2，251 = 2025.1，252 = 2025.2，253 = 2025.3，261 = 2026.1）。

## 代理 / 镜像

Gradle 代理（`gradle.properties`）：

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7890
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7890
```

国内网络建议：
- Gradle 发行版：wrapper 已默认 JetBrains 镜像；
- 平台 SDK：走 JetBrains 仓库（`defaultRepositories()`），一般可直接访问。

## 常见问题

### 1. `Failed to load native library 'libnative-platform.so'`

Gradle 无法写入默认用户目录（沙箱/只读环境）。设置：

```bash
export GRADLE_USER_HOME=$PWD/.gradle-home   # 指到可写目录
export TMPDIR=$PWD/.tmp
```

### 2. `Unsupported class file major version` / Gradle 拒绝启动

JDK 版本不对（低于 17）。设置 `JAVA_HOME` 指向 JDK 17+。

### 3. 插件 SDK 下载失败 / 超时

- 重试即可（Gradle 会断点续传缓存中已下载的部分）；
- 或在 `gradle.properties` 配置代理（见上）。

### 4. 编译报错 `Unresolved reference: JBxxx / AllIcons`

确认依赖块中存在 `intellijPlatform { androidStudio(...) }`，
且未误删 `repositories { intellijPlatform { defaultRepositories() } }`。

### 5. 安装插件时提示版本不兼容

查看 IDE 的构建号（Help → About），确认落在 `sinceBuild..untilBuild` 区间内
（当前为 `242 .. 261.*`），否则调整 `build.gradle.kts` 后重新打包。

### 6. `buildSearchableOptions` 启动失败

本构建已默认关闭（`buildSearchableOptions = false`）。
若你打开它，需要图形环境（会真实启动沙箱 IDE）。

### 7. 测试报 `No roots for 'org.jetbrains:annotations:...'`

`~/.m2` 不可写（无头/沙箱环境）。构建脚本本应自动把测试 JVM 的
`user.home` 指到 `.tools/test-home/`；若你改过该逻辑或仍报错，手动确认：
`.tools/test-home/.m2/` 存在且测试 JVM 收到 `-Duser.home` 指向它
（可加 `-i` 查看测试任务参数）。

### 8. `Kotlin daemon` 报 `只读文件系统`（写 `~/.local/share/kotlin` 失败）

`gradle.properties` 已设置 `kotlin.compiler.execution.strategy=in-process`，
在 Gradle 进程内编译、不启动独立 Kotlin daemon。请勿删除该行（无头环境必需）。

### 9. 点击右侧 AiCode 图标显示 "Nothing to show"

工具窗注册成功（图标可见）但内容为空，且点击无反应——通常是
`createToolWindowContent` 工厂方法抛异常被平台吞掉。
打开 runIde 沙箱的 `idea.log`（`Help → Show Log in Files`），
搜插件异常即可定位。

已踩过的一个真实案例：`ChatPanel` 里
`JPanel(BoxLayout(this, BoxLayout.Y_AXIS))` ——
`BoxLayout` 的容器参数传成了 `this`（外层 ChatPanel）而不是实际挂载的
panel，Swing 在 `add()` 子组件时抛 `java.awt.AWTError: BoxLayout can't be shared`，
工厂中断、内容未注册 → "Nothing to show"。
修复：`BoxLayout` 的容器参数必须等于它实际 `setLayout` 的那个容器：

```kotlin
val topPanel = JPanel()
topPanel.layout = BoxLayout(topPanel, BoxLayout.Y_AXIS)
```

### 10. Test Connection / Auto Add Models 一直转圈、无成功或失败提示

这两个操作在后台线程发 HTTP 请求，结果通过 `invokeLater` 回到 EDT 弹提示。
为便于排查，**每一步都写了 `idea.log` 日志**（前缀 `AiCode:`）。复现后打开
runIde 沙箱或本机的 `idea.log`（**Help → Show Log in Files**），按下面顺序读：

| 日志 | 含义 |
| --- | --- |
| `AiCode: 测试连接 ... / Auto Add Models 开始 ...` | 操作已发起 |
| `AiCode: 发起 chat 请求 ... / 获取模型列表 ...` | HTTP 请求真正发出 |
| `AiCode: chat 响应 HTTP xxx（Nms）/ 模型列表响应 ...` | 收到响应（含耗时） |
| `AiCode: Test Connection 网络调用结束（Nms，成功=...）` | 网络调用结束 |
| `AiCode: 对话框已关闭，丢弃 ... 结果` | 你在结果回来前关掉了对话框 |
| `AiCode: ... 被用户取消` | 你再次点击按钮触发了取消 |

- 若日志停在“发起请求”之后没有“响应”行 → 网络层卡住。本插件对 LLM 请求
  **强制直连、不走系统代理**（见下条）；若你的 base_url 必须经代理才能访问，
  需要在系统层配置直连例外，或反馈以便加代理开关。
- 所有请求都有硬超时（建连 15s / 请求 30~60s），超时会以明确错误弹出，
  不会再无限“Testing…”。
- “Testing… (click to cancel)” / “Fetching… (click to cancel)” 状态下
  **再次点击按钮即取消**。

### 11. 为什么请求不走系统代理？

`LlmClient` 构造 `HttpClient` 时显式 `.proxy(ProxySelector.of(null))`（直连）。
原因：LLM 的 base_url 多为自部署 / 内网 / 直连公网 API，若被系统（OS/IDE）
代理拦截，内网地址往往经代理不可达，表现为请求长时间挂起。若你的端点
确实需要经代理访问，请在系统代理里对该地址配置直连例外。

### 12. idea.log 报 `SEVERE - Unable to serialize AiCodeProviderSettings state`

`Can't serialize state ... No argument provided for a required parameter:
instance parameter of fun ProviderManager.State.<init>()`——
`@State` 的载体类被声明成了 `ProviderManager` 的 **inner class**。
平台的 `KotlinAwareBeanBinding` 用 Kotlin 反射 `callBy` 调无参构造器实例化
State，但 inner 类的构造器带一个**隐式的外部实例参数**（`instance`），反射
拿不到 → 抛 `IllegalArgumentException`，配置无法落盘（重启后 Provider 全丢）。

- 规则：**`@State` 载体类必须是顶层类**（或至少是 `object`/顶层 `class`，
  不能 inner）。本插件已改为顶层 `ProviderManagerState`。
- 回归保护：`ProviderManagerTest.testStateXmlRoundTrip` 用平台 `XmlSerializer`
  做真实 serialize/deserialize 往返，一旦有人改回 inner class 会立刻失败。
