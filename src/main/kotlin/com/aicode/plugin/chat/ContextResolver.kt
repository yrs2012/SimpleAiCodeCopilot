package com.aicode.plugin.chat

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.ArrayDeque

/**
 * 上下文解析：把会话中保存的上下文条目（文件 / 文件夹 / 代码片段）
 * 解析为可塞进 Prompt 的文本块。
 *
 * 限制（防止把整个仓库灌进上下文）：
 * - 单文件最多读取 [MAX_FILE_CHARS] 字符，超出截断
 * - 文件夹最多扫描 [MAX_FOLDER_FILES] 个文件、总字符 [MAX_TOTAL_CHARS]
 * - 自动跳过常见构建/依赖目录与二进制文件
 */
object ContextResolver {

    private val log = Logger.getInstance(ContextResolver::class.java)

    const val MAX_FILE_CHARS = 60_000
    const val MAX_FOLDER_FILES = 200
    const val MAX_TOTAL_CHARS = 400_000

    private val SKIP_DIRS = setOf(
        ".git", "build", "node_modules", ".gradle", ".idea", "out",
        ".cxx", "captures", "Pods", ".mvn", "dist", "target"
    )

    /** 将一条上下文条目解析为 Prompt 文本块；无法解析时返回 null。 */
    fun resolve(entry: StoredContextEntry, project: Project): String? = when (entry.kind) {
        ContextKind.FILE -> {
            val vf = findFile(entry.path)
                ?: return "--- ${entry.label} ---\n[File not found — it may have been moved or deleted]\n"
            fileBlock(vf)
        }
        ContextKind.FOLDER -> {
            val vf = findFile(entry.path)
                ?: return "--- ${entry.label}/ ---\n[Folder not found — it may have been moved or deleted]\n"
            folderBlock(vf)
        }
        ContextKind.SNIPPET -> {
            val content = entry.content ?: return null
            "--- ${entry.label} (selected code) ---\n$content\n"
        }
        else -> null
    }

    fun findFile(path: String): VirtualFile? {
        if (path.isBlank()) return null
        return runCatching { LocalFileSystem.getInstance().refreshAndFindFileByPath(path) }
            .getOrNull()
    }

    // ------------------------------------------------------------------
    // 文件 / 文件夹
    // ------------------------------------------------------------------

    private fun fileBlock(vf: VirtualFile): String {
        if (vf.fileType.isBinary()) {
            return "--- ${vf.name} ---\n[Binary file — content skipped]\n"
        }
        return try {
            val text = vf.contentsToByteArray().toString(StandardCharsets.UTF_8)
            if ('\u0000' in text) {
                return "--- ${vf.name} ---\n[Binary file — content skipped]\n"
            }
            val truncated = text.length > MAX_FILE_CHARS
            val content = text.take(MAX_FILE_CHARS)
            "--- ${vf.name} ---\n$content${if (truncated) "\n... [truncated, ${text.length} chars total]" else ""}\n"
        } catch (e: Exception) {
            log.warn("AiCode: 读取文件失败 ${vf.path}", e)
            "--- ${vf.name} ---\n[Read error: ${e.message}]\n"
        }
    }

    private fun folderBlock(root: VirtualFile): String {
        val sb = StringBuilder()
        sb.append("--- Folder: ${root.path} ---\n")
        val queue = ArrayDeque<VirtualFile>()
        queue.add(root)
        var count = 0
        var skipped = 0
        var truncated = false

        while (queue.isNotEmpty()) {
            if (count >= MAX_FOLDER_FILES || sb.length >= MAX_TOTAL_CHARS) {
                truncated = true
                break
            }
            val vf = queue.removeFirst()
            if (vf.isDirectory) {
                val children = vf.children ?: continue
                for (child in children) {
                    if (child.name in SKIP_DIRS || child.name.startsWith(".")) {
                        skipped++
                        continue
                    }
                    queue.add(child)
                }
            } else {
                if (vf.fileType.isBinary() || vf.length > MAX_FILE_CHARS.toLong()) {
                    skipped++
                    continue
                }
                val rel = runCatching {
                    Paths.get(root.path).relativize(Paths.get(vf.path)).toString()
                }.getOrDefault(vf.name)
                val text = runCatching { vf.contentsToByteArray().toString(StandardCharsets.UTF_8) }
                    .getOrNull()
                if (text == null || '\u0000' in text) {
                    skipped++
                    continue
                }
                count++
                sb.append("\n## ").append(rel).append("\n").append(text.take(MAX_FILE_CHARS)).append("\n")
            }
        }
        sb.append("\n[scanned $count files")
        if (truncated) sb.append(", limit reached (${MAX_FOLDER_FILES} files / ${MAX_TOTAL_CHARS} chars)")
        if (skipped > 0) sb.append(", $skipped skipped (binary/hidden/build dirs)")
        sb.append("]")
        return sb.toString()
    }
}
