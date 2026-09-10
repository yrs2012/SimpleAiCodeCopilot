package com.aicodecopilot.plugin.chat

import java.util.UUID

/** 消息角色。 */
object Role {
    const val SYSTEM = "system"
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

/** 会话中保存的一条消息。 */
data class StoredMessage(
    val role: String,
    var content: String,
    val ts: Long
)

/**
 * 上下文条目类型：
 * FILE    —— 单个文件（请求时实时读取最新内容）
 * FOLDER  —— 文件夹（请求时递归扫描）
 * SNIPPET —— 编辑器中直接选中的代码片段（内容已内联保存，不随文件变化丢失）
 */
object ContextKind {
    const val FILE = "FILE"
    const val FOLDER = "FOLDER"
    const val SNIPPET = "SNIPPET"
}

/** 上下文条目来源：MANUAL（用户手动/@添加，随会话持久化）/ AUTO（自动跟随，内存态）。 */
object ContextSource {
    const val MANUAL = "manual"
    const val AUTO = "auto"
}

/**
 * 一条上下文条目。
 *
 * @param path    绝对路径；SNIPPET 时为空
 * @param content SNIPPET 的内联内容；其他类型为 null（请求时再读）
 */
data class StoredContextEntry(
    val id: String = UUID.randomUUID().toString(),
    val kind: String,
    val label: String,
    val path: String = "",
    val content: String? = null,
    val source: String = ContextSource.MANUAL
)

/** 会话元信息（用于列表展示）。 */
data class SessionMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val projectName: String
)

/**
 * 一个完整的对话会话（多轮消息 + 上下文 + 使用的 Provider）。
 *
 * @param persisted 是否已落盘（内存草稿为 false）
 */
class Session(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "New chat",
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var projectName: String = "",
    var providerId: String = "",
    /** 会话级选中的模型（空串 = 使用 provider 的默认/首个模型）。 */
    var modelId: String = "",
    val contextEntries: MutableList<StoredContextEntry> = mutableListOf(),
    val messages: MutableList<StoredMessage> = mutableListOf(),
    var persisted: Boolean = false
) {
    fun isDraft(): Boolean = !persisted && messages.isEmpty()
}
