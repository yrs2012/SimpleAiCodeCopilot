package com.aicodecopilot.plugin.chat

import com.aicodecopilot.plugin.provider.AiCodeCopilotSettingsConfigurable
import com.aicodecopilot.plugin.provider.PromptsMemoryConfigurable
import com.aicodecopilot.plugin.provider.ProviderConfig
import com.aicodecopilot.plugin.provider.ProviderConfigDialog
import com.aicodecopilot.plugin.provider.ProviderManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Insets
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.html.HTMLEditorKit

/**
 * AiCodeCopilot 聊天面板（右侧工具窗口内容）。
 *
 * 布局：
 *   ┌ 会话下拉 │ ＋新对话 │ 🗑删除          │ ⚙(设置菜单) ┐
 *   │ 消息区（HTML 渲染，流式追加）        │
 *   │ + │ 关联 chips │ 清空                 │
 *   │ 输入框（Enter 发送 / Shift+Enter 换行） │ 发送/停止 │
 *   └ Provider 下拉 │ Model: 模型下拉 ┘
 */
class ChatPanel(private val project: Project) : JPanel(BorderLayout()), ChatUiListener {

    private val controller = ChatController(project, this)

    // 顶部控件
    private val sessionCombo = JComboBox<ComboItem>()
    private val newChatButton = JButton(AllIcons.General.Add)
    private val deleteSessionButton = JButton(AllIcons.General.Delete)
    private val providerCombo = JComboBox<ProviderConfig>()
    private val settingsMenuButton = JButton(AllIcons.General.GearPlain)
    private val clearContextButton = JButton("Clear")

    /** 输入框上方 chips 行：+ 按钮 + 自动跟随/手动关联 chips（多行自动长高）。 */
    private val attachButton = JButton("+")
    private val attachRow = JPanel(WrapLayout(6, 2)).apply {
        border = JBUI.Borders.empty(2, 8, 2, 8)
        isOpaque = false
    }
    private val followState = FollowState()
    private lateinit var followTracker: EditorFollowTracker
    private val mentionPopup by lazy { MentionPopup(project) }

    // 消息区（HTML 渲染）
    private val transcriptPane = JEditorPane().apply {
        isEditable = false
        contentType = "text/html"
        isOpaque = false
        val kit = HTMLEditorKit()
        kit.styleSheet.addRule("body { background-color: transparent; margin: 0; }")
        kit.styleSheet.addRule(
            "div, p, span { font-family: -apple-system, 'Segoe UI', 'Noto Sans CJK SC', " +
                "sans-serif; font-size: 13px; color: inherit; }"
        )
        editorKit = kit
    }
    private val messagesScroll = JBScrollPane(transcriptPane,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)

    // 输入区
    private val inputArea = JBTextArea(4, 40)
    private val sendButton = JButton("Send")
    private val stopButton = JButton("Stop")
    private val sendLayout = CardLayout()
    private val sendButtonPanel = JPanel(sendLayout)

    // 模型选择（输入框下方）
    private val modelCombo = JComboBox<String>()

    /** 非持久化的提示/错误气泡（仅当前面板生命周期内可见）。 */
    private val infoBubbles = mutableListOf<InfoBubble>()

    init {
        buildUi()
        controller.newChat()
        refreshSessionCombo()
        refreshProviderCombo()
        refreshModelCombo()
        rebuildTranscript()
        rebuildAttachRow()
        followTracker = EditorFollowTracker(project, followState) { onFollowChanged() }
    }

    private fun onFollowChanged() {
        controller.setFollowEntry(followState.current?.toEntry())
        rebuildAttachRow()
    }

    // ==================================================================
    // UI 构建
    // ==================================================================

    private fun buildUi() {
        // ---- 第一行：会话（左侧）+ 设置菜单（右侧） ----
        val sessionRow = JPanel(BorderLayout(0, 0)).apply {
            border = JBUI.Borders.empty(6, 8, 2, 8)
        }
        sessionCombo.preferredSize = Dimension(240, 26)
        newChatButton.toolTipText = "New chat"
        newChatButton.addActionListener { controller.newChat() }
        deleteSessionButton.toolTipText = "Delete current session"
        deleteSessionButton.addActionListener {
            val s = controller.currentSession()
            if (s != null && s.persisted) controller.deleteCurrentSession()
        }
        val sessionLeft = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        sessionLeft.add(sessionCombo)
        sessionLeft.add(newChatButton)
        sessionLeft.add(deleteSessionButton)
        sessionRow.add(sessionLeft, BorderLayout.WEST)
        settingsMenuButton.toolTipText = "AiCodeCopilot 设置（Provider / 系统提示词 & Memory）"
        settingsMenuButton.margin = Insets(0, 0, 0, 0)
        settingsMenuButton.preferredSize = Dimension(30, 30)
        settingsMenuButton.isFocusable = false
        settingsMenuButton.addActionListener { openSettingsMenu(settingsMenuButton) }
        sessionRow.add(settingsMenuButton, BorderLayout.EAST)

        // ---- 清空上下文（挂在文件列表行最右侧，见 rebuildAttachRow） ----
        clearContextButton.toolTipText = "清除手动关联 + 当前跟随项"
        clearContextButton.preferredSize = Dimension(50, 32)
        clearContextButton.addActionListener {
            controller.clearContext()
            followState.dismiss()
            controller.setFollowEntry(null)
            rebuildAttachRow()
        }

        add(sessionRow, BorderLayout.NORTH)

        // ---- 消息区 ----
        messagesScroll.border = JBUI.Borders.empty(0, 10, 4, 10)
        add(messagesScroll, BorderLayout.CENTER)

        // ---- 输入区（带边框，保证可见） ----
        inputArea.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor(0x8A8D93.toInt(), 0x8A8D93.toInt()), 1),
            JBUI.Borders.empty(6, 8, 6, 8)
        )
        inputArea.toolTipText = "Enter 发送，Shift+Enter 换行；支持 /add <路径> /clear /help 命令"
        sendButton.addActionListener { onSendClicked() }
        stopButton.addActionListener { controller.stop() }
        stopButton.isEnabled = false
        sendButtonPanel.add(sendButton, "send")
        sendButtonPanel.add(stopButton, "stop")

        val inputPanel = JPanel(BorderLayout(6, 0)).apply {
            border = JBUI.Borders.empty(4, 8, 4, 8)
        }
        inputPanel.add(inputArea, BorderLayout.CENTER)
        inputPanel.add(sendButtonPanel, BorderLayout.EAST)

        // ---- 底部行（输入框下方）：Provider 下拉 │ Model: 模型下拉 ----
        val modelRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            border = JBUI.Borders.empty(0, 8, 8, 8)
            isOpaque = false
        }
        providerCombo.preferredSize = Dimension(130, maxOf(26, providerCombo.preferredSize.height))
        providerCombo.toolTipText = "Active LLM provider"
        // val modelLabel = JLabel("Model:")
        // modelLabel.foreground = JBColor.GRAY
        modelCombo.preferredSize = Dimension(200, maxOf(26, modelCombo.preferredSize.height))
        modelCombo.toolTipText = "Active model for the current provider"
        modelRow.add(providerCombo)
        // modelRow.add(modelLabel)
        modelRow.add(modelCombo)

        val bottomPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        attachButton.toolTipText = "关联文件（@选择器）"
        attachButton.addActionListener { openMentionPopup("") }
        // attachButton.margin = java.awt.Insets(0, 0, 0, 0)
        attachButton.preferredSize = Dimension(30, 30)
        attachRow.add(attachButton)
        bottomPanel.add(attachRow)
        bottomPanel.add(inputPanel)
        bottomPanel.add(modelRow)
        add(bottomPanel, BorderLayout.SOUTH)

        // ---- 事件 ----
        sessionCombo.addActionListener {
            if (updatingCombo) return@addActionListener
            when (val item = sessionCombo.selectedItem) {
                is ComboItem.Saved -> {
                    val cur = controller.currentSession()
                    if (cur?.id != item.meta.id) controller.loadSession(item.meta)
                }
                else -> {}
            }
        }
        providerCombo.addActionListener {
            if (updatingCombo) return@addActionListener
            val p = providerCombo.selectedItem as? ProviderConfig ?: return@addActionListener
            if (p.id == ADD_PROVIDER_ID) {
                // 哨兵项：回到当前选中并打开新增对话框（哨兵永不驻留、不进 controller）
                refreshProviderCombo()
                openAddProviderDialog()
                return@addActionListener
            }
            controller.setProvider(p.id)
            refreshModelCombo()
        }
        modelCombo.addActionListener {
            if (updatingCombo) return@addActionListener
            val m = modelCombo.selectedItem as? String ?: return@addActionListener
            controller.setModel(m)
        }
        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                // @ 弹窗打开时：焦点仍在输入框，上下/回车/Esc 转发给弹窗
                if (mentionPopup.isOpen()) {
                    when (e.keyCode) {
                        KeyEvent.VK_UP -> {
                            mentionPopup.moveSelection(-1)
                            e.consume()
                            return
                        }
                        KeyEvent.VK_DOWN -> {
                            mentionPopup.moveSelection(1)
                            e.consume()
                            return
                        }
                        KeyEvent.VK_ENTER -> {
                            if (!e.isShiftDown && !e.isControlDown && !e.isAltDown) {
                                mentionPopup.confirmSelected()
                                e.consume()
                                return
                            }
                        }
                        KeyEvent.VK_ESCAPE -> {
                            mentionPopup.close()
                            e.consume()
                            return
                        }
                    }
                }
                if (e.keyCode == KeyEvent.VK_ENTER &&
                    !e.isShiftDown && !e.isControlDown && !e.isAltDown
                ) {
                    e.consume()
                    onSendClicked()
                    return
                }
                if (e.keyCode == KeyEvent.VK_ENTER &&
                    e.isShiftDown && !e.isControlDown && !e.isAltDown
                ) {
                    // Shift+Enter 换行：显式插入（不依赖 keymap，工具窗上下文里 keyTyped 可能到不了）
                    e.consume()
                    inputArea.replaceSelection("\n")
                }
            }
        })
        inputArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = updateSendButton()
            override fun removeUpdate(e: DocumentEvent?) = updateSendButton()
            override fun changedUpdate(e: DocumentEvent?) = updateSendButton()
        })
        inputArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onInputChanged()
            override fun removeUpdate(e: DocumentEvent?) = onInputChanged()
            override fun changedUpdate(e: DocumentEvent?) { /* 纯文本属性变化，无需处理 */ }
        })
        // 工具窗宽度变化时让 HTML 重新按可视宽度换行
        messagesScroll.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                transcriptPane.setSize(
                    messagesScroll.viewport.width.coerceAtLeast(200),
                    transcriptPane.height
                )
            }
        })
        updateSendButton()
    }

    // ==================================================================
    // ChatUiListener（均在 EDT 上）
    // ==================================================================

    override fun onMessagesChanged() {
        rebuildTranscript()
    }

    override fun onStreamDelta() {
        rebuildTranscript()
    }

    override fun onStreamFinished() {
        rebuildTranscript()
        refreshSessionCombo()
        refreshProviderCombo()
        refreshModelCombo()
        updateSendButton()
    }

    override fun onSessionsChanged() {
        refreshSessionCombo()
        refreshModelCombo()
        rebuildAttachRow()
    }

    override fun onContextChanged() {
        rebuildAttachRow()
    }

    override fun onError(message: String) {
        infoBubbles.add(InfoBubble.Error(message))
        rebuildTranscript()
    }

    // ==================================================================
    // 行为
    // ==================================================================

    private fun onSendClicked() {
        // 流式回复进行中：忽略新发送（Stop 按钮独立处理停止）
        if (controller.isStreaming) return
        val text = inputArea.text
        if (text.isBlank()) return
        // 新一次发送：清掉上次的提示/报错气泡（否则失败提示永远堆在最后）
        infoBubbles.clear()
        inputArea.text = ""
        if (handleCommand(text)) return
        controller.send(text)
    }

    /** 简单的斜杠命令。返回 true 表示已消费该输入。 */
    private fun handleCommand(input: String): Boolean {
        if (!input.startsWith("/")) return false
        val parts = input.trim().split(" ", limit = 2)
        val arg = parts.getOrElse(1) { "" }.trim()
        when (parts[0].lowercase()) {
            "/add" -> {
                if (arg.isEmpty()) {
                    infoBubbles.add(InfoBubble.Error("用法：/add <文件或文件夹的绝对路径>"))
                } else {
                    val f = File(arg)
                    if (!f.exists()) {
                        infoBubbles.add(InfoBubble.Error("路径不存在：$arg"))
                    } else if (f.isDirectory) {
                        controller.addFolderContext(f.absolutePath)
                        infoBubbles.add(InfoBubble.Info("已添加文件夹上下文：${f.name}"))
                    } else {
                        controller.addFileContext(f.absolutePath)
                        infoBubbles.add(InfoBubble.Info("已添加文件上下文：${f.name}"))
                    }
                }
            }
            "/clear" -> {
                controller.newChat()
                infoBubbles.clear()
                inputArea.text = ""
            }
            "/help" -> {
                infoBubbles.add(
                    InfoBubble.Info(
                            "命令：\n" +
                            "  /add <路径>   把指定文件或文件夹加入上下文\n" +
                            "  /clear       开启新对话\n" +
                            "  /help        显示本帮助\n\n" +
                            "输入框上方的 + 或输入 @ 可关联项目文件；\n" +
                            "当前打开的文件/选中代码会自动关联（×可取消），\n" +
                            "或在编辑器中选中代码后右键 Send to AiCodeCopilot。"
                    )
                )
            }
            else -> {
                infoBubbles.add(InfoBubble.Error("未知命令：${parts[0]}（输入 /help 查看帮助）"))
            }
        }
        rebuildAttachRow()
        rebuildTranscript()
        return true
    }

    /** 供 SendToAiCodeCopilotAction 调用：追加一段选中代码作为上下文。 */
    fun addSnippetContext(label: String, content: String) {
        controller.addSnippetContext(label, content)
    }

    /** 供 SendToAiCodeCopilotAction 调用：把文件加入上下文。 */
    fun addFileContext(path: String) {
        controller.addFileContext(path)
    }

    // ==================================================================
    // 关联 chips 行（输入框上方）
    // ==================================================================

    private fun rebuildAttachRow() {
        attachRow.removeAll()
        attachRow.add(attachButton)
        controller.currentSession()?.contextEntries.orEmpty().forEach { entry ->
            attachRow.add(buildChip(entry, auto = false))
        }
        controller.followEntry?.let { entry ->
            attachRow.add(buildChip(entry, auto = true))
        }
        attachRow.add(clearContextButton)
        attachRow.revalidate()
        attachRow.repaint()
    }

    private fun openMentionPopup(initialFilter: String) {
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
    }

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

    // ==================================================================
    // 渲染
    // ==================================================================

    private fun refreshSessionCombo() {
        updatingCombo = true
        sessionCombo.removeAllItems()
        controller.sessions().forEach { sessionCombo.addItem(ComboItem.Saved(it)) }
        // 新建草稿不占条目（无 Draft 项）：首发消息落盘后由 send() 触发刷新并选中当前
        val cur = controller.currentSession()
        val target: ComboItem.Saved? = if (cur != null && cur.persisted) {
            controller.sessions().firstOrNull { it.id == cur.id }?.let { ComboItem.Saved(it) }
        } else null
        if (target != null) sessionCombo.selectedItem = target
        else sessionCombo.selectedIndex = -1
        updatingCombo = false
    }

    private fun refreshProviderCombo() {
        updatingCombo = true
        val providers = ProviderManager.getInstance().enabled()
        providerCombo.removeAllItems()
        providers.forEach { providerCombo.addItem(it) }
        providerCombo.addItem(ProviderConfig(id = ADD_PROVIDER_ID, name = "+ Add provider…"))
        val cur = controller.activeProvider()
        providerCombo.selectedItem = providers.firstOrNull { it.id == cur?.id }
        if (providers.isEmpty()) {
            // 一个可用 provider 都没有：不选中哨兵（否则点不开新增），留待用户点选
            providerCombo.selectedIndex = -1
        } else if (providerCombo.selectedIndex < 0) {
            providerCombo.selectedIndex = 0
        }
        updatingCombo = false
    }

    /** 新增 Provider（provider 下拉末尾哨兵项入口）。 */
    private fun openAddProviderDialog() {
        val dialog = ProviderConfigDialog(null)
        dialog.setOKActionListener {
            ProviderManager.getInstance().add(dialog.buildResult())
            refreshProviderCombo()
            refreshModelCombo()
        }
        dialog.show()
    }

    /** 顶部右侧“设置”下拉菜单：直达 IDE 设置页（Provider 设置 / 系统提示词 & Memory）。 */
    private fun openSettingsMenu(anchor: JComponent) {
        val menu = JPopupMenu()
        menu.add(
            JMenuItem("Provider 设置").apply {
                addActionListener {
                    // showSettingsDialog(project, configurable) 按实例 displayName 定位设置页
                    ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, AiCodeCopilotSettingsConfigurable())
                }
            }
        )
        menu.add(
            JMenuItem("系统提示词 & Memory").apply {
                addActionListener {
                    ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, PromptsMemoryConfigurable())
                }
            }
        )
        menu.show(anchor, anchor.width, 0)
    }

    /** 根据当前 Provider 填充模型下拉，并选中会话所选模型（或首个）。 */
    private fun refreshModelCombo() {
        updatingCombo = true
        modelCombo.removeAllItems()
        val models = controller.availableModels()
        models.forEach { modelCombo.addItem(it) }
        val cur = controller.activeModel()
        modelCombo.selectedItem = cur.takeIf { it in models } ?: models.firstOrNull()
        if (modelCombo.selectedIndex < 0 && modelCombo.itemCount > 0) {
            modelCombo.selectedIndex = 0
        }
        modelCombo.isEnabled = modelCombo.itemCount > 0
        updatingCombo = false
    }

    private fun buildChip(entry: StoredContextEntry, auto: Boolean = false): JPanel {
        val icon = when (entry.kind) {
            ContextKind.FOLDER -> AllIcons.Nodes.Folder
            ContextKind.SNIPPET -> AllIcons.Actions.Edit
            else -> AllIcons.FileTypes.Text
        }
        val label = JLabel(entry.label, icon, SwingConstants.LEADING).apply {
            toolTipText = entry.path.ifBlank { entry.content ?: "" }
            foreground = JBColor.GRAY
        }
        val remove = JButton("×").apply {
            border = JBUI.Borders.empty(0)
            isFocusable = false
            preferredSize = Dimension(18, 18)
            if (auto) {
                toolTipText = "取消本次跟随"
                addActionListener {
                    followState.dismiss()
                    controller.setFollowEntry(null)
                    rebuildAttachRow()
                }
            } else {
                addActionListener { controller.removeContextEntry(entry.id) }
            }
        }
        val chip = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            border = JBUI.Borders.customLine(JBColor.GRAY, 1)
            background = JBColor(Color(0xF5, 0xF5, 0xF5), Color(0x2A, 0x2A, 0x2A))
            add(label)
            add(remove)
        }
        return chip
    }

    private fun rebuildTranscript() {
        val s = controller.currentSession()
        val messages = s?.messages.orEmpty()
        transcriptPane.text = renderTranscript(messages, infoBubbles)
        // HTML 模式：先让 pane 按当前可视宽度排版，再滚动到底部
        transcriptPane.setSize(messagesScroll.viewport.width.coerceAtLeast(200), transcriptPane.height)
        transcriptPane.revalidate()
        messagesScroll.verticalScrollBar.value = messagesScroll.verticalScrollBar.maximum
    }

    /**
     * 极简 Markdown → HTML 渲染：
     * - ``` 围栏代码块 → <pre>
     * - `行内代码` → <code>
     * - 其余转义后换行
     */
    private fun renderTranscript(messages: List<StoredMessage>, info: List<InfoBubble>): String {
        val sb = StringBuilder("<html><body>")
        val timeFmt = SimpleDateFormat("HH:mm")

        if (messages.isEmpty() && info.isEmpty()) {
            sb.append(
                "<div style=\"text-align:center; color:#888888; margin-top:48px;\">" +
                    "<div style=\"font-size:30px;\">🤖</div>" +
                    "<div style=\"font-size:15px; font-weight:bold; margin-top:6px;\">AiCodeCopilot Assistant</div>" +
                    "<div style=\"font-size:12px; margin-top:8px;\">" +
                    "Ask anything about your code.<br>" +
                    "Type @ to attach project files, or just open a file — it follows your editor.<br>" +
                    "Type <code>/help</code> for slash commands.</div></div>"
            )
            return sb.append("</body></html>").toString()
        }

        for (m in messages) {
            if (m.content.isEmpty()) continue // 流式占位消息还没有内容
                val who = if (m.role == Role.USER) "You" else "AiCodeCopilot"
            val bg = when (m.role) {
                Role.USER -> "#E3F2FD"
                else -> "#F2F2F2"
            }
            sb.append(
                "<div style=\"margin: 4px 0 10px 0;\">" +
                    "<div style=\"font-size: 11px; color: #888888;\">$who · ${timeFmt.format(Date(m.ts))}</div>" +
                    "<div style=\"background-color: $bg; padding: 8px 10px; border: 1px solid #D8D8D8;\">" +
                    renderContent(m.content) +
                    "</div></div>"
            )
        }
        for (b in info) {
            val (bg, text) = when (b) {
                is InfoBubble.Error -> "#FFEBEE" to b.text
                is InfoBubble.Info -> "#E8F5E9" to b.text
            }
            sb.append(
                "<div style=\"margin: 4px 0 10px 0;\">" +
                    "<div style=\"background-color: $bg; padding: 6px 10px; border: 1px solid #D8D8D8;\">" +
                    renderContent(text) +
                    "</div></div>"
            )
        }
        return sb.append("</body></html>").toString()
    }

    private fun renderContent(text: String): String {
        // 按 ``` 切分，偶数为普通文本、奇数为代码块
        val parts = text.split("```")
        val sb = StringBuilder()
        parts.forEachIndexed { i, part ->
            if (i % 2 == 1) {
                val lines = part.lineSequence().toList()
                val first = lines.firstOrNull { it.isNotBlank() }?.trim()
                val code = if (lines.firstOrNull()?.trim() == first && first.isNullOrBlank().not() && first.length <= 20) {
                    lines.drop(1).joinToString("\n")
                } else {
                    lines.joinToString("\n")
                }
                sb.append(
                    "<pre style=\"background-color:#282C34; color:#D4D4D4; padding:8px; " +
                        "font-family:monospace; margin:4px 0;\">${escapeHtml(code.trimEnd())}</pre>"
                )
            } else {
                val escaped = escapeHtml(part)
                val withInlineCode = "`([^`]+)`".toRegex().replace(escaped) { m ->
                    "<code style=\"background-color:#E0E0E0;\">${m.groupValues[1]}</code>"
                }
                sb.append(withInlineCode.replace("\n", "<br>"))
            }
        }
        return sb.toString()
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun updateSendButton() {
        val streaming = controller.isStreaming
        if (streaming) {
            sendLayout.show(sendButtonPanel, "stop")
            stopButton.isEnabled = true
        } else {
            sendLayout.show(sendButtonPanel, "send")
            sendButton.isEnabled = inputArea.text.isNotBlank()
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private var updatingCombo = false

    sealed class ComboItem {
        class Saved(val meta: SessionMeta) : ComboItem() {
            override fun toString(): String = meta.title
        }
    }

    sealed class InfoBubble {
        class Error(val text: String) : InfoBubble()
        class Info(val text: String) : InfoBubble()
    }

    companion object {
        const val TOOL_WINDOW_ID = "AiCodeCopilot"
        /** provider 下拉末尾“新增”哨兵项的 id（不对应真实 provider，选中即打开新增对话框）。 */
        const val ADD_PROVIDER_ID = "__aicode_add_provider__"
    }
}
