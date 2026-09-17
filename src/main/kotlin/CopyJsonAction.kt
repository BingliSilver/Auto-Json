package com.LazeroX

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

/**
 * 处理 JSON 工具窗顶部工具栏中的一键复制操作。
 */
internal class CopyJsonAction : AnAction() {
    /**
     * 仅在存在项目上下文时启用复制动作。
     *
     * @param event IDEA 动作系统提供的当前上下文
     */
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }

    /**
     * 将当前项目 JSON 工具窗中的完整内容复制到系统剪贴板。
     *
     * @param event IDEA 动作系统提供的项目上下文
     */
    override fun actionPerformed(event: AnActionEvent) {
        // 服务内部会处理空内容，此处只负责把工具栏动作转发给项目级服务。
        val project = event.project ?: return
        project.service<JsonToolWindowService>().copyCurrentText()
    }
}
