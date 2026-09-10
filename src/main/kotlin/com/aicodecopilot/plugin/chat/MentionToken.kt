package com.aicodecopilot.plugin.chat

/** 光标处的 @ 查询：startOffset 为 '@' 位置，filter 为其后无空白的文本。 */
data class MentionQuery(val startOffset: Int, val filter: String)

fun extractMentionQuery(text: String, caretOffset: Int): MentionQuery? {
    val caret = caretOffset.coerceIn(0, text.length)
    var i = caret - 1
    while (i >= 0 && !text[i].isWhitespace()) i--
    val start = i + 1
    if (start >= caret || text[start] != '@') return null
    if (start > 0 && !text[start - 1].isWhitespace()) return null
    return MentionQuery(start, text.substring(start + 1, caret))
}

/** 删除 [q.startOffset, caret) 的 token，返回（新文本，新光标）。 */
fun removeMentionQuery(text: String, q: MentionQuery, caretOffset: Int): Pair<String, Int> {
    val caret = caretOffset.coerceIn(0, text.length)
    return text.removeRange(q.startOffset, caret) to q.startOffset
}
