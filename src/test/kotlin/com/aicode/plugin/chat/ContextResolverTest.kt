package com.aicode.plugin.chat

import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase
import java.io.File
import java.nio.file.Files

/**
 * ContextResolver 测试（需要平台测试环境：VFS / LocalFileSystem）。
 *
 * 注意：fixture 的临时目录（findFileInTempDir）位于内存版 TempFileSystem，
 * 而生产代码通过 LocalFileSystem 解析用户选择的磁盘路径。
 * 因此这里直接在真实磁盘临时目录里建文件，与真实使用路径一致。
 */
class ContextResolverTest : LightCodeInsightFixtureTestCase() {

    private lateinit var workDir: File

    override fun setUp() {
        super.setUp()
        workDir = Files.createTempDirectory("aicode-ctx-test-").toFile()
    }

    override fun tearDown() {
        try {
            workDir.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun write(rel: String, content: String): String {
        val f = File(workDir, rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f.absolutePath
    }

    fun testFileContext_includesContent() {
        val path = write("hello.kt", "fun main() = println(1)")
        val entry = StoredContextEntry(kind = ContextKind.FILE, label = "hello.kt", path = path)
        val block = ContextResolver.resolve(entry, project)
        assertNotNull(block)
        assertTrue(block!!.contains("--- hello.kt ---"))
        assertTrue(block.contains("fun main() = println(1)"))
    }

    fun testBinaryFile_skipped() {
        val path = write("bin.dat", "ab\u0000cd")
        val entry = StoredContextEntry(kind = ContextKind.FILE, label = "bin.dat", path = path)
        val block = ContextResolver.resolve(entry, project)
        assertTrue(block!!.contains("Binary file"))
    }

    fun testLargeFile_truncated() {
        val big = "x".repeat(70_000)
        val path = write("big.txt", big)
        val entry = StoredContextEntry(kind = ContextKind.FILE, label = "big.txt", path = path)
        val block = ContextResolver.resolve(entry, project)!!
        assertTrue(block.contains("truncated"))
        assertTrue("截断后应小于原长", block.length < big.length)
    }

    fun testFolderContext_scansRecursivelyAndSkipsHidden() {
        write("src/a.kt", "val a = 1")
        write("src/b.java", "int b;")
        write("src/sub/c.py", "c = 3")
        write("src/.git/HEAD", "ref: refs/heads/main")

        val entry = StoredContextEntry(
            kind = ContextKind.FOLDER, label = "src", path = File(workDir, "src").absolutePath
        )
        val block = ContextResolver.resolve(entry, project)!!

        assertTrue(block.contains("## a.kt"))
        assertTrue(block.contains("## b.java"))
        assertTrue(block.contains("## sub/c.py"))
        assertFalse("隐藏/构建目录应被跳过", block.contains("HEAD"))
        assertTrue(block.contains("scanned 3 files"))
    }

    fun testSnippetContext_inlinesContent() {
        val entry = StoredContextEntry(kind = ContextKind.SNIPPET, label = "sel", content = "x()")
        val block = ContextResolver.resolve(entry, project)
        assertEquals("--- sel (selected code) ---\nx()\n", block)
    }

    fun testMissingFile_markedNotFound() {
        val entry = StoredContextEntry(
            kind = ContextKind.FILE, label = "gone.kt", path = "/nonexistent/aicode-gone.kt"
        )
        val block = ContextResolver.resolve(entry, project)
        assertTrue(block!!.contains("File not found"))
    }

    fun testFolderNotFound_markedNotFound() {
        val entry = StoredContextEntry(
            kind = ContextKind.FOLDER, label = "gone-dir", path = "/nonexistent/aicode-gone-dir"
        )
        val block = ContextResolver.resolve(entry, project)
        assertTrue(block!!.contains("Folder not found"))
    }
}
