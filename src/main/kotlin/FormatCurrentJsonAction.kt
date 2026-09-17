package com.LazeroX

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

/**
 * 处理 JSON 工具窗编辑器右键菜单中的格式化操作。
 */
internal class FormatCurrentJsonAction : AnAction() {
    /**
     * 仅在存在项目上下文时启用格式化动作。
     *
     * @param event IDEA 动作系统提供的当前上下文
     */
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }

    /**
     * 格式化当前项目 JSON 工具窗中的完整内容。
     *
     * @param event IDEA 动作系统提供的项目上下文
     */
    override fun actionPerformed(event: AnActionEvent) {
        // 右键菜单只绑定到 JSON 工具窗编辑器，无项目时仍安全返回以兼容项目关闭过程。
        val project = event.project ?: return
        project.service<JsonToolWindowService>().formatCurrentText()
    }
}
