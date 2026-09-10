package com.aicodecopilot.plugin.chat

import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID

/**
 * SessionRepository 持久化测试（需要平台测试环境：PathManager / 沙箱系统目录）。
 */
class SessionRepositoryTest : LightCodeInsightFixtureTestCase() {

    private fun uniqueId(): String = "test-" + UUID.randomUUID().toString().take(8)

    private fun buildSession(id: String, updatedAt: Long, modelId: String = ""): Session {
        val s = Session(id = id, title = "Title-$id", projectName = "proj", providerId = "p1")
        s.updatedAt = updatedAt
        s.persisted = true
        s.modelId = modelId
        s.messages.add(StoredMessage(Role.USER, "hi", 1L))
        s.messages.add(StoredMessage(Role.ASSISTANT, "hello!", 2L))
        s.contextEntries.add(
            StoredContextEntry(id = "c1", kind = ContextKind.SNIPPET, label = "snippet", content = "val x = 1")
        )
        s.contextEntries.add(
            StoredContextEntry(id = "c2", kind = ContextKind.FILE, label = "a.kt", path = "/tmp/a.kt")
        )
        return s
    }

    fun testSaveLoadRoundTrip() {
        val id = uniqueId()
        SessionRepository.save(buildSession(id, 1000L, modelId = "gpt-4o"))

        val loaded = SessionRepository.load(id)
        assertNotNull(loaded)
        assertEquals("Title-$id", loaded!!.title)
        assertEquals("proj", loaded.projectName)
        assertEquals("p1", loaded.providerId)
        assertEquals("gpt-4o", loaded.modelId)
        assertTrue(loaded.persisted)

        assertEquals(2, loaded.messages.size)
        assertEquals(Role.USER, loaded.messages[0].role)
        assertEquals("hi", loaded.messages[0].content)
        assertEquals(Role.ASSISTANT, loaded.messages[1].role)
        assertEquals("hello!", loaded.messages[1].content)

        assertEquals(2, loaded.contextEntries.size)
        assertEquals(ContextKind.SNIPPET, loaded.contextEntries[0].kind)
        assertEquals("val x = 1", loaded.contextEntries[0].content)
        assertEquals(ContextKind.FILE, loaded.contextEntries[1].kind)
        assertEquals("/tmp/a.kt", loaded.contextEntries[1].path)
        assertNull(loaded.contextEntries[1].content)
    }

    fun testListSortedByUpdatedAtAndDelete() {
        val idOlder = uniqueId()
        val idNewer = uniqueId()
        SessionRepository.save(buildSession(idOlder, 100L))
        SessionRepository.save(buildSession(idNewer, 200L))

        try {
            val list = SessionRepository.list()
            assertTrue(list.any { it.id == idOlder })
            assertTrue(list.any { it.id == idNewer })
            // 按最近活跃倒序
            assertTrue(list.indexOfFirst { it.id == idNewer } < list.indexOfFirst { it.id == idOlder })

            SessionRepository.delete(idOlder)
            assertFalse(SessionRepository.list().any { it.id == idOlder })
            assertTrue(SessionRepository.list().any { it.id == idNewer })
        } finally {
            SessionRepository.delete(idOlder)
            SessionRepository.delete(idNewer)
        }
    }

    fun testListSkipsCorruptFiles() {
        val dir = SessionRepository.fileOf("probe").parent
        val corrupt = dir.resolve("corrupt-" + uniqueId() + ".json")
        Files.write(corrupt, "{ this is not valid json !!".toByteArray(StandardCharsets.UTF_8))
        try {
            // 不应抛异常，损坏文件被静默跳过
            SessionRepository.list()
        } finally {
            Files.deleteIfExists(corrupt)
        }
    }

    fun testLoadMissingReturnsNull() {
        assertNull(SessionRepository.load("definitely-not-exists-" + uniqueId()))
    }

    fun testSaveIgnoredForDraft() {
        val s = Session(title = "draft", projectName = "p")
        s.messages.add(StoredMessage(Role.USER, "x", 1L))
        s.persisted = false
        SessionRepository.save(s)
        assertNull(SessionRepository.load(s.id))
    }

    fun testEmptyModelIdNotPersisted() {
        // 空 modelId 不写入 JSON（保持旧文件格式向后兼容），加载后回落到 ""
        val id = uniqueId()
        val s = buildSession(id, 100L) // modelId 默认 ""
        SessionRepository.save(s)
        val file = SessionRepository.fileOf(id)
        val json = String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        assertFalse("空 modelId 不应写入 JSON，实际：$json", json.contains("\"modelId\""))
        assertEquals("", SessionRepository.load(id)!!.modelId)
    }

    fun testSourceRoundTrip() {
        val id = uniqueId()
        val s = buildSession(id, 1000L)
        s.contextEntries.add(
            StoredContextEntry(id = "c3", kind = ContextKind.FILE, label = "b.kt",
                path = "/tmp/b.kt", source = ContextSource.AUTO)
        )
        SessionRepository.save(s)

        val loaded = SessionRepository.load(id)
        assertNotNull(loaded)
        // c1/c2 为老数据形状（默认 manual）
        assertEquals(ContextSource.MANUAL, loaded!!.contextEntries[0].source)
        assertEquals(ContextSource.MANUAL, loaded.contextEntries[1].source)
        assertEquals(ContextSource.AUTO, loaded.contextEntries[2].source)
    }
}
