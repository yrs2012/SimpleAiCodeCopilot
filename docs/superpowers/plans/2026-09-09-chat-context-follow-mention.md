# 上下文改造（自动跟随 + @ 引用）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 去掉 `+File`/`+Folder` 按钮，主界面改为输入框上方 chips 行（`+` + 文件/行号 chips），编辑器实时跟随 + `@` 文件选择器，发送时合并解析。

**Architecture:** 纯逻辑下沉为可单测小单元（`MentionToken`、`FollowState`），IDE 监听与弹窗为薄适配层（`EditorFollowTracker`、`MentionPopup`），`ChatPanel` 只做组装，`ChatController` 持有一个 transient 跟随项参与发送组装。

**Tech Stack:** Kotlin, Swing (GridBag/FlowLayout), IntelliJ Platform (FileEditorManagerListener, CaretListener, JBPopupFactory, Alarm), JUnit4, org.json（既有持久化）。

**测试命令（全计划通用）：** 构建与单测均需先设 JDK：
`JAVA_HOME="$PWD/.tools/android-studio/jbr" ./gradlew test --tests "<TestClass>"`，纯逻辑测试同时可用该命令运行。

---

## File Structure

| 文件 | 职责 |
|---|---|
| Create `src/main/kotlin/com/aicode/plugin/chat/MentionToken.kt` | 纯函数：`@` token 提取与删除 |
| Create `src/test/kotlin/com/aicode/plugin/chat/MentionTokenTest.kt` | 上述单测（纯 JUnit，无需 IDE） |
| Create `src/main/kotlin/com/aicode/plugin/chat/FollowState.kt` | 纯逻辑：跟随目标 + dismissed 状态机 + 转 `StoredContextEntry` |
| Create `src/test/kotlin/com/aicode/plugin/chat/FollowStateTest.kt` | 上述单测（纯 JUnit） |
| Create `src/main/kotlin/com/aicode/plugin/chat/EditorFollowTracker.kt` | 监听编辑器切换/选中，防抖后驱动 `FollowState` |
| Create `src/main/kotlin/com/aicode/plugin/chat/MentionPopup.kt` | `@` 文件选择弹窗（项目根起，过滤+目录导航+关联目录） |
| Modify `src/main/kotlin/com/aicode/plugin/chat/Session.kt` | `StoredContextEntry` 加 `source` 字段 + `ContextSource` 常量 |
| Modify `src/main/kotlin/com/aicode/plugin/chat/SessionRepository.kt` | `source` 持久化（`optString` 默认 manual，老数据兼容） |
| Modify `src/test/kotlin/com/aicode/plugin/chat/SessionRepositoryTest.kt` | `source` 回环测试 |
| Modify `src/main/kotlin/com/aicode/plugin/chat/ChatController.kt` | transient `followEntry` + 发送时合并 + `clearContext` 语义不变（只清会话内） |
| Modify `src/main/kotlin/com/aicode/plugin/chat/ChatPanel.kt` | chips 行、`@` 接线、tracker 接线、删旧按钮/旧 chips 区、文案更新 |
| Modify `src/main/kotlin/com/aicode/plugin/action/SendToAiCodeAction.kt` | 新语义：只确保 snippet chip + 聚焦聊天窗口 |
| Modify `src/test/kotlin/com/aicode/plugin/chat/ChatPanelUiConstructionTest.kt` | 构造断言：`+` 在、旧按钮不在、chips 行在 |

---

### Task 1: MentionToken 纯函数

**Files:**
- Create: `src/main/kotlin/com/aicode/plugin/chat/MentionToken.kt`
- Test: `src/test/kotlin/com/aicode/plugin/chat/MentionTokenTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aicode.plugin.chat

import org.junit.Assert.*
import org.junit.Test

class MentionTokenTest {
    @Test fun extract_simpleFilter() {
        val q = extractMentionQuery("hello @src/ma", 12)
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
        val q = extractMentionQuery("see @src/a.kt end", 10)!!
        val (text, caret) = removeMentionQuery("see @src/a.kt end", q, 10)
        assertEquals("see  end", text)
        assertEquals(4, caret)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$PWD/.tools/android-studio/jbr" ./gradlew test --tests "com.aicode.plugin.chat.MentionTokenTest"`
Expected: FAIL, compilation error (unresolved reference `extractMentionQuery`).

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.aicode.plugin.chat

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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="$PWD/.tools/android-studio/jbr" ./gradlew test --tests "com.aicode.plugin.chat.MentionTokenTest"`
Expected: BUILD SUCCESSFUL, 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/aicode/plugin/chat/MentionToken.kt src/test/kotlin/com/aicode/plugin/chat/MentionTokenTest.kt
git commit -m "feat: add MentionToken @-query extract/remove"
```

---

### Task 2: FollowState 纯逻辑

**Files:**
- Create: `src/main/kotlin/com/aicode/plugin/chat/FollowState.kt`
- Test: `src/test/kotlin/com/aicode/plugin/chat/FollowStateTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aicode.plugin.chat

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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$PWD/.tools/android-studio/jbr" ./gradlew test --tests "com.aicode.plugin.chat.FollowStateTest"`
Expected: FAIL, unresolved reference `FollowState`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.aicode.plugin.chat

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
```

（依赖 Task 3 的 `ContextSource` 与 `source` 字段；若先做本任务会编译失败——按任务顺序先做 Task 3，或两任务同批提交。顺序：Task 3 → Task 2 的 Step 3。）

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="$PWD/.tools/android-studio/jbr" ./gradlew test --tests "com.aicode.plugin.chat.FollowStateTest"`
Expected: BUILD SUCCESSFUL, 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/aicode/plugin/chat/FollowState.kt src/test/kotlin/com/aicode/plugin/chat/FollowStateTest.kt
git commit -m "feat: add FollowState follow/dismiss state machine"
```

---

### Task 3: ContextSource + 持久化

**Files:**
- Modify: `src/main/kotlin/com/aicode/plugin/chat/Session.kt` (StoredContextEntry 加字段 + 新增 ContextSource)
- Modify: `src/main/kotlin/com/aicode/plugin/chat/SessionRepository.kt` (save 加 put，load 加 optString 默认)
- Test: `src/test/kotlin/com/aicode/plugin/chat/SessionRepositoryTest.kt` (加回环测试方法)

- [ ] **Step 1: 先加测试（回环 source）**

在 `SessionRepositoryTest` 末尾（类内）追加：

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="$PWD/.tools/android-studio/jbr" ./gradlew test --tests "com.aicode.plugin.chat.SessionRepositoryTest"`
Expected: FAIL，编译错误（`source` 参数不存在）。

- [ ] **Step 3: 最小实现（三处）**

`Session.kt` 在 `ContextKind` 后加：

```kotlin
/** 上下文条目来源：MANUAL（用户手动/@添加，随会话持久化）/ AUTO（自动跟随，内存态）。 */
object ContextSource {
    const val MANUAL = "manual"
    const val AUTO = "auto"
}
```

`StoredContextEntry` 加字段：

```kotlin
data class StoredContextEntry(
    val id: String = UUID.randomUUID().toString(),
    val kind: String,
    val label: String,
    val path: String = "",
    val content: String? = null,
    val source: String = ContextSource.MANUAL
)
```

`SessionRepository.kt`：load 处（约 100–105 行）`StoredContextEntry(` 调用加 `source = c.optString("source", ContextSource.MANUAL)`；save 处（约 148–154 行）`o.put("label", c.label)` 后加 `o.put("source", c.source)`。需 import `ContextSource`（同包，无需 import）。

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="$PWD/.tools/android-studio/jbr" ./gradlew test --tests "com.aicode.plugin.chat.SessionRepositoryTest"`
Expected: BUILD SUCCESSFUL（含既有回环测试）。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/aicode/plugin/chat/Session.kt src/main/kotlin/com/aicode/plugin/chat/SessionRepository.kt src/test/kotlin/com/aicode/plugin/chat/SessionRepositoryTest.kt
git commit -m "feat: persist context entry source (manual/auto)"
```

---

### Task 4: ChatController 持有 transient 跟随项

**Files:**
- Modify: `src/main/kotlin/com/aicode/plugin/chat/ChatController.kt`（约 135–145 行上下文区 + 约 210–230 行 send 组装）

本任务无新增单测：逻辑由 Task 2 覆盖，发送合并走 Task 9 手动验收（send 需真实网络）。

- [ ] **Step 1: 加字段与 setter（context 段，`clearContext()` 之后）**

```kotlin
    /** 自动跟随项（内存态，不随会话持久化；发送时与会话 entries 合并）。 */
    var followEntry: StoredContextEntry? = null
        private set

    fun setFollowEntry(e: StoredContextEntry?) {
        followEntry = e
    }
```

- [ ] **Step 2: send() 合并组装**

原（约 215 行）：

```kotlin
        val contextBlocks = s.contextEntries.mapNotNull { ContextResolver.resolve(it, project) }
```

改：

```kotlin
        val allEntries = s.contextEntries + listOfNotNull(followEntry)
        val contextBlocks = allEntries.mapNotNull { ContextResolver.resolve(it, project) }
```

原日志（约 227 行）`"... 上下文=${s.contextEntries.size}项）"` 改为 `"... 上下文=${allEntries.size}项）"`（仅替换该插值，其余不动）。

`clearContext()` 保持只清会话内 entries（自动项由面板经 `setFollowEntry(null)` 清，见 Task 7）。

- [ ] **Step 3: 编译验证**

Run: `JAVA_HOME="$PWD/.tools/android-studio/jbr" ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/aicode/plugin/chat/ChatController.kt
git commit -m "feat: controller holds transient follow entry merged at send"
```

---

### Task 5: EditorFollowTracker

**Files:**
- Create: `src/main/kotlin/com/aicode/plugin/chat/EditorFollowTracker.kt`

无新增单测：平台监听器只能手动验收（Task 9 清单第 1–2 项）；状态逻辑已由 Task 2 覆盖。

- [ ] **Step 1: 实现（完整代码）**

```kotlin
package com.aicode.plugin.chat

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.events.CaretEvent
import com.intellij.openapi.editor.events.CaretListener
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.Alarm

/**
 * 跟踪选中编辑器 + 选中区，驱动 FollowState。
 * 全部监听以 project 为 parent Disposable，随项目关闭自动释放，无需 stop()。
 * 回调 onChange 保证在 EDT 上执行。
 */
class EditorFollowTracker(
    private val project: Project,
    private val state: FollowState,
    private val onChange: () -> Unit
) {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)

    private fun schedule() {
        alarm.cancelAllRequests()
        alarm.addRequest({ recompute() }, 300)
    }

    private fun recompute() {
        val editor = com.intellij.openapi.fileEditor.FileEditorManager
            .getInstance(project).selectedTextEditor
        val vf = editor?.virtualFile
        val target: FollowTarget? =
            if (editor == null || vf == null || !vf.isInLocalFileSystem || vf.isDirectory) {
                null
            } else {
                val sel = editor.selectionModel
                if (sel.selectionEnd > sel.selectionStart) {
                    val doc = editor.document
                    val sl = doc.getLineNumber(sel.selectionStart) + 1
                    val el = doc.getLineNumber(sel.selectionEnd) + 1
                    FollowTarget(
                        kind = ContextKind.SNIPPET,
                        path = vf.path,
                        label = "${vf.name} ($sl-$el)",
                        startLine = sl,
                        endLine = el,
                        content = sel.selectedText
                    )
                } else {
                    FollowTarget(ContextKind.FILE, vf.path, vf.name)
                }
            }
        if (state.update(target)) onChange()
    }

    init {
        project.messageBus.connect(project)
            .subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
                object : FileEditorManagerListener {
                    override fun selectionChanged(e: FileEditorManagerEvent) = schedule()
                })
        com.intellij.openapi.editor.EditorFactory.getInstance().eventMulticaster
            .addCaretListener(object : CaretListener {
                override fun caretPositionChanged(e: CaretEvent) {
                    if (e.editor.project == project) schedule()
                }
            }, project)
        // 面板打开时立刻算一次
        ApplicationManager.getApplication().invokeLater { recompute() }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `JAVA_HOME="$PWD/.tools/android-studio/jbr" ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/aicode/plugin/chat/EditorFollowTracker.kt
git commit -m "feat: track selected editor/selection into FollowState"
```

---

### Task 6: MentionPopup

**Files:**
- Create: `src/main/kotlin/com/aicode/plugin/chat/MentionPopup.kt`

无新增单测：JBPopup 交互走 Task 9 手动验收（第 3 项）；过滤/排序纯逻辑内联但与 UI 线程交织，不单独抽取（YAGNI）。

- [ ] **Step 1: 实现（完整代码）**

```kotlin
package com.aicode.plugin.chat

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.Component
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList

/** @ 选择结果：文件直接关联；目录可进入或整体关联。 */
sealed class MentionPick {
    class File(val path: String) : MentionPick()
    class Dir(val path: String) : MentionPick()
}

private sealed class Row {
    class Up(val path: String) : Row()                       // 返回上級
    class AttachDir(val path: String) : Row()                // 关联当前目录
    class Entry(val name: String, val path: String, val isDir: Boolean) : Row()
}

/**
 * @ 文件选择器：起点项目根；打字过滤；目录可进入；可直接关联目录。
 * 由 ChatPanel 驱动：show → updateFilter* → close；选中回调 onPick。
 */
class MentionPopup(private val project: Project) {

    private var popup: JBPopup? = null
    private var onPick: ((MentionPick) -> Unit)? = null
    private val model = DefaultListModel<Row>()
    private val list = JBList(model).apply { visibleRowCount = 10 }

    private var baseDir: String = project.basePath ?: "/"
    private var curDir: String = baseDir
    private var filter: String = ""

    fun isOpen(): Boolean = popup?.isDisposed?.not() ?: false

    fun show(anchor: JComponent, initialFilter: String, onPick: (MentionPick) -> Unit) {
        close()
        this.onPick = onPick
        this.curDir = baseDir
        list.cellRenderer = rowRenderer()
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) activate(list.selectedValue)
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    e.consume()
                    activate(list.selectedValue)
                }
            }
        })
        val scroll = JBScrollPane(list).apply { preferredSize = Dimension(380, 260) }
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(scroll, null)
            .setFocusable(true)
            .setRequestFocus(false)
            .setShowBorder(true)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .createPopup()
        popup?.showInBestPositionFor(anchor)
        updateFilter(initialFilter)
    }

    fun updateFilter(f: String) {
        if (!isOpen()) return
        filter = f
        val dir = curDir
        ApplicationManager.getApplication().executeOnPooledThread {
            val rows = listRows(dir, f)
            ApplicationManager.getApplication().invokeLater {
                if (!isOpen() || dir != curDir || f != filter) return@invokeLater
                model.clear()
                rows.forEach { model.addElement(it) }
                if (model.size() > 0) list.selectedIndex = 0
            }
        }
    }

    fun close() {
        popup?.cancel()
        popup = null
    }

    private fun activate(row: Row?) {
        when (row) {
            is Row.Entry ->
                if (row.isDir) {
                    curDir = row.path
                    updateFilter(filter)
                } else {
                    val cb = onPick
                    close()
                    cb?.invoke(MentionPick.File(row.path))
                }
            is Row.AttachDir -> {
                val cb = onPick
                val dir = row.path
                close()
                cb?.invoke(MentionPick.Dir(dir))
            }
            is Row.Up -> {
                File(curDir).parent?.let {
                    curDir = it
                    updateFilter(filter)
                }
            }
            null -> {}
        }
    }

    private fun listRows(dir: String, f: String): List<Row> {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(dir) ?: return emptyList()
        val out = mutableListOf<Row>()
        if (dir != baseDir) out.add(Row.Up(dir))
        out.add(Row.AttachDir(dir))
        val q = f.trim().lowercase()
        val children = vf.children?.toList().orEmpty()
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        var count = 0
        for (c in children) {
            if (count >= 500) break
            if (q.isNotEmpty() && !c.name.lowercase().contains(q)
                && !c.path.lowercase().contains(q)
            ) continue
            out.add(Row.Entry(if (c.isDirectory) c.name + "/" else c.name, c.path, c.isDirectory))
            count++
        }
        return out
    }

    private fun rowRenderer() = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            listOf: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val label = super.getListCellRendererComponent(
                listOf, displayOf(value), index, isSelected, cellHasFocus
            ) as javax.swing.JLabel
            label.icon = when (value) {
                is Row.Entry -> if (value.isDir) AllIcons.Nodes.Folder else AllIcons.FileTypes.Text
                is Row.AttachDir -> AllIcons.Nodes.Folder
                else -> AllIcons.General.ArrowUp
            }
            return label
        }
    }

    private fun displayOf(v: Any?): String = when (v) {
        is Row.Entry -> v.name
        is Row.AttachDir -> "关联整个目录：${v.path}  ⏎"
        is Row.Up -> "← 返回上级"
        else -> ""
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `JAVA_HOME="$PWD/.tools/android-studio/jbr" ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/aicode/plugin/chat/MentionPopup.kt
git commit -m "feat: @ file picker popup rooted at project base"
```

---

### Task 7: ChatPanel 组装（chips 行 + @ 接线 + 跟随接线 + 删除旧入口）

**Files:**
- Modify: `src/main/kotlin/com/aicode/plugin/chat/ChatPanel.kt`
- Modify: `src/main/kotlin/com/aicode/plugin/action/SendToAiCodeAction.kt`

- [ ] **Step 1: 删除旧入口**

删除字段 `addFileButton`、`addFolderButton`（58–59 行）、`chipsPanel`（62–64 行）；
删除 `providerRow.add(addFileButton)` / `add(addFolderButton)`（142–143 行）；
删除 `topPanel.add(chipsPanel)`（155 行）；
删除 `chooseContext()` 整个方法（360–371 行）；
删除 `import javax.swing.JFileChooser`（29 行）；
更新类头布局注释（41–47 行）：第二行改为 `│ Provider 下拉 │ 设置 │ 清空 │`，删除第三行 chips 行（chips 行移到输入框上方，见 Step 2）。

- [ ] **Step 2: 新增 chips 行（输入框上方）+ 字段**

字段区（`clearContextButton` 后）加：

```kotlin
    private val attachButton = JButton("+")
    private val attachRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
        border = JBUI.Borders.empty(2, 8, 2, 8)
        isOpaque = false
    }
    private val followState = FollowState()
    private lateinit var followTracker: EditorFollowTracker
    private val mentionPopup by lazy { MentionPopup(project) }
```

`buildUi()` 中 `bottomPanel.add(inputPanel)` 之前插入：

```kotlin
        attachButton.toolTipText = "关联文件（@选择器）"
        attachButton.addActionListener { openMentionPopup("") }
        attachRow.add(attachButton)
        clearContextButton.toolTipText = "清除手动关联 + 当前跟随项"
        clearContextButton.addActionListener {
            controller.clearContext()
            followState.dismiss()
            controller.setFollowEntry(null)
            rebuildAttachRow()
        }
```

`bottomPanel` 组装改为：`attachRow` → `inputPanel` → `modelRow`。
`init` 末尾加：`followTracker = EditorFollowTracker(project, followState) { onFollowChanged() }`，
新增：

```kotlin
    private fun onFollowChanged() {
        controller.setFollowEntry(followState.current?.toEntry())
        rebuildAttachRow()
    }
```

- [ ] **Step 3: chips 渲染（复用 buildChip）+ @ 接线**

新增：

```kotlin
    private fun rebuildAttachRow() {
        attachRow.removeAll()
        attachRow.add(attachButton)
        controller.currentSession()?.contextEntries.orEmpty().forEach { entry ->
            attachRow.add(buildChip(entry, auto = false))
        }
        controller.followEntry?.let { entry ->
            attachRow.add(buildChip(entry, auto = true))
        }
        attachRow.revalidate()
        attachRow.repaint()
    }

    private fun openMentionPopup(initialFilter: String) {
        mentionPopup.show(inputArea, initialFilter) { pick ->
            when (pick) {
                is MentionPick.File -> controller.addFileContext(pick.path)
                is MentionPick.Dir -> controller.addFolderContext(pick.path)
            }
            rebuildAttachRow()
        }
    }
```

`buildChip(entry)` 加参 `auto: Boolean = false`：自动 chip 的 `remove` 按钮动作改为
`{ followState.dismiss(); controller.setFollowEntry(null); rebuildAttachRow() }`，
手动 chip 保持 `controller.removeContextEntry(entry.id)`；之后统一 `rebuildAttachRow()`。
旧 `rebuildChips()` 删除；三处调用点替换为 `rebuildAttachRow()`：
`onContextChanged()`、`handleCommand()` 尾部、（init 中 `rebuildTranscript()` 后加一次）。

输入框 `@` 监听（`buildUi` 事件区追加，与现有 DocumentListener 并列）：

```kotlin
        inputArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onInputChanged()
            override fun removeUpdate(e: DocumentEvent?) = onInputChanged()
            override fun changedUpdate(e: DocumentEvent?) = onInputChanged()
        })
```

新增：

```kotlin
    private fun onInputChanged() {
        updateSendButton()
        val text = inputArea.text
        val q = extractMentionQuery(text, inputArea.caretPosition)
        if (q == null) {
            if (mentionPopup.isOpen()) mentionPopup.close()
            return
        }
        if (mentionPopup.isOpen()) {
            mentionPopup.updateFilter(q.filter)
        } else {
            openMentionPopup(q.filter)
        }
    }
```

`openMentionPopup` 在选中回调开头加：删除已输入的 `@xxx` token——把 `show` 的 `onPick` 包一层：

```kotlin
        mentionPopup.show(inputArea, initialFilter) { pick ->
            val qq = extractMentionQuery(inputArea.text, inputArea.caretPosition)
            if (qq != null) {
                val (nt, nc) = removeMentionQuery(inputArea.text, qq, inputArea.caretPosition)
                inputArea.text = nt
                inputArea.caretPosition = nc
            }
            when (pick) {
                is MentionPick.File -> controller.addFileContext(pick.path)
                is MentionPick.Dir -> controller.addFolderContext(pick.path)
            }
            rebuildAttachRow()
        }
```

（以此版本为准，替代 Step 3 首个 `openMentionPopup`。）

- [ ] **Step 4: 文案更新**

`/help` 文本（329–339 行）改为：

```
"命令：\n" +
    "  /add <路径>   把指定文件或文件夹加入上下文\n" +
    "  /clear       开启新对话\n" +
    "  /help        显示本帮助\n\n" +
    "输入框上方的 + 或输入 @ 可关联项目文件；\n" +
    "当前打开的文件/选中代码会自动关联（×可取消），\n" +
    "或在编辑器中选中代码后右键 Send to AiCode。"
```

空态提示（约 507–510 行）`Add project files/folders as context...` 改为
`Type @ to attach project files, or just open a file — it follows your editor.<br>`。

- [ ] **Step 5: SendToAiCodeAction 新语义**

`actionPerformed` 的选择分支改为只加 snippet（不再 `service.addFile`，文件由自动跟随覆盖），无选中时不加任何 entry；保留激活工具窗口；删除 `Notifications` 提示块及相关 import（`Notification`, `NotificationType`, `Notifications`）。`AiCodeUiService.addFile/addSnippet` 原样保留（YAGNI，不重构 service）。

- [ ] **Step 6: 编译验证**

Run: `JAVA_HOME="$PWD/.tools/android-studio/jbr" ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/aicode/plugin/chat/ChatPanel.kt src/main/kotlin/com/aicode/plugin/action/SendToAiCodeAction.kt
git commit -m "feat: chips row above input, @ wiring, follow wiring, drop +File/+Folder"
```

---

### Task 8: UI 构造测试更新 + 全量验证

**Files:**
- Modify: `src/test/kotlin/com/aicode/plugin/chat/ChatPanelUiConstructionTest.kt`

- [ ] **Step 1: 追加断言（`testChatPanelConstructsAndExposesNewControls` 末尾、`controllerOf` 断言后）**

```kotlin
        // 6) 新 chips 行：+ 按钮在面板树中
        val plus = field<javax.swing.JButton>(p, "attachButton")
        assertEquals("+", plus.text)
        assertComponentParented(p, plus, "attachButton 应在面板树中")

        // 7) 旧 +File/+Folder 按钮已删除
        assertThrows(NoSuchFieldException::class.java) {
            ChatPanel::class.java.getDeclaredField("addFileButton")
        }
        assertThrows(NoSuchFieldException::class.java) {
            ChatPanel::class.java.getDeclaredField("addFolderButton")
        }
```

需 import：`import org.junit.Assert.assertThrows`（JUnit 4.13 已在依赖中：`testImplementation("junit:junit:4.13.2")`）。

- [ ] **Step 2: 运行 UI 构造测试**

Run: `JAVA_HOME="$PWD/.tools/android-studio/jbr" ./gradlew test --tests "com.aicode.plugin.chat.ChatPanelUiConstructionTest"`
Expected: BUILD SUCCESSFUL（headless 环境会自动跳过 Swing 构造并打印 UI-SKIP；有显示环境则真实断言）。

- [ ] **Step 3: 全量测试 + 打包**

Run: `JAVA_HOME="$PWD/.tools/android-studio/jbr" ./gradlew test buildPlugin`
Expected: BUILD SUCCESSFUL，无失败用例。

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/aicode/plugin/chat/ChatPanelUiConstructionTest.kt
git commit -m "test: chips row construction assertions, drop old buttons"
```

---

### Task 9: 手动验收（runIde，按 spec §5）

- [ ] **Step 1: 启动沙箱**：`JAVA_HOME="$PWD/.tools/android-studio/jbr" ./gradlew runIde`（沙箱 IDE 完全重启）。
- [ ] **Step 2: 逐项验收**：①切编辑器自动 chip 跟换 ②选中→行号 chip→取消选中恢复文件 chip ③× 后切走切回重现 ④`@` 过滤/进目录/关联文件与目录 ⑤`+`/Esc/清空语义 ⑥发送后 warn 日志 model 正确且回复引用文件内容 ⑦旧按钮消失 ⑧右键 Send 可用。
- [ ] **Step 3: 验收通过后再 commit 遗留改动**（如有）：`git add -A && git commit -m "fix: manual acceptance fixes for follow+mention"`；无改动则跳过。

---

## Self-Review

1. **Spec 覆盖**：§1 组件→Task 3/5/6/7；§2 跟随链→Task 5+7（防抖300ms/比对刷新/dismissed），@触发→Task 1+6+7，目录行→Task 6（Row.Up/AttachDir），发送组装→Task 4+7，会话切换→Task 4（transient）+7；§3 错误处理→Task 6（500上限/后台过滤/空结果）+ 沿用 ContextResolver（Task 7 未改 resolve 路径）；§4 单元→Task 1/2/3/8，手动→Task 9。`clearContext` 新语义→Task 7 Step 2。右键动作→Task 7 Step 5。
2. **Placeholder 扫描**：无 TBD/TODO/“类似 Task N”；每处代码完整；`NoSuchFieldException` 断言使用 JUnit4.13 真实 API；`Alarm(SWING_THREAD, project)`、`messageBus.connect(project)`、`eventMulticaster.addCaretListener(..., project)` 均为平台真实签名；`createComponentPopupBuilder` 参数顺序（content, preferableFocusComponent）正确。
3. **类型一致**：`FollowTarget.toEntry()` 定义于 Task 2，Task 7 直接复用；`MentionPick.File/Dir` 定义于 Task 6，Task 7 引用一致；`ContextSource.AUTO/MANUAL` 定义于 Task 3，Task 2/7 引用一致；`StoredContextEntry.source` 默认 MANUAL，老数据经 `optString` 兼容。
