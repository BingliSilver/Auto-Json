package com.LazeroX

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

/**
 * 处理 JSON 工具窗顶部工具栏中的 Minify JSON 操作。
 */
internal class MinifyJsonAction : AnAction() {
    /**
     * 仅在存在项目上下文时启用压缩动作。
     *
     * @param event IDEA 动作系统提供的当前上下文
     */
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }

    /**
     * 将当前项目 JSON 工具窗中的完整内容压缩为单行 JSON。
     *
     * @param event IDEA 动作系统提供的项目上下文
     */
    override fun actionPerformed(event: AnActionEvent) {
        // 该动作仅由项目级 JSON 工具栏触发，无项目时安全返回。
        val project = event.project ?: return
        project.service<JsonToolWindowService>().minifyCurrentText()
    }
}
