package com.LazeroX

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.datatransfer.StringSelection
import java.util.ArrayDeque
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.KeyStroke

/**
 * 保存单个项目中的 JSON 工具窗组件及交互状态。
 *
 * 项目级服务可以让工具窗与编辑器快捷键访问同一个输入区域，快捷键格式化选区后
 * 无需通过静态变量传递内容，也不会把不同 IDEA 项目的 JSON 内容混在一起。
 */
@Service(Service.Level.PROJECT)
internal class JsonToolWindowService(private val project: Project) : Disposable {
    companion object {
        /** plugin.xml 中注册的工具窗唯一标识。 */
        const val TOOL_WINDOW_ID = "JsonAssistant"

        /** plugin.xml 中注册的工具窗格式化动作标识。 */
        private const val FORMAT_CURRENT_JSON_ACTION_ID = "com.LazeroX.FormatCurrentJsonAction"

        /** plugin.xml 中注册的工具窗复制动作标识。 */
        private const val COPY_JSON_ACTION_ID = "com.LazeroX.CopyJsonAction"

        /** plugin.xml 中注册的工具窗压缩动作标识。 */
        private const val MINIFY_JSON_ACTION_ID = "com.LazeroX.MinifyJsonAction"

        // 自定义着色放在语法层之上，确保颜色不会被编辑器默认文本属性覆盖。
        private const val JSON_HIGHLIGHT_LAYER = HighlighterLayer.SYNTAX + 1

        // Object 折叠后仍保留花括号语义，省略号用于表示内部内容已隐藏。
        private const val OBJECT_FOLD_PLACEHOLDER = "{…}"

    }

    /**
     * JSON Object 或 Array 的可折叠范围及折叠占位符。
     *
     * @property textRange 包含开始和结束括号的完整文本范围
     * @property placeholder 折叠后展示的 `{…}` 或包含元素数量的 `[...,x个]`
     */
    private data class JsonFoldRange(val textRange: TextRange, val placeholder: String)

    /**
     * 可通过意图动作展开的字符串子 JSON。
     *
     * @property textRange 包含字符串起止双引号的完整 value 范围
     * @property decodedJson 已解除字符串转义并规范化为单行的 Object 或 Array
     */
    private data class NestedJsonString(
        val textRange: TextRange,
        val decodedJson: String
    )

    /**
     * 扫描 JSON 嵌套结构时记录尚未闭合的括号。
     *
     * @property character 起始花括号或方括号
     * @property offset 起始括号在文档中的偏移量
     * @property arrayCommaCount 当前 Array 顶层已扫描到的元素分隔逗号数量
     */
    private data class OpeningDelimiter(
        val character: Char,
        val offset: Int,
        var arrayCommaCount: Int = 0
    )

    // 使用 IDEA Document 保存内容，让原生编辑器负责光标、选择、撤销和滚动行为。
    private val jsonDocument = EditorFactory.getInstance().createDocument("")

    // EditorEx 自动采用当前 IDEA 编辑器字体、字号和主题，无需维护独立的字体配置。
    private val jsonEditor = (EditorFactory.getInstance().createEditor(jsonDocument, project) as EditorEx).apply {
        settings.apply {
            // 左侧行号与折叠轮廓共同构成 gutter，用户可直接点击图标折叠 Object 或 Array。
            isLineNumbersShown = true
            isFoldingOutlineShown = true
            isLineMarkerAreaShown = true
            isIndentGuidesShown = true
            // JSON 结构依赖真实换行和缩进，关闭软换行避免折叠层级产生视觉歧义。
            isUseSoftWraps = false
            additionalLinesCount = 0
            additionalColumnsCount = 0
        }
        setPlaceholder(MyMessageBundle.message("json.toolwindow.input.placeholder"))
        setShowPlaceholderWhenFocused(true)
    }

    // 只记录本服务创建的着色范围，刷新时不会影响编辑器自身的选区与搜索高亮。
    private val syntaxHighlighters = mutableListOf<RangeHighlighter>()

    // 括号配对同时保存两个方向，使光标位于开始或结束括号时都能常量时间定位另一端。
    private var delimiterPairs: Map<Int, Int> = emptyMap()

    // 配对结果只适用于同一文档版本，防止异步刷新前使用已经失效的括号偏移量。
    private var delimiterPairsModificationStamp = -1L

    // 匹配括号高亮独立管理，移动光标时无需重建整篇 JSON 的语法颜色。
    private val matchedDelimiterHighlighters = mutableListOf<RangeHighlighter>()

    // 保存本轮扫描识别出的字符串子 JSON，供光标位置判断和 Alt+Enter 意图动作使用。
    private var nestedJsonStrings: List<NestedJsonString> = emptyList()

    // 合并同一事件循环内的连续文档变化，避免粘贴较大 JSON 时重复扫描中间状态。
    private var decorationRefreshScheduled = false

    // 组件只创建一次，使工具窗隐藏再打开时仍能保留用户尚未复制的内容。
    private val content = createContent()

    init {
        // 用户输入、粘贴或快捷键写入结果后，统一刷新颜色和结构折叠区间。
        jsonDocument.addDocumentListener(object : DocumentListener {
            /**
             * 文档内容变化后安排一次展示刷新。
             *
             * @param event IDEA 编辑器产生的文档变化事件
             */
            override fun documentChanged(event: DocumentEvent) {
                scheduleDecorationRefresh()
            }
        }, this)

        // 轻量 EditorEx 没有 PSI 括号匹配能力，因此在光标移动后按预先扫描的配对结果刷新高亮。
        jsonEditor.caretModel.addCaretListener(object : CaretListener {
            /**
             * 光标位置变化时高亮当前 JSON 括号及其匹配项。
             *
             * @param event IDEA 编辑器产生的光标移动事件
             */
            override fun caretPositionChanged(event: CaretEvent) {
                refreshMatchedDelimiterHighlight()
            }
        }, this)

        // 复用 IDEA“显示意图动作”的当前快捷键；用户修改 Keymap 后插件也会自动跟随。
        registerNestedJsonIntentionShortcut()
    }

    /**
     * 返回注册到 IDEA 工具窗中的根组件。
     *
     * @return 包含排序按钮与 JSON 编辑器的 Swing 组件
     */
    fun getContent(): JComponent = content

    /**
     * 格式化工具窗编辑器中的当前内容，并就地替换为格式化结果。
     */
    fun formatCurrentText() {
        // 读取完整文档而不是选区，符合“格式化侧边栏 JSON”的右键菜单操作语义。
        when (val result = JsonFormatter.format(jsonDocument.text)) {
            is JsonFormatResult.Success -> setFormattedText(result.formattedJson)
            // 非法输入保持原文不变；工具窗不再额外占用顶部或底部空间显示提示。
            is JsonFormatResult.Failure -> Unit
        }
    }

    /**
     * 递归排序每一层 Object 的同级 key，并将格式化结果写回编辑器。
     *
     * @param ascending true 表示按 A-Z 升序，false 表示按 Z-A 降序
     */
    fun sortCurrentText(ascending: Boolean) {
        when (val result = JsonFormatter.sortKeys(jsonDocument.text, ascending)) {
            // 排序结果统一通过可撤销写入入口更新，因此 Ctrl+Z 可以恢复排序前的 JSON。
            is JsonFormatResult.Success -> setFormattedText(result.formattedJson)
            // JSON 不合法时保留用户当前内容，不执行任何局部排序。
            is JsonFormatResult.Failure -> Unit
        }
    }

    /**
     * 将来自代码编辑器或终端选区的格式化 JSON 写入工具窗。
     *
     * @param formattedJson 已通过语法校验并完成缩进的 JSON 文本
     */
    fun setFormattedText(formattedJson: String) {
        // 可撤销写命令既满足 Document 的线程约束，也会把本次整体替换加入 IDEA 撤销栈。
        WriteCommandAction.runWriteCommandAction(
            project,
            MyMessageBundle.message("json.undo.command.update"),
            null,
            Runnable {
                // 一次 setText 对应一次用户可理解的 JSON 操作，按 Ctrl+Z 可完整恢复旧内容。
                jsonDocument.setText(formattedJson)
            }
        )
        // 光标和视口回到开头，打开工具窗时优先看到 JSON 的顶层结构。
        jsonEditor.caretModel.moveToOffset(0)
        jsonEditor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
    }

    /**
     * 将 JSON 编辑器中的完整内容复制到系统剪贴板。
     */
    fun copyCurrentText() {
        val jsonText = jsonDocument.text
        // 空内容不写入剪贴板，避免覆盖用户之前复制的有效数据。
        if (jsonText.isBlank()) {
            return
        }

        CopyPasteManager.getInstance().setContents(StringSelection(jsonText))
    }

    /**
     * 将编辑器中的 JSON 压缩为不带缩进和换行的单行文本。
     */
    fun minifyCurrentText() {
        when (val result = JsonFormatter.minify(jsonDocument.text)) {
            is JsonFormatResult.Success -> setFormattedText(result.formattedJson)

            // 压缩失败时保留输入框内容，不再显示底部状态提示。
            is JsonFormatResult.Failure -> Unit
        }
    }

    /**
     * 注册仅作用于 JSON 工具窗编辑器的意图动作快捷键。
     *
     * 工具窗使用无 PSI 的轻量 EditorEx，无法进入代码分析器的标准 IntentionAction 流程，
     * 因此在同一编辑器组件上复用 IDEA 的意图快捷键并展示原生动作弹窗。
     */
    private fun registerNestedJsonIntentionShortcut() {
        val showIntentionsAction = object : DumbAwareAction() {
            /**
             * 仅当光标位于可展开的字符串子 JSON 中时启用动作。
             *
             * @param event IDEA 动作系统提供的当前上下文
             */
            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = findNestedJsonAtCaret() != null
            }

            /**
             * 在当前光标旁展示“将子 JSON 序列化”选项。
             *
             * @param event IDEA 动作系统提供的数据上下文
             */
            override fun actionPerformed(event: AnActionEvent) {
                showNestedJsonIntentionPopup(event.dataContext)
            }
        }

        // 优先读取用户当前 Keymap 中的快捷键，而不是把 Alt+Enter 永久写死在组件上。
        val shortcutSet = ActionManager.getInstance()
            .getAction(IdeActions.ACTION_SHOW_INTENTION_ACTIONS)
            ?.shortcutSet
            // 极少数裁剪版平台没有该动作时，回退到 IDEA 的默认 Alt+Enter。
            ?: CustomShortcutSet(
                KeyboardShortcut(
                    KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.ALT_DOWN_MASK),
                    null
                )
            )
        showIntentionsAction.registerCustomShortcutSet(shortcutSet, jsonEditor.contentComponent, this)
    }

    /**
     * 使用 IDEA 原生动作弹窗展示当前字符串子 JSON 的可用修复。
     *
     * @param dataContext 当前工具窗编辑器的数据上下文，用于执行弹窗中的 IDEA 动作
     */
    private fun showNestedJsonIntentionPopup(dataContext: DataContext) {
        // 快捷键触发与弹窗创建之间文档可能发生变化，因此展示前再次确认当前范围仍然有效。
        if (findNestedJsonAtCaret() == null) {
            return
        }

        val expandAction = object : DumbAwareAction(MyMessageBundle.message("json.nested.action.expand")) {
            /**
             * 将光标所在的字符串子 JSON 替换为真正的嵌套结构。
             *
             * @param event IDEA 动作系统提供的当前上下文
             */
            override fun actionPerformed(event: AnActionEvent) {
                expandNestedJsonAtCaret()
            }
        }
        val actionGroup = DefaultActionGroup().apply {
            add(expandAction)
        }

        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                null,
                actionGroup,
                dataContext,
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true
            )
            // 明确以 JSON 编辑器定位，确保选项弹窗出现在当前子 JSON 光标附近。
            .showInBestPositionFor(jsonEditor)
    }

    /**
     * 展开光标所在的字符串子 JSON，并重新格式化完整文档。
     */
    private fun expandNestedJsonAtCaret() {
        val nestedJson = findNestedJsonAtCaret() ?: return
        val currentText = jsonDocument.text
        // Range 来源于最近一次扫描；边界检查可防止异步文档变化导致替换越界。
        if (nestedJson.textRange.endOffset > currentText.length) {
            return
        }

        val expandedText = currentText.replaceRange(
            nestedJson.textRange.startOffset,
            nestedJson.textRange.endOffset,
            nestedJson.decodedJson
        )
        when (val result = JsonFormatter.format(expandedText)) {
            is JsonFormatResult.Success -> {
                // setFormattedText 会在写操作中整体替换文档，并触发颜色、警告与折叠范围重建。
                setFormattedText(result.formattedJson)
            }

            // 外层 JSON 正在编辑且尚未闭合时保留原文，不执行局部破坏性替换。
            is JsonFormatResult.Failure -> Unit
        }
    }

    /**
     * 查找当前光标所在的可展开字符串子 JSON。
     *
     * @return 光标命中的子 JSON；未命中时返回 null
     */
    private fun findNestedJsonAtCaret(): NestedJsonString? {
        val caretOffset = jsonEditor.caretModel.offset
        return nestedJsonStrings.firstOrNull { nestedJson ->
            caretOffset >= nestedJson.textRange.startOffset && caretOffset < nestedJson.textRange.endOffset
        }
    }

    /**
     * 释放项目级服务持有的 IDEA 编辑器实例。
     */
    override fun dispose() {
        syntaxHighlighters.clear()
        matchedDelimiterHighlighters.clear()
        delimiterPairs = emptyMap()
        delimiterPairsModificationStamp = -1L
        nestedJsonStrings = emptyList()
        if (!jsonEditor.isDisposed) {
            EditorFactory.getInstance().releaseEditor(jsonEditor)
        }
    }

    /**
     * 创建工具窗布局。
     *
     * @return 使用边界布局、能随工具窗尺寸变化自动伸缩的根面板
     */
    private fun createContent(): JComponent {
        val operationToolbar = createOperationToolbar()

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            // 顶部集中放置 JSON 操作与排序按钮，避免功能入口继续占用编辑器右键菜单。
            border = JBUI.Borders.empty(8)
            add(operationToolbar, BorderLayout.NORTH)
            // EditorEx 自带滚动条与 gutter，不再额外包裹 Swing ScrollPane。
            add(jsonEditor.component, BorderLayout.CENTER)
        }
    }

    /**
     * 创建与 IDEA 内置工具窗一致的 JSON 操作工具栏。
     *
     * 格式化、复制和压缩动作复用 plugin.xml 中注册的动作，确保工具栏入口与原业务方法保持一致；
     * 排序动作继续使用专属图标，清晰区分 A-Z 与 Z-A 两种方向。
     *
     * @return 包含格式化、复制、压缩以及升降序按钮的横向工具栏组件
     */
    private fun createOperationToolbar(): JComponent {
        val actionManager = ActionManager.getInstance()
        // 三个动作均由当前插件描述文件注册；缺失代表插件配置损坏，应在创建工具窗时立即暴露。
        val formatAction = requireNotNull(actionManager.getAction(FORMAT_CURRENT_JSON_ACTION_ID))
        val copyAction = requireNotNull(actionManager.getAction(COPY_JSON_ACTION_ID))
        val minifyAction = requireNotNull(actionManager.getAction(MINIFY_JSON_ACTION_ID))
        val ascendingAction = object : DumbAwareAction(
            MyMessageBundle.message("json.sort.ascending.tooltip"),
            MyMessageBundle.message("json.sort.ascending.tooltip"),
            IconLoader.getIcon("/icons/sortAscending.svg", JsonToolWindowService::class.java)
        ) {
            /**
             * 按 A-Z 方向排序当前编辑器中的所有同级 JSON key。
             *
             * @param event IDEA 动作系统提供的当前上下文
             */
            override fun actionPerformed(event: AnActionEvent) {
                sortCurrentText(ascending = true)
            }
        }
        val descendingAction = object : DumbAwareAction(
            MyMessageBundle.message("json.sort.descending.tooltip"),
            MyMessageBundle.message("json.sort.descending.tooltip"),
            IconLoader.getIcon("/icons/sortDescending.svg", JsonToolWindowService::class.java)
        ) {
            /**
             * 按 Z-A 方向排序当前编辑器中的所有同级 JSON key。
             *
             * @param event IDEA 动作系统提供的当前上下文
             */
            override fun actionPerformed(event: AnActionEvent) {
                sortCurrentText(ascending = false)
            }
        }
        val actionGroup = DefaultActionGroup().apply {
            // 常用内容操作放在最前方，顺序与原右键菜单保持一致，降低入口迁移后的使用成本。
            add(formatAction)
            add(copyAction)
            add(minifyAction)
            addSeparator()
            add(ascendingAction)
            add(descendingAction)
        }
        val toolbar = actionManager.createActionToolbar(
            "AutoJson.OperationToolbar",
            actionGroup,
            true
        )
        // 指定目标编辑器后，工具栏动作能获得与输入框一致的项目和焦点上下文。
        toolbar.targetComponent = jsonEditor.contentComponent
        return toolbar.component.apply {
            // 小间距只负责分隔工具栏和编辑器，不放大 IDEA 默认的紧凑按钮尺寸。
            border = JBUI.Borders.emptyBottom(4)
        }
    }

    /**
     * 把文档装饰刷新延迟到当前文档事件结束后执行。
     *
     * FoldingModel 不应在 DocumentListener 回调内部直接修改；延迟执行还能合并连续输入事件。
     */
    private fun scheduleDecorationRefresh() {
        if (decorationRefreshScheduled) {
            return
        }
        decorationRefreshScheduled = true

        SwingUtilities.invokeLater {
            decorationRefreshScheduled = false
            // 项目关闭或编辑器已经释放时，丢弃队列中尚未执行的 UI 刷新。
            if (!project.isDisposed && !jsonEditor.isDisposed) {
                refreshEditorDecorations()
            }
        }
    }

    /**
     * 根据当前文档内容刷新 JSON token 颜色以及 Object、Array 折叠区间。
     */
    private fun refreshEditorDecorations() {
        val jsonText = jsonDocument.text
        clearSyntaxHighlighters()
        nestedJsonStrings = applyJsonSyntaxColors(jsonText)
        applyNestedJsonWarnings(nestedJsonStrings)
        // 文档变化会让旧偏移量失效，先重建配对关系，再按变化后的光标位置恢复匹配高亮。
        delimiterPairs = findJsonDelimiterPairs(jsonText)
        delimiterPairsModificationStamp = jsonDocument.modificationStamp
        refreshMatchedDelimiterHighlight()
        rebuildJsonFoldRegions(jsonText)
    }

    /**
     * 清理上一次扫描创建的 token 着色范围。
     */
    private fun clearSyntaxHighlighters() {
        syntaxHighlighters.forEach(jsonEditor.markupModel::removeHighlighter)
        syntaxHighlighters.clear()
    }

    /**
     * 按 JSON token 类型应用 IDEA 当前配色方案中的橙色、紫色和绿色属性。
     *
     * @param jsonText 当前编辑器中的 JSON 文本
     */
    private fun applyJsonSyntaxColors(jsonText: String): List<NestedJsonString> {
        val detectedNestedJsonStrings = mutableListOf<NestedJsonString>()
        var offset = 0
        while (offset < jsonText.length) {
            when {
                jsonText[offset] == '"' -> {
                    // 完整字符串 token 包含引号；冒号前的字符串是 key，其余字符串是 value。
                    val tokenEnd = findStringTokenEnd(jsonText, offset)
                    val isKey = isJsonKey(jsonText, tokenEnd)
                    val colorKey = if (isKey) {
                        DefaultLanguageHighlighterColors.STATIC_FIELD
                    } else {
                        DefaultLanguageHighlighterColors.STRING
                    }
                    // key 只替换为紫色，字体类型、背景和效果均与普通 value 字符串一致。
                    addSyntaxHighlighter(
                        offset,
                        tokenEnd,
                        colorKey,
                        DefaultLanguageHighlighterColors.STRING
                    )
                    // 仅检查 value 字符串；key 即使内容恰好类似 JSON 也不具备可展开语义。
                    if (!isKey) {
                        detectNestedJsonString(jsonText, offset, tokenEnd)?.let(detectedNestedJsonStrings::add)
                    }
                    offset = tokenEnd
                }

                jsonText[offset] == '{' || jsonText[offset] == '}' ||
                    jsonText[offset] == '[' || jsonText[offset] == ']' -> {
                    // 花括号和数组方括号统一使用 IDEA 关键字色，默认深色主题下呈橙色。
                    addSyntaxHighlighter(offset, offset + 1, DefaultLanguageHighlighterColors.KEYWORD)
                    offset++
                }

                isJsonValueStart(jsonText[offset]) -> {
                    // 数字、布尔值与 null 也按 value 的绿色显示，形成统一的视觉层级。
                    val tokenEnd = findValueTokenEnd(jsonText, offset)
                    addSyntaxHighlighter(offset, tokenEnd, DefaultLanguageHighlighterColors.STRING)
                    offset = tokenEnd
                }

                else -> offset++
            }
        }
        return detectedNestedJsonStrings
    }

    /**
     * 判断字符串 value 解码后是否为合法 JSON Object 或 Array。
     *
     * @param jsonText 当前编辑器中的完整 JSON 文本
     * @param startOffset 字符串 value 的起始双引号偏移量
     * @param endOffset 字符串 value 的结束偏移量，不包含该位置字符
     * @return 可展开的字符串子 JSON；普通字符串或不完整 token 返回 null
     */
    private fun detectNestedJsonString(
        jsonText: String,
        startOffset: Int,
        endOffset: Int
    ): NestedJsonString? {
        // 未闭合字符串没有结束双引号，等待用户输入完成后再提供警告和意图动作。
        if (endOffset <= startOffset + 1 || jsonText[endOffset - 1] != '"') {
            return null
        }

        val decodedJson = JsonFormatter.decodeNestedContainerString(
            jsonText.substring(startOffset, endOffset)
        ) ?: return null
        return NestedJsonString(TextRange(startOffset, endOffset), decodedJson)
    }

    /**
     * 为所有可展开的字符串子 JSON 添加 IDEA Warning 风格的黄色波浪线。
     *
     * @param nestedJsonValues 当前文档中识别出的字符串子 JSON
     */
    private fun applyNestedJsonWarnings(nestedJsonValues: List<NestedJsonString>) {
        val warningAttributes = jsonEditor.colorsScheme
            .getAttributes(CodeInsightColors.WARNINGS_ATTRIBUTES)
        nestedJsonValues.forEach { nestedJson ->
            // 只设置波浪线效果，保留字符串 value 已有的绿色前景色和编辑器背景。
            val effectAttributes = com.intellij.openapi.editor.markup.TextAttributes().apply {
                effectType = EffectType.WAVE_UNDERSCORE
                effectColor = warningAttributes.effectColor
                    ?: warningAttributes.foregroundColor
                    ?: JBColor.YELLOW
            }
            val highlighter = jsonEditor.markupModel.addRangeHighlighter(
                nestedJson.textRange.startOffset,
                nestedJson.textRange.endOffset,
                HighlighterLayer.WARNING,
                effectAttributes,
                HighlighterTargetArea.EXACT_RANGE
            )
            highlighter.errorStripeTooltip = MyMessageBundle.message("json.nested.warning")
            syntaxHighlighters += highlighter
        }
    }

    /**
     * 添加一个使用 IDEA 当前颜色方案的 token 着色范围。
     *
     * @param startOffset token 起始偏移量
     * @param endOffset token 结束偏移量，不包含该位置字符
     * @param colorAttributesKey 提供前景色的 IDEA 语义颜色键
     * @param styleAttributesKey 提供字体类型、背景与效果的 IDEA 语义样式键
     */
    private fun addSyntaxHighlighter(
        startOffset: Int,
        endOffset: Int,
        colorAttributesKey: TextAttributesKey,
        styleAttributesKey: TextAttributesKey = colorAttributesKey
    ) {
        if (startOffset >= endOffset) {
            return
        }

        // 先复制样式来源，再仅替换前景色；key 因此不会继承字段色自带的斜体样式。
        val textAttributes = jsonEditor.colorsScheme.getAttributes(styleAttributesKey).clone()
        textAttributes.foregroundColor = jsonEditor.colorsScheme.getAttributes(colorAttributesKey).foregroundColor
        syntaxHighlighters += jsonEditor.markupModel.addRangeHighlighter(
            startOffset,
            endOffset,
            JSON_HIGHLIGHT_LAYER,
            textAttributes,
            HighlighterTargetArea.EXACT_RANGE
        )
    }

    /**
     * 扫描 JSON 文本并建立花括号、方括号的双向配对关系。
     *
     * 字符串中的括号属于 value 内容，不参与结构匹配；输入尚未完成或类型不一致的括号
     * 不会产生配对，避免把光标引向错误的结构边界。
     *
     * @param jsonText 当前编辑器中的完整 JSON 文本
     * @return 以任一括号偏移量为 key、其匹配括号偏移量为 value 的映射
     */
    private fun findJsonDelimiterPairs(jsonText: String): Map<Int, Int> {
        val openingDelimiters = ArrayDeque<OpeningDelimiter>()
        val pairs = mutableMapOf<Int, Int>()
        var inString = false
        var escaped = false

        jsonText.forEachIndexed { offset, currentChar ->
            if (inString) {
                when {
                    // 被反斜杠转义的字符不具备结束字符串或参与括号匹配的语义。
                    escaped -> escaped = false
                    currentChar == '\\' -> escaped = true
                    currentChar == '"' -> inString = false
                }
                return@forEachIndexed
            }

            when (currentChar) {
                '"' -> inString = true
                '{', '[' -> openingDelimiters.addLast(OpeningDelimiter(currentChar, offset))
                '}', ']' -> {
                    if (openingDelimiters.isEmpty()) {
                        return@forEachIndexed
                    }

                    val openingDelimiter = openingDelimiters.peekLast()
                    val expectedOpeningCharacter = if (currentChar == '}') '{' else '['
                    // 只消费类型一致的栈顶括号，保证嵌套层级与 JSON 结构完全对应。
                    if (openingDelimiter.character != expectedOpeningCharacter) {
                        return@forEachIndexed
                    }

                    openingDelimiters.removeLast()
                    // 同时记录两个方向，让开始括号和结束括号都能直接找到对端。
                    pairs[openingDelimiter.offset] = offset
                    pairs[offset] = openingDelimiter.offset
                }
            }
        }
        return pairs
    }

    /**
     * 根据当前光标位置刷新成对 JSON 括号的高亮。
     *
     * IDEA 光标位于字符边界：移动到括号前时偏移量指向括号，刚输入括号后偏移量则
     * 指向下一字符。因此先检查当前位置，再检查前一位置，覆盖键盘输入和鼠标定位两种场景。
     */
    private fun refreshMatchedDelimiterHighlight() {
        clearMatchedDelimiterHighlighters()
        // 文档监听采用异步合并刷新；版本不一致时等待新配对结果，绝不使用旧偏移量绘制。
        if (delimiterPairsModificationStamp != jsonDocument.modificationStamp) {
            return
        }
        val jsonText = jsonDocument.text
        val delimiterOffset = findDelimiterOffsetAtCaret(jsonText) ?: return
        val matchedOffset = delimiterPairs[delimiterOffset] ?: return
        val matchedBraceAttributes = jsonEditor.colorsScheme
            .getAttributes(CodeInsightColors.MATCHED_BRACE_ATTRIBUTES)

        // 两端使用同一主题属性，使当前括号与远端匹配括号形成清晰、对称的视觉提示。
        listOf(delimiterOffset, matchedOffset).forEach { offset ->
            matchedDelimiterHighlighters += jsonEditor.markupModel.addRangeHighlighter(
                offset,
                offset + 1,
                HighlighterLayer.ELEMENT_UNDER_CARET,
                matchedBraceAttributes,
                HighlighterTargetArea.EXACT_RANGE
            )
        }
    }

    /**
     * 查找当前光标所指向的 JSON 结构括号。
     *
     * @param jsonText 当前编辑器中的完整 JSON 文本
     * @return 光标处或光标前已配对括号的偏移量；没有命中时返回 null
     */
    private fun findDelimiterOffsetAtCaret(jsonText: String): Int? {
        val caretOffset = jsonEditor.caretModel.offset
        // 优先选择光标右侧字符，符合鼠标把插入点放到某个符号“上”的直觉。
        if (caretOffset < jsonText.length && delimiterPairs.containsKey(caretOffset)) {
            return caretOffset
        }
        val previousOffset = caretOffset - 1
        // 光标刚输入并越过括号时，回看左侧字符即可保持高亮立即可见。
        return previousOffset.takeIf { offset -> offset >= 0 && delimiterPairs.containsKey(offset) }
    }

    /**
     * 移除上一次光标位置创建的匹配括号高亮。
     */
    private fun clearMatchedDelimiterHighlighters() {
        matchedDelimiterHighlighters.forEach(jsonEditor.markupModel::removeHighlighter)
        matchedDelimiterHighlighters.clear()
    }

    /**
     * 定位 JSON 字符串 token 的结束位置，并正确跳过反斜杠转义的引号。
     *
     * @param jsonText 完整 JSON 文本
     * @param startOffset 字符串起始双引号位置
     * @return token 结束偏移量，不包含该位置字符
     */
    private fun findStringTokenEnd(jsonText: String, startOffset: Int): Int {
        var offset = startOffset + 1
        var escaped = false
        while (offset < jsonText.length) {
            val currentChar = jsonText[offset]
            if (escaped) {
                escaped = false
            } else if (currentChar == '\\') {
                escaped = true
            } else if (currentChar == '"') {
                return offset + 1
            }
            offset++
        }
        // 输入尚未完成时把剩余文本视为一个字符串，仍可提供稳定的编辑体验。
        return jsonText.length
    }

    /**
     * 判断一个字符串 token 后是否紧跟属性分隔符冒号。
     *
     * @param jsonText 完整 JSON 文本
     * @param tokenEnd 字符串 token 结束偏移量
     * @return true 表示当前字符串是 Object key
     */
    private fun isJsonKey(jsonText: String, tokenEnd: Int): Boolean {
        var offset = tokenEnd
        while (offset < jsonText.length && jsonText[offset].isWhitespace()) {
            offset++
        }
        return offset < jsonText.length && jsonText[offset] == ':'
    }

    /**
     * 判断字符是否可能是数字、布尔值或 null 的起始字符。
     *
     * @param currentChar 当前扫描字符
     * @return true 表示需要按非字符串 value 扫描
     */
    private fun isJsonValueStart(currentChar: Char): Boolean {
        return currentChar == '-' || currentChar.isDigit() ||
            currentChar == 't' || currentChar == 'f' || currentChar == 'n'
    }

    /**
     * 定位数字、布尔值或 null token 的结束位置。
     *
     * @param jsonText 完整 JSON 文本
     * @param startOffset value token 起始偏移量
     * @return token 结束偏移量，不包含该位置字符
     */
    private fun findValueTokenEnd(jsonText: String, startOffset: Int): Int {
        var offset = startOffset
        while (offset < jsonText.length) {
            val currentChar = jsonText[offset]
            if (currentChar.isWhitespace() || currentChar == ',' || currentChar == '}' || currentChar == ']') {
                break
            }
            offset++
        }
        return offset
    }

    /**
     * 重新创建多行 JSON Object 和 Array 的折叠区间。
     *
     * @param jsonText 当前编辑器中的 JSON 文本
     */
    private fun rebuildJsonFoldRegions(jsonText: String) {
        val foldRanges = findMultilineJsonFoldRanges(jsonText).sortedBy { it.textRange.startOffset }
        jsonEditor.foldingModel.runBatchFoldingOperation {
            // 文档变化后旧偏移量已经失效，需要先移除旧区间再按新文本重新创建。
            jsonEditor.foldingModel.allFoldRegions.forEach(jsonEditor.foldingModel::removeFoldRegion)
            foldRanges.forEach { foldRange ->
                jsonEditor.foldingModel.addFoldRegion(
                    foldRange.textRange.startOffset,
                    foldRange.textRange.endOffset,
                    foldRange.placeholder
                )
            }
        }
    }

    /**
     * 扫描多行 Object 与 Array 的括号范围，并忽略 JSON 字符串内部的括号字符。
     *
     * @param jsonText 当前编辑器中的 JSON 文本
     * @return 可安全注册到 FoldingModel 的 Object 与 Array 折叠范围
     */
    private fun findMultilineJsonFoldRanges(jsonText: String): List<JsonFoldRange> {
        val openingDelimiters = ArrayDeque<OpeningDelimiter>()
        val foldRanges = mutableListOf<JsonFoldRange>()
        var inString = false
        var escaped = false

        jsonText.forEachIndexed { offset, currentChar ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    currentChar == '\\' -> escaped = true
                    currentChar == '"' -> inString = false
                }
                return@forEachIndexed
            }

            when (currentChar) {
                '"' -> inString = true
                '{', '[' -> openingDelimiters.addLast(OpeningDelimiter(currentChar, offset))
                ',' -> countCurrentArrayElementSeparator(openingDelimiters)
                '}', ']' -> addFoldRangeForClosingDelimiter(
                    jsonText,
                    currentChar,
                    offset,
                    openingDelimiters,
                    foldRanges
                )
            }
        }
        return foldRanges
    }

    /**
     * 记录当前最内层 Array 的顶层元素分隔逗号。
     *
     * 只有 Array 位于栈顶时才计数，因此嵌套 Object 或子 Array 内的逗号不会影响父集合数量。
     *
     * @param openingDelimiters 尚未闭合的起始括号栈
     */
    private fun countCurrentArrayElementSeparator(openingDelimiters: ArrayDeque<OpeningDelimiter>) {
        if (openingDelimiters.isNotEmpty() && openingDelimiters.peekLast().character == '[') {
            openingDelimiters.peekLast().arrayCommaCount++
        }
    }

    /**
     * 将匹配且跨行的结束括号转换为 Object 或 Array 折叠范围。
     *
     * @param jsonText 当前编辑器中的 JSON 文本
     * @param closingCharacter 当前扫描到的结束花括号或方括号
     * @param closingOffset 结束括号在文档中的偏移量
     * @param openingDelimiters 尚未闭合的起始括号栈
     * @param foldRanges 扫描过程中收集的折叠范围
     */
    private fun addFoldRangeForClosingDelimiter(
        jsonText: String,
        closingCharacter: Char,
        closingOffset: Int,
        openingDelimiters: ArrayDeque<OpeningDelimiter>,
        foldRanges: MutableList<JsonFoldRange>
    ) {
        if (openingDelimiters.isEmpty()) {
            return
        }

        val openingDelimiter = openingDelimiters.peekLast()
        val expectedOpeningCharacter = if (closingCharacter == '}') '{' else '['
        // 括号类型不匹配说明当前输入尚未形成合法结构，保留栈等待用户继续修正。
        if (openingDelimiter.character != expectedOpeningCharacter) {
            return
        }

        openingDelimiters.removeLast()
        val endOffset = closingOffset + 1
        // gutter 只显示跨行结构；格式化后 Object 和 Array 会自然变为多行。
        if (!containsLineBreak(jsonText, openingDelimiter.offset, endOffset)) {
            return
        }

        val placeholder = if (openingDelimiter.character == '{') {
            OBJECT_FOLD_PLACEHOLDER
        } else {
            createArrayFoldPlaceholder(jsonText, openingDelimiter, closingOffset)
        }
        foldRanges += JsonFoldRange(
            TextRange(openingDelimiter.offset, endOffset),
            placeholder
        )
    }

    /**
     * 创建包含当前 Array 顶层元素数量的折叠占位符。
     *
     * @param jsonText 当前编辑器中的 JSON 文本
     * @param openingDelimiter Array 起始方括号及顶层逗号计数
     * @param closingOffset Array 结束方括号的偏移量
     * @return 形如 `[...,5个]` 的折叠提示
     */
    private fun createArrayFoldPlaceholder(
        jsonText: String,
        openingDelimiter: OpeningDelimiter,
        closingOffset: Int
    ): String {
        val hasElement = (openingDelimiter.offset + 1 until closingOffset)
            .any { offset -> !jsonText[offset].isWhitespace() }
        // 非空数组的元素数量等于顶层分隔逗号数量加一，空数组则固定为零。
        val elementCount = if (hasElement) openingDelimiter.arrayCommaCount + 1 else 0
        return "[...${elementCount}个]"
    }

    /**
     * 判断指定 Object 或 Array 范围内是否包含换行符。
     *
     * @param text 完整 JSON 文本
     * @param startOffset 结构起始偏移量
     * @param endOffset 结构结束偏移量，不包含该位置字符
     * @return true 表示该结构跨越多行并适合显示 gutter 折叠标记
     */
    private fun containsLineBreak(text: String, startOffset: Int, endOffset: Int): Boolean {
        for (offset in startOffset until endOffset) {
            if (text[offset] == '\n' || text[offset] == '\r') {
                return true
            }
        }
        return false
    }
}
