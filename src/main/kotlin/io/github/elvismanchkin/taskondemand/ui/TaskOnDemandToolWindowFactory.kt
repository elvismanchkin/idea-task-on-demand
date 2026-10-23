package io.github.elvismanchkin.taskondemand.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class TaskOnDemandToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()
        val jira = JiraPanel(project)
        val confluence = ConfluencePanel(project)
        toolWindow.contentManager.addContent(factory.createContent(jira, "Jira", false).also { Disposer.register(it, jira) })
        toolWindow.contentManager.addContent(factory.createContent(ObservabilityPanel(project), "Logs & Metrics", false))
        toolWindow.contentManager.addContent(factory.createContent(confluence, "Confluence", false).also { Disposer.register(it, confluence) })
    }
}
