package cn.uliang

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * 响应 JSON 格式化快捷键。
 *
 * 读取代码编辑器、运行控制台或终端日志中的选中文本，格式化后输出到右侧工具窗。
 * 终端组件通过 IDEA 通用复制接口提供选区，因此不依赖具体终端实现。
 */
internal class FormatJsonAction : AnAction() {
    /**
     * 仅在存在项目上下文时启用动作，因为工具窗状态按项目保存。
     *
     * @param event IDEA 动作系统提供的当前上下文
     */
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }

    /**
     * 格式化当前焦点组件中的选中文本，并将结果输出到 JSON 工具窗。
     *
     * @param event IDEA 动作系统提供的项目和编辑器上下文
     */
    override fun actionPerformed(event: AnActionEvent) {
        // 没有项目时 update 已禁用动作；此处仍安全返回以防动作被代码直接调用。
        val project = event.project ?: return
        val jsonToolWindowService = project.service<JsonToolWindowService>()
        val selectedText = getSelectedText(event)

        if (!selectedText.isNullOrBlank()) {
            // 选区先经过 JSON 语法校验，只有合法 JSON 才覆盖工具窗中的旧结果。
            when (val result = JsonFormatter.format(selectedText)) {
                is JsonFormatResult.Success -> jsonToolWindowService.setFormattedText(result.formattedJson)
                // 格式化失败时保留工具窗中的旧 JSON，不再显示顶部或底部提示。
                is JsonFormatResult.Failure -> Unit
            }
        }

        // 无论是否存在有效选区都显示工具窗，方便用户继续在输入框内粘贴或编辑 JSON。
        ToolWindowManager.getInstance(project)
            .getToolWindow(JsonToolWindowService.TOOL_WINDOW_ID)
            ?.show(null)
    }

    /**
     * 获取当前代码编辑器、运行控制台或终端中的选中文本。
     *
     * @param event IDEA 动作系统提供的数据上下文
     * @return 当前选中的文本；没有有效选区时返回 null
     */
    private fun getSelectedText(event: AnActionEvent): String? {
        // 代码编辑器和基于 Editor 实现的控制台可以直接读取选区，无需触碰系统剪贴板。
        val editorSelectedText = event.getData(CommonDataKeys.EDITOR)
            ?.selectionModel
            ?.selectedText
        if (!editorSelectedText.isNullOrBlank()) {
            return editorSelectedText
        }

        // 传统终端不一定暴露 Editor，但会通过 CopyProvider 提供与 IDEA“复制”动作相同的选区内容。
        val copyProvider = event.getData(PlatformDataKeys.COPY_PROVIDER) ?: return null
        if (!copyProvider.isCopyEnabled(event.dataContext)) {
            return null
        }

        val copyPasteManager = CopyPasteManager.getInstance()
        // 读取前保存原剪贴板对象，确保快捷键不会破坏用户之前复制的内容。
        val previousClipboardContent = copyPasteManager.contents

        return try {
            // IDEA 的复制提供器会同步写入当前选区，适用于终端、控制台等非 Editor 组件。
            copyProvider.performCopy(event.dataContext)
            copyPasteManager.getContents<String>(DataFlavor.stringFlavor)
        } finally {
            // 恢复快捷键执行前的内容；原剪贴板为空时恢复为空字符串，避免终端日志残留在剪贴板。
            copyPasteManager.setContents(previousClipboardContent ?: StringSelection(""))
        }
    }
}
