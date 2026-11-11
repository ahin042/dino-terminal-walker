package io.github.ahin042.dinowalker

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class DinoToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun init(toolWindow: ToolWindow) {
        toolWindow.setIcon(DinoSprites.HeadIcon)
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = DinoProjectService.getInstance(project)
        val panel = DinoPanel(onDeathFinished = service::onDeathFinished)
        val content = ContentFactory.getInstance().createContent(panel, "", false).apply {
            isCloseable = false
            setDisposer(panel)
        }
        toolWindow.contentManager.addContent(content)
        service.attachPanel(panel)
    }

    companion object {
        const val ID = "Dino"
    }
}
