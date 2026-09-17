package com.LazeroX

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * 创建右侧 JSON 序列化工具窗，并把项目级 JSON 编辑组件挂载到工具窗内容区。
 */
internal class JsonToolWindowFactory : ToolWindowFactory {
    /**
     * JSON 工具窗对所有已打开项目始终可用。
     *
     * @param project 当前 IDEA 项目
     * @return 固定返回 true
     */
    override fun shouldBeAvailable(project: Project): Boolean = true

    /**
     * 初始化工具窗内容。
     *
     * @param project 当前 IDEA 项目，用于获取项目隔离的工具窗服务
     * @param toolWindow IDEA 已注册的 JSON 工具窗实例
     */
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // 项目服务持有输入状态，因此工具窗重新展示时不会丢失用户内容。
        val jsonToolWindowService = project.service<JsonToolWindowService>()
        val content = ContentFactory.getInstance().createContent(
            jsonToolWindowService.getContent(),
            null,
            false
        )
        toolWindow.contentManager.addContent(content)
    }
}
