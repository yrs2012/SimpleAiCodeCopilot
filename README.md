# AiCode — Android Studio AI 编码助手插件

AiCode 是一个内嵌在 **Android Studio**（也兼容 IntelliJ IDEA）中的 AI 编码助手插件。
它提供一个右侧聊天工具窗口，让你在不离开 IDE 的情况下与任意大语言模型对话、
并让它直接“阅读”你的项目文件与文件夹。

## 功能特性

| 功能 | 说明 |
| --- | --- |
| 🧩 自定义 Provider | 支持任意 **OpenAI 兼容** 的 Chat Completions 接口：OpenAI、DeepSeek、OpenRouter、Moonshot、通义千问、本地 Ollama / vLLM 等。可添加多个 Provider 并随时切换 |
| ➕ 新建对话 | 一键开启新 Session |
| 💬 多轮对话 | 自动携带历史上下文（默认最近 40 条消息），支持流式（SSE）输出，可随时**停止** |
| 🕘 历史 Session | 会话自动持久化到本地，下拉列表可切换、**继续任意历史会话**、删除会话 |
| 📂 阅读文件 / 文件夹 | 将指定文件或整个文件夹加入上下文，AI 回答时会阅读其内容（自动跳过二进制/构建目录，单文件 60KB、总量 400KB 保护上限） |
| 🖱 Send to AiCode | 编辑器右键菜单 / Tools 菜单：把当前文件或选中代码一键发给助手 |
| ⌨ 斜杠命令 | `/add <路径>`、`/clear`、`/help` |

## 环境要求

| 组件 | 要求 |
| --- | --- |
| Android Studio | Hedgehog (2024.2) 及以上，最高兼容到 Quail (2026.1) |
| IntelliJ IDEA | 2024.2+（插件仅依赖平台核心模块） |
| JDK（构建时需要） | JDK 17+（Android Studio 自带 JBR 17/21 即可） |
| Gradle | 无需单独安装（项目自带 Gradle Wrapper） |

## 快速开始

### 1. 构建插件

```bash
# 在项目根目录
./gradlew buildPlugin
```

首次构建会自动下载 IntelliJ Platform SDK 与依赖（约 1GB，请耐心等待）。
产物位于：

```
build/distributions/AiCode-1.0.0.zip
```

> 在 Windows 上使用 `gradlew.bat buildPlugin`。

### 2. 安装插件

1. Android Studio → **Settings**（macOS 为 **Preferences**）→ **Plugins**
2. 右上角齿轮 ⚙ → **Install Plugin from Disk…**
3. 选择 `build/distributions/AiCode-1.0.0.zip`
4. 重启 IDE

### 3. 配置 Provider

1. **Settings → Tools → AiCode**
2. 点击 **Add Provider…**（或编辑预置的 OpenAI / DeepSeek）
3. 填写：
   - **Name**：任意名称
   - **Base URL**：接口地址（见下表）
   - **Model**：模型名
   - **API Key**：密钥（本地 Ollama 可留空）
4. 点击 **Test Connection** 验证连通性
5. 用 **Set as Default** 设为默认

常用 Base URL 示例：

| Provider | Base URL | Model 示例 |
| --- | --- | --- |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` |
| OpenRouter | `https://openrouter.ai/api/v1` | `deepseek/deepseek-chat` |
| Moonshot (Kimi) | `https://api.moonshot.cn/v1` | `moonshot-v1-8k` |
| 通义千问 DashScope | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` |
| Ollama（本地） | `http://localhost:11434/v1` | `llama3.2`、`qwen2.5:7b` |
| vLLM / LM Studio（本地） | `http://localhost:8000/v1` | 自部署模型名 |

### 4. 开始对话

1. 打开右侧 **AiCode** 工具窗口（View → Tool Windows → AiCode）
2. 输入问题，**Enter** 发送（**Shift+Enter** 换行）
3. 回复以流式输出；过程中点击 **Stop** 可中断（已生成的内容保留）

## 使用说明

### 新建 / 切换 / 删除会话

- 工具窗口顶部的下拉框列出所有历史会话（按最近活跃排序）
- **＋** 按钮：新建对话
- **🗑** 按钮：删除当前会话（仅对已保存的会话有效）
- 会话存储位置：`<IDE 系统目录>/aicode/sessions/<id>.json`

### 添加文件 / 文件夹上下文

- **+ File**：多选项目内的文件
- **+ Folder**：选择一个文件夹（递归扫描，自动跳过 `.git`、`build`、`node_modules` 等）
- **/add `/abs/path/to/file`**：用绝对路径直接添加（项目外的文件也可以）
- 添加后以“标签”形式显示在输入区上方，点 **×** 移除，**Clear** 清空
- 上下文随会话保存，切换回去后仍然存在；文件被移动/删除时会标注 “not found”

> 上下文限制：单文件最多 60,000 字符；文件夹最多 200 个文件、总计 400,000 字符。

### Send to AiCode

- 在编辑器中**右键 → Send to AiCode**：把当前文件加入上下文
- 若事先**选中了代码**：选区会以 `文件名 (L起-L止)` 片段形式加入上下文（同时加入整个文件）
- 也可以从 **Tools → Send to AiCode** 菜单触发

### 斜杠命令

| 命令 | 作用 |
| --- | --- |
| `/add <路径>` | 添加文件或文件夹为上下文 |
| `/clear` | 清空当前上下文并开启新对话 |
| `/help` | 查看帮助 |

## 项目结构

```
AiCode/
├── build.gradle.kts                 # Gradle 构建（IntelliJ Platform Gradle Plugin 2.x）
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/                  # Gradle Wrapper
├── src/main/
│   ├── resources/META-INF/plugin.xml
│   └── kotlin/com/aicode/plugin/
│       ├── AiCodeToolWindowFactory.kt       # 右侧工具窗口
│       ├── action/
│       │   └── SendToAiCodeAction.kt        # 右键“Send to AiCode”
│       ├── chat/
│       │   ├── ChatPanel.kt                 # 聊天 UI（Swing + HTML 渲染）
│       │   ├── ChatController.kt            # 对话逻辑 / 流式请求 / 上下文
│       │   ├── Session.kt                   # 会话与消息数据模型
│       │   ├── SessionRepository.kt         # 会话 JSON 持久化
│       │   ├── ContextResolver.kt           # 文件/文件夹 → Prompt 文本块
│       │   └── AiCodeUiService.kt           # 面板宿主（Action 与面板的桥梁）
│       └── provider/
│           ├── ProviderConfig.kt            # Provider 配置模型
│           ├── ProviderManager.kt           # Provider 持久化（应用级服务）
│           ├── LlmClient.kt                 # OpenAI 兼容 HTTP 客户端（SSE 流式）
│           ├── ProviderConfigDialog.kt      # 添加/编辑 Provider 对话框
│           └── AiCodeSettingsConfigurable.kt# Settings → Tools → AiCode
├── src/test/kotlin/com/aicode/plugin/       # 单元测试（./gradlew test）
│   ├── provider/  LlmClientTest（假 LLM 服务）/ ProviderManagerTest / ProviderConfigTest
│   └── chat/      SessionRepositoryTest / ContextResolverTest
└── docs/
    ├── ARCHITECTURE.md                  # 架构设计说明
    └── BUILD.md                         # 构建与故障排查
```

## 常见问题

**Q: 构建时下载 SDK 很慢/失败？**
A: 首次构建需从 JetBrains 仓库下载约 1GB 的平台 SDK。可在 `gradle.properties`
中配置代理：`systemProp.http.proxyHost=... / systemProp.http.proxyPort=...`。

**Q: 插件装不上，提示版本不兼容？**
A: 插件兼容构建号 `242`（2024.2）至 `261.*`（2026.1）。
查看版本：Help → About → 第三行 `AI-xxx.xxxxx`。

**Q: 请求报 HTTP 401 / 404？**
A: 401 通常是 API Key 错误；404 通常是 Base URL 或 Model 名称不对。
先在设置页用 **Test Connection** 定位问题。

**Q: Ollama 本地模型无法连接？**
A: 确认 Ollama 已启动（`ollama serve`），Base URL 为
`http://localhost:11434/v1`，Model 与 `ollama list` 中的名称一致，API Key 留空。

**Q: 对话记录保存在哪里？**
A: `<IDE 系统目录>/aicode/sessions/`（Linux 下通常是
`~/.cache/AndroidStudio<版本>/aicode/sessions/`）。

## 构建与开发

详见 [docs/BUILD.md](docs/BUILD.md)。架构设计详见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 许可证

MIT
