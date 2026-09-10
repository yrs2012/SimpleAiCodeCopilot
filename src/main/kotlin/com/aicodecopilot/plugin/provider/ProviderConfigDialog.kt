package com.aicodecopilot.plugin.provider

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Window
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/**
 * 添加 / 编辑 Provider 的对话框。
 *
 * 字段：名称、Base URL、模型、API Key、Temperature、Max Tokens、
 * 额外请求头（每行 "Key: Value"）、启用开关，以及一个“测试连接”按钮。
 */
class ProviderConfigDialog(existing: ProviderConfig?) : DialogWrapper(null, true) {

    private val log = Logger.getInstance(ProviderConfigDialog::class.java)

    private val source = existing ?: ProviderConfig()

    private val nameField = JBTextField(source.name)
    private val baseUrlField = JBTextField(source.baseUrl)
    private val modelField = JBTextField(source.model)
    private val apiKeyField = JBPasswordField().apply { text = source.apiKey }
    /** Temperature 用滑杆（0.0–2.0，内部按 0–200 存取）。 */
    private val temperatureSlider = JSlider(0, 200, (source.temperature.coerceIn(0.0, 2.0) * 100).toInt()).apply {
        minorTickSpacing = 10
        majorTickSpacing = 50
        paintTicks = true
    }
    private val temperatureValueLabel = JBLabel(formatDouble(source.temperature)).apply {
        preferredSize = java.awt.Dimension(36, preferredSize.height)
    }
    private val temperatureRow = JPanel(BorderLayout(8, 0)).apply {
        add(temperatureSlider, BorderLayout.CENTER)
        add(temperatureValueLabel, BorderLayout.EAST)
    }
    private val maxTokensField = JBTextField(source.maxTokens.toString()).apply {
        columns = 12
    }
    private val headersText = JBTextArea(3, 30).apply {
        text = source.extraHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    }
    private val enabledBox = JBCheckBox("Enabled", source.enabled)
    private val testButton = JButton("Test Connection")

    /** “自动添加模型”区域：按钮 + 已选模型列表 + 移除按钮。 */
    private val fetchModelsButton = JButton("Auto Add Models")
    private val modelsModel = DefaultListModel<String>().apply {
        source.models.forEach { addElement(it) }
    }
    private val modelsList = JList<String>(modelsModel).apply {
        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        visibleRowCount = 6
    }
    private val removeModelButton = JButton("Remove Selected")
    private val modelsEmptyLabel = JBLabel("(no models yet — click “Auto Add Models”)")

    /** 运行中的网络操作：再次点击对应按钮 = 取消。 */
    private val testRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val testCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val fetchRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val fetchCancelled = java.util.concurrent.atomic.AtomicBoolean(false)

    /** OK 按钮回调（由 Settings 面板设置，用于非模态下持久化表单结果）。 */
    private var okActionListener: (() -> Unit)? = null

    fun setOKActionListener(listener: () -> Unit) {
        okActionListener = listener
    }

    override fun doOKAction() {
        super.doOKAction()
        okActionListener?.invoke()
    }

    init {
        title = if (existing == null) "Add Provider" else "Edit Provider — ${existing.name}"
    
        log.warn("=== 初始化 ProviderConfigDialog ===")
        log.warn("设置 Test Connection 按钮监听器...")
        testButton.addActionListener {
            log.warn("!!! Test Connection 按钮被点击 !!!")
            testConnection()
        }
        
        log.warn("设置 Auto Add Models 按钮监听器...")
        fetchModelsButton.addActionListener {
            log.warn("!!! Auto Add Models 按钮被点击 !!!")
            fetchModels()
        }
        
        removeModelButton.addActionListener {
            log.warn("!!! Remove Selected 按钮被点击 !!!")
            removeSelectedModels()
        }

        temperatureSlider.addChangeListener {
            temperatureValueLabel.text = formatDouble(temperatureSlider.value / 100.0)
        }
        
        init()
        
        // 设置为非模态对话框
        isModal = false
        log.warn("对话框设置为非模态")
        
        log.warn("=== ProviderConfigDialog 初始化完成 ===")
    }

    override fun createCenterPanel(): JComponent {
        val form = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 6, 4, 6)
            anchor = GridBagConstraints.WEST
        }

        var row = 0
        fun addRow(label: String, component: JComponent, grow: Boolean = true) {
            gbc.gridy = row; gbc.gridx = 0
            form.add(JLabel(label), gbc)
            gbc.gridx = 1
            gbc.fill = if (grow) GridBagConstraints.HORIZONTAL else GridBagConstraints.NONE
            gbc.weightx = if (grow) 1.0 else 0.0
            form.add(component, gbc)
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            row++
        }

        addRow("Name", nameField)
        addRow("Base URL", baseUrlField)
        addRow("Model", modelField)
        addRow("API Key", apiKeyField)
        addRow("Temperature", temperatureRow)
        // 与 Name/Base URL 等行一样占满整列：GridBag 空间不足时会优先压缩
        // weightx=0 且 fill=NONE 的组件（之前会被压到只剩几像素），占满后不再被单独压扁
        addRow("Max Tokens(KB)", maxTokensField)
        addRow("Extra Headers", headersText)
        gbc.gridy = row; gbc.gridx = 1
        form.add(enabledBox, gbc)
        row++
        gbc.gridy = row; gbc.gridx = 1
        form.add(testButton, gbc)
        row++

        // ---- 自动添加模型区 ----
        gbc.gridy = row; gbc.gridx = 0
        gbc.anchor = GridBagConstraints.NORTHWEST
        form.add(JLabel("Models"), gbc)
        gbc.anchor = GridBagConstraints.WEST
        gbc.gridy = row; gbc.gridx = 1
        // 模型行吃掉所有多余纵向空间：内容整体贴顶，拉伸时列表变高而不是整体居中
        gbc.fill = GridBagConstraints.BOTH
        gbc.weightx = 1.0
        gbc.weighty = 1.0
        val modelsColumn = JPanel(BorderLayout(4, 4)).apply {
            border = JBUI.Borders.empty(2)
        }
        modelsColumn.add(fetchModelsButton, BorderLayout.NORTH)
        val modelsArea = JPanel(BorderLayout(4, 0))
        val modelsScroll = JBScrollPane(modelsList)
        modelsScroll.preferredSize = java.awt.Dimension(360, 140)
        modelsScroll.minimumSize = java.awt.Dimension(360, 100)
        modelsArea.add(modelsScroll, BorderLayout.CENTER)
        modelsArea.add(removeModelButton, BorderLayout.SOUTH)
        modelsColumn.add(modelsArea, BorderLayout.CENTER)
        form.add(modelsColumn, gbc)
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        gbc.weighty = 0.0
        row++
        // 空列表提示（有模型时隐藏）
        gbc.gridy = row; gbc.gridx = 0
        gbc.gridwidth = 2
        form.add(modelsEmptyLabel, gbc)
        gbc.gridwidth = 1

        modelsEmptyLabel.isVisible = modelsModel.isEmpty()
        modelsModel.addListDataListener(object : javax.swing.event.ListDataListener {
            override fun intervalAdded(e: javax.swing.event.ListDataEvent) = syncModelsEmpty()
            override fun intervalRemoved(e: javax.swing.event.ListDataEvent) = syncModelsEmpty()
            override fun contentsChanged(e: javax.swing.event.ListDataEvent) = syncModelsEmpty()
        })
        // 选中变化也要刷新按钮状态，否则选了 item 后 Remove 仍是灰的
        modelsList.addListSelectionListener { syncModelsEmpty() }
        syncModelsEmpty()

        form.border = JBUI.Borders.empty(8)
        form.preferredSize = java.awt.Dimension(500, form.preferredSize.height)

        val wrapper = JPanel(BorderLayout(0, 0))
        // 内容贴顶：放 NORTH，有多余高度时不再垂直居中
        wrapper.add(form, BorderLayout.NORTH)
        return wrapper
    }

    private fun syncModelsEmpty() {
        modelsEmptyLabel.isVisible = modelsModel.isEmpty()
        removeModelButton.isEnabled = modelsList.selectedIndices.isNotEmpty()
    }

    private fun removeSelectedModels() {
        val idx = modelsList.selectedIndices
        if (idx.isEmpty()) return
        // 从后往前删，避免索引位移
        for (i in idx.reversed()) modelsModel.remove(i)
    }

    /** 用当前表单的 name/baseUrl/apiKey 拉取模型列表，成功后直接弹出 Select Models。 */
    private fun fetchModels() {
        log.warn("=== fetchModels() 被调用 ===")
        log.warn("fetchRunning=${fetchRunning.get()}, fetchCancelled=${fetchCancelled.get()}")
        
        // 已在运行：再次点击 = 取消
        if (fetchRunning.get()) {
            fetchCancelled.set(true)
            log.warn("AiCodeCopilot: Auto Add Models 被用户取消")
            fetchModelsButton.text = "Auto Add Models"
            fetchModelsButton.isEnabled = true
            fetchModelsButton.toolTipText = null
            return
        }
        
        val baseUrl = baseUrlField.text.trim()
        val name = nameField.text.trim()
        log.warn("baseUrl=$baseUrl, name=$name")
        
        if (name.isBlank()) {
            log.warn("Name 为空，显示错误")
            fetchModelsButton.text = "Auto Add Models"
            fetchModelsButton.toolTipText = "Name is required"
            fetchModelsButton.isEnabled = true
            return
        }
        if (baseUrl.isBlank()) {
            log.warn("Base URL 为空，显示错误")
            fetchModelsButton.text = "Auto Add Models"
            fetchModelsButton.toolTipText = "Base URL is required"
            fetchModelsButton.isEnabled = true
            return
        }
        
        val cfg = ProviderConfig(
            name = name,
            baseUrl = baseUrl,
            apiKey = String(apiKeyField.password).trim(),
            extraHeaders = parseHeaders(headersText.text),
            model = modelField.text.trim().ifBlank { "default" }
        )
        log.warn("配置创建完成")
        
        fetchRunning.set(true)
        fetchCancelled.set(false)
        fetchModelsButton.text = "Fetching… (click to cancel)"
        fetchModelsButton.isEnabled = true
        
        log.warn("AiCodeCopilot: Auto Add Models 开始（provider=$name, baseUrl=$baseUrl）")
        
        ApplicationManager.getApplication().executeOnPooledThread {
            log.warn("=== 后台线程开始执行 fetchModels ===")
            val start = System.currentTimeMillis()
            
            val outcome: Result<List<String>> = runCatching {
                LlmClient.listModels(cfg)
            }
            
            val elapsed = System.currentTimeMillis() - start
            log.warn("AiCodeCopilot: Auto Add Models 网络调用结束（${elapsed}ms，成功=${outcome.isSuccess}）")
            
            if (outcome.isFailure) {
                log.warn("AiCodeCopilot: Auto Add Models 失败", outcome.exceptionOrNull())
            }
            
            // 使用 Swing Timer 来更新 UI
            javax.swing.Timer(100) { e ->
                (e.source as javax.swing.Timer).stop()
                
                log.warn("=== Timer 开始执行 UI 更新 ===")
                
                fetchRunning.set(false)
                fetchModelsButton.isEnabled = true
                fetchModelsButton.text = "Auto Add Models"
                
                if (fetchCancelled.get()) {
                    log.warn("AiCodeCopilot: Auto Add Models 已取消")
                    fetchModelsButton.toolTipText = "Fetch cancelled"
                    return@Timer
                }
                
                if (outcome.isSuccess) {
                    val models = outcome.getOrThrow()
                    log.warn("成功获取 ${models.size} 个模型")
                    
                    if (models.isEmpty()) {
                        log.warn("AiCodeCopilot: 服务端返回空模型列表")
                        fetchModelsButton.text = "Auto Add Models (Empty)"
                        fetchModelsButton.toolTipText = "Server returned an empty model list"
                    } else {
                        log.warn("准备更新模型列表")
                        
                        // 直接更新模型列表
                        modelsModel.clear()
                        models.forEach { modelsModel.addElement(it) }
                        
                        // 更新 Model 字段
                        val cur = modelField.text.trim()
                        if (cur.isBlank() || cur !in models) {
                            modelField.text = models.firstOrNull() ?: ""
                        }
                        
                        log.warn("模型列表已更新，共 ${models.size} 个模型")
                        fetchModelsButton.text = "Auto Add Models (✓ ${models.size})"
                        fetchModelsButton.toolTipText = "Added ${models.size} models"
                    }
                } else {
                    val msg = outcome.exceptionOrNull()?.message ?: "unknown error"
                    log.warn("AiCodeCopilot: Auto Add Models 失败：$msg")
                    fetchModelsButton.text = "Auto Add Models (Failed)"
                    fetchModelsButton.toolTipText = msg
                }
                
                // 强制刷新 UI
                fetchModelsButton.revalidate()
                fetchModelsButton.repaint()
                modelsList.revalidate()
                modelsList.repaint()
                // 内容变化后按首选尺寸重新 pack，保证模型列表可见（Timer 跑在 EDT 上，可直接调）
                SwingUtilities.getWindowAncestor(modelsList)?.pack()
                
                log.warn("UI 已刷新")
            }.apply {
                isRepeats = false
                start()
            }
        }
        log.warn("=== fetchModels() 方法结束 ===")
    }

    private fun sourceModelsSet(): Set<String> = modelsModelAsList().toSet()

    private fun modelsModelAsList(): List<String> {
        val out = ArrayList<String>(modelsModel.size)
        for (i in 0 until modelsModel.size) out.add(modelsModel.getElementAt(i))
        return out
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) return ValidationInfo("Name is required", nameField)
        if (baseUrlField.text.isBlank()) return ValidationInfo("Base URL is required", baseUrlField)
        if (modelField.text.isBlank() && modelsModel.isEmpty()) {
            return ValidationInfo(
                "Model is required (fill it in or use Auto Add Models)",
                modelField
            )
        }
        return null
    }

    fun buildResult(): ProviderConfig {
        val headers = parseHeaders(headersText.text)
        val models = modelsModelAsList().toMutableList()
        var model = modelField.text.trim()
        if (model.isBlank()) model = models.firstOrNull() ?: ""
        else if (models.isNotEmpty() && model !in models) {
            // Model 字段不在自动添加的列表里时，保留用户手填值（允许自定义模型名）
        }
        return ProviderConfig(
            id = source.id,
            name = nameField.text.trim(),
            baseUrl = baseUrlField.text.trim(),
            apiKey = String(apiKeyField.password).trim(),
            model = model,
            temperature = temperatureSlider.value / 100.0,
            maxTokens = maxTokensField.text.trim().toIntOrNull() ?: 128,
            extraHeaders = headers,
            enabled = enabledBox.isSelected,
            models = models
        )
    }


private fun testConnection() {
    log.warn("=== testConnection() 被调用 ===")
    log.warn("testRunning=${testRunning.get()}, testCancelled=${testCancelled.get()}")
    
    // 已在运行：再次点击 = 取消
    if (testRunning.get()) {
        testCancelled.set(true)
        log.warn("AiCodeCopilot: Test Connection 被用户取消")
        testButton.text = "Test Connection"
        testButton.isEnabled = true
        testButton.toolTipText = null
        return
    }

    log.warn("开始验证表单...")
    val validationInfo = doValidate()
    log.warn("doValidate() 返回: $validationInfo")

    if (validationInfo != null) {
        log.warn("表单验证失败: ${validationInfo.message}")
        testButton.text = "Test Connection"
        testButton.toolTipText = validationInfo.message
        return
    }

    log.warn("表单验证通过，开始构建配置...")
    val cfg = try {
        buildResult()
    } catch (e: Exception) {
        log.warn("buildResult() 异常", e)
        testButton.text = "Test Connection"
        testButton.toolTipText = "构建配置失败: ${e.message}"
        return
    }

    log.warn("配置构建完成: name=${cfg.name}, baseUrl=${cfg.baseUrl}, model=${cfg.model}")

    testRunning.set(true)
    testCancelled.set(false)
    
    // 在 EDT 上更新按钮状态
    SwingUtilities.invokeLater {
        testButton.text = "Testing… (click to cancel)"
        testButton.isEnabled = true
        testButton.revalidate()
        testButton.repaint()
    }

    log.warn("AiCodeCopilot: Test Connection 开始（provider=${cfg.name}, baseUrl=${cfg.baseUrl}, model=${cfg.model}）")
    
    ApplicationManager.getApplication().executeOnPooledThread {
        log.warn("=== 后台线程开始执行 ===")
        val start = System.currentTimeMillis()
        
        val outcome: Pair<Boolean, String> = try {
            val reply = LlmClient.testConnection(cfg).trim().take(120)
            log.warn("LlmClient.testConnection() 返回成功")
            true to "Connection OK. Model replied: $reply"
        } catch (e: Exception) {
            log.warn("Test Connection 异常", e)
            false to (e.message ?: e.javaClass.simpleName)
        }
        
        val elapsed = System.currentTimeMillis() - start
        log.warn("AiCodeCopilot: Test Connection 网络调用结束（${elapsed}ms，成功=${outcome.first}）")
        
        // 使用 SwingUtilities.invokeLater 更新 UI
        SwingUtilities.invokeLater {
            log.warn("=== SwingUtilities.invokeLater 开始执行 ===")
            
            testRunning.set(false)
            testButton.isEnabled = true
            
            if (testCancelled.get()) {
                log.warn("AiCodeCopilot: Test Connection 已取消")
                testButton.text = "Test Connection"
                testButton.toolTipText = "Test cancelled"
                testButton.revalidate()
                testButton.repaint()
                return@invokeLater
            }
            
            if (outcome.first) {
                log.warn("AiCodeCopilot: Test Connection 成功：${outcome.second}")
                testButton.text = "✓ Connection OK"
                testButton.toolTipText = outcome.second
            } else {
                log.warn("AiCodeCopilot: Test Connection 失败：${outcome.second}")
                testButton.text = "✗ Connection Failed"
                testButton.toolTipText = outcome.second
            }
            
            // 强制刷新按钮
            testButton.revalidate()
            testButton.repaint()
            log.warn("按钮状态已更新")
        }
    }
    log.warn("=== testConnection() 方法结束 ===")
}

    // 添加安全的对话框显示方法
    private fun showInfoDialog(message: String, title: String) {
        try {
            val window = getWindow()
            if (window != null && window.isShowing) {
                Messages.showMessageDialog(window, message, title, Messages.getInformationIcon())
            } else {
                Messages.showMessageDialog(message, title, Messages.getInformationIcon())
            }
        } catch (e: Exception) {
            log.warn("显示对话框失败", e)
            // 降级方案：使用标准对话框
            javax.swing.JOptionPane.showMessageDialog(
                null,
                message,
                title,
                javax.swing.JOptionPane.INFORMATION_MESSAGE
            )
        }
    }

    private fun showErrorDialog(message: String, title: String) {
        try {
            val window = getWindow()
            if (window != null && window.isShowing) {
                Messages.showErrorDialog(window, message, title)
            } else {
                Messages.showErrorDialog(message, title)
            }
        } catch (e: Exception) {
            log.warn("显示错误对话框失败", e)
            // 降级方案：使用标准对话框
            javax.swing.JOptionPane.showMessageDialog(
                null,
                message,
                title,
                javax.swing.JOptionPane.ERROR_MESSAGE
            )
        }
    }

    private fun parseHeaders(text: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains(":") }
            .forEach { line ->
                val idx = line.indexOf(':')
                val k = line.substring(0, idx).trim()
                val v = line.substring(idx + 1).trim()
                if (k.isNotEmpty()) map[k] = v
            }
        return map
    }

    private fun formatDouble(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}
