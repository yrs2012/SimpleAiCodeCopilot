package com.aicodecopilot.plugin.chat

import org.junit.Assert.*
import org.junit.Test

class MentionTokenTest {
    @Test fun extract_simpleFilter() {
        val q = extractMentionQuery("hello @src/ma", 13)
        assertNotNull(q)
        assertEquals(6, q!!.startOffset)
        assertEquals("src/ma", q.filter)
    }
    @Test fun extract_atStart_emptyFilter() {
        val q = extractMentionQuery("@", 1)
        assertNotNull(q)
        assertEquals("", q!!.filter)
    }
    @Test fun extract_emailLike_returnsNull() {
        assertNull(extractMentionQuery("a@b", 3))
    }
    @Test fun extract_noAt_returnsNull() {
        assertNull(extractMentionQuery("hello", 5))
    }
    @Test fun remove_deletesTokenAndMovesCaret() {
        val q = extractMentionQuery("see @src/a.kt end", 13)!!
        val (text, caret) = removeMentionQuery("see @src/a.kt end", q, 13)
        assertEquals("see  end", text)
        assertEquals(4, caret)
    }
}
