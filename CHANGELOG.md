# Changelog

所有重要变更都记录在此文件中。
格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0.0] - 2026-09-04

### Added
- 右侧 **AiCode** 工具窗口聊天面板（Swing + HTML 渲染，代码块高亮底色）
- 自定义 LLM Provider：
  - 支持任意 OpenAI 兼容 Chat Completions 接口
  - 预置 OpenAI / DeepSeek / Ollama 三个模板
  - 支持 Base URL、模型、Temperature、Max Tokens、额外请求头
  - Settings → Tools → AiCode 管理界面，内置 Test Connection 连通性测试
- 新建对话（New Chat）、多轮对话（自动携带最近 40 条历史）
- 流式（SSE）输出，可随时 Stop 中断（保留已生成内容）
- 历史会话持久化（`<system>/aicode/sessions/*.json`）：
  下拉切换、继续任意历史 Session、删除会话
- 文件 / 文件夹上下文：
  - 工具窗口 + File / + Folder 按钮（项目内文件选择）
  - `/add <绝对路径>` 斜杠命令（支持项目外路径）
  - 编辑器右键 / Tools 菜单 **Send to AiCode**（当前文件 + 选中代码片段）
  - 上下文 chips 可单独移除 / 一键清空
  - 自动跳过二进制与构建目录；单文件 60K 字符、总量 400K 字符保护上限
- `/help` 帮助命令
- 兼容 Android Studio 2024.2 (Hedgehog) 至 2026.1 (Quail)，同时兼容 IntelliJ IDEA
