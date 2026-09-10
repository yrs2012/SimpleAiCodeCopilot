package com.aicodecopilot.plugin.chat

import org.junit.Assert.*
import org.junit.Test

class FollowStateTest {
    private fun fileTarget() = FollowTarget(ContextKind.FILE, "/p/a.kt", "a.kt")
    private fun otherTarget() = FollowTarget(ContextKind.FILE, "/p/b.kt", "b.kt")

    @Test fun update_setsCurrentAndReportsChanged() {
        val s = FollowState()
        assertTrue(s.update(fileTarget()))
        assertEquals("/p/a.kt", s.current?.path)
    }
    @Test fun update_sameTarget_reportsUnchanged() {
        val s = FollowState()
        s.update(fileTarget())
        assertFalse(s.update(fileTarget()))
    }
    @Test fun dismiss_hidesCurrent() {
        val s = FollowState()
        s.update(fileTarget())
        s.dismiss()
        assertNull(s.current)
    }
    @Test fun dismiss_sameTargetStaysHidden() {
        val s = FollowState()
        s.update(fileTarget())
        s.dismiss()
        assertFalse(s.update(fileTarget()))
        assertNull(s.current)
    }
    @Test fun dismiss_differentTargetShowsAndClearsDismissed() {
        val s = FollowState()
        s.update(fileTarget())
        s.dismiss()
        assertTrue(s.update(otherTarget()))
        assertEquals("/p/b.kt", s.current?.path)
        // 旧 dismissed 已清除：切回来可再次显示
        assertTrue(s.update(fileTarget()))
    }
    @Test fun toEntry_marksAutoSource() {
        val e = fileTarget().toEntry()
        assertEquals(ContextSource.AUTO, e.source)
        assertEquals(ContextKind.FILE, e.kind)
    }
}
