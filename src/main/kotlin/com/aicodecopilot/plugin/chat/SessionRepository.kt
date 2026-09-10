package com.aicodecopilot.plugin.chat

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.PathManager
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * 会话持久化仓库。
 *
 * 每个会话保存为一个 JSON 文件：
 *   <system>/aicodecopilot/sessions/<sessionId>.json
 *
 * 会话与应用（而非某个项目）绑定，但记录 projectName 便于展示；
 * 上下文中的文件路径在恢复时按绝对路径重新解析，文件不存在则标注。
 */
object SessionRepository {

    private val log = Logger.getInstance(SessionRepository::class.java)

    private val sessionsDir: Path by lazy {
        val dir = Paths.get(PathManager.getSystemPath(), "aicodecopilot", "sessions")
        runCatching { Files.createDirectories(dir) }.onFailure { log.warn("AiCodeCopilot: 无法创建会话目录 $dir", it) }
        migrateLegacySessions(dir)
        dir
    }

    /**
     * 改名迁移：旧版会话在 <system>/aicode/sessions。新目录为空且旧目录有文件时
     * 一次性拷贝过来（只拷 JSON，老文件保留不动，幂等）。
     */
    private fun migrateLegacySessions(dir: Path) {
        runCatching {
            val legacy = Paths.get(PathManager.getSystemPath(), "aicode", "sessions")
            if (!Files.isDirectory(legacy) || legacy == dir) return@runCatching
            val hasNew = Files.list(dir).use { s -> s.anyMatch { it.toString().endsWith(".json") } }
            if (hasNew) return@runCatching
            Files.list(legacy).use { s ->
                s.filter { it.toString().endsWith(".json") }.forEach { src ->
                    val dst = dir.resolve(src.fileName.toString())
                    if (!Files.exists(dst)) Files.copy(src, dst)
                }
            }
            log.warn("AiCodeCopilot: 已从旧目录迁移会话 ${legacy}")
        }.onFailure { log.warn("AiCodeCopilot: 会话迁移失败", it) }
    }

    fun fileOf(id: String): Path = sessionsDir.resolve("$id.json")

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    fun list(): List<SessionMeta> {
        if (!Files.isDirectory(sessionsDir)) return emptyList()
        val files: List<Path> = try {
            Files.list(sessionsDir).use { stream ->
                stream.filter { it.toString().endsWith(".json") }.toList()
            }
        } catch (e: Exception) {
            log.warn("AiCodeCopilot: 读取会话列表失败", e)
            return emptyList()
        }
        val metas = ArrayList<SessionMeta>(files.size)
        for (p in files) {
            val meta = loadMeta(p)
            if (meta != null) metas.add(meta)
        }
        metas.sortByDescending { it.updatedAt }
        return metas
    }

    private fun loadMeta(p: Path): SessionMeta? = try {
        val obj = JSONObject(Files.readString(p, StandardCharsets.UTF_8))
        SessionMeta(
            id = obj.optString("id"),
            title = obj.optString("title", "New chat"),
            createdAt = obj.optLong("createdAt"),
            updatedAt = obj.optLong("updatedAt"),
            projectName = obj.optString("projectName", "")
        )
    } catch (e: Exception) {
        log.warn("AiCodeCopilot: 会话元数据解析失败 $p", e)
        null
    }

    // ------------------------------------------------------------------
    // 读写
    // ------------------------------------------------------------------

    fun load(id: String): Session? {
        val file = fileOf(id)
        if (!Files.isRegularFile(file)) return null
        return try {
            val obj = JSONObject(Files.readString(file, StandardCharsets.UTF_8))

            val messages = mutableListOf<StoredMessage>()
            obj.optJSONArray("messages")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    messages.add(
                        StoredMessage(
                            role = m.optString("role", Role.USER),
                            content = m.optString("content"),
                            ts = m.optLong("ts")
                        )
                    )
                }
            }

            val entries = mutableListOf<StoredContextEntry>()
            obj.optJSONArray("context")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    entries.add(
                        StoredContextEntry(
                            id = c.optString("id"),
                            kind = c.optString("kind", ContextKind.FILE),
                            label = c.optString("label"),
                            path = c.optString("path"),
                            content = c.optString("content", null),
                            source = c.optString("source", ContextSource.MANUAL)
                        )
                    )
                }
            }

            Session(
                id = obj.optString("id", id),
                title = obj.optString("title", "New chat"),
                createdAt = obj.optLong("createdAt"),
                updatedAt = obj.optLong("updatedAt"),
                projectName = obj.optString("projectName", ""),
                providerId = obj.optString("providerId", ""),
                modelId = obj.optString("modelId", ""),
                contextEntries = entries,
                messages = messages,
                persisted = true
            )
        } catch (e: Exception) {
            log.warn("AiCodeCopilot: 会话加载失败 $id", e)
            null
        }
    }

    fun save(session: Session) {
        if (!session.persisted) return
        try {
            val obj = JSONObject()
            obj.put("id", session.id)
            obj.put("title", session.title)
            obj.put("createdAt", session.createdAt)
            obj.put("updatedAt", session.updatedAt)
            obj.put("projectName", session.projectName)
            obj.put("providerId", session.providerId)
            if (session.modelId.isNotBlank()) obj.put("modelId", session.modelId)

            val msgs = JSONArray()
            session.messages.forEach { m ->
                msgs.put(JSONObject().put("role", m.role).put("content", m.content).put("ts", m.ts))
            }
            obj.put("messages", msgs)

            val ctx = JSONArray()
            session.contextEntries.forEach { c ->
                val o = JSONObject()
                o.put("id", c.id)
                o.put("kind", c.kind)
                o.put("label", c.label)
                o.put("path", c.path)
                o.put("source", c.source)
                if (c.content != null) o.put("content", c.content)
                ctx.put(o)
            }
            obj.put("context", ctx)

            // 先写临时文件再原子替换，避免半截文件
            val tmp = sessionsDir.resolve("${session.id}.tmp.json")
            Files.write(tmp, obj.toString(2).toByteArray(StandardCharsets.UTF_8))
            Files.move(tmp, fileOf(session.id), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            log.warn("AiCodeCopilot: 会话保存失败 ${session.id}", e)
        }
    }

    fun delete(id: String) {
        runCatching { Files.deleteIfExists(fileOf(id)) }
            .onFailure { log.warn("AiCodeCopilot: 会话删除失败 $id", it) }
    }
}
