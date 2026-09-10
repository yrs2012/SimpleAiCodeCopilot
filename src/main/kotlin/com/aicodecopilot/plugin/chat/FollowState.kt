package com.aicodecopilot.plugin.chat

/** 跟随目标（内存态，不持久化）。SNIPPET 时 content 为选中行文本。 */
data class FollowTarget(
    val kind: String,
    val path: String,
    val label: String,
    val startLine: Int = -1,
    val endLine: Int = -1,
    val content: String? = null
)

fun FollowTarget.toEntry(): StoredContextEntry = StoredContextEntry(
    kind = kind,
    label = label,
    path = path,
    content = content,
    source = ContextSource.AUTO
)

/** 自动跟随状态机：dismiss 只隐藏当前项，切到不同目标后自动清除。 */
class FollowState {
    var current: FollowTarget? = null
        private set
    private var dismissedKey: String? = null

    private fun keyOf(t: FollowTarget): String =
        "${t.kind}|${t.path}|${t.startLine}|${t.endLine}"

    /** 返回可见目标是否发生变化。 */
    fun update(t: FollowTarget?): Boolean {
        if (t == null) {
            val changed = current != null
            current = null
            return changed
        }
        if (keyOf(t) == dismissedKey) {
            val changed = current != null
            current = null
            return changed
        }
        dismissedKey = null
        val changed = current != t
        current = t
        return changed
    }

    fun dismiss() {
        current?.let { dismissedKey = keyOf(it) }
        current = null
    }
}
