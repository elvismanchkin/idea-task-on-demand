package io.github.elvismanchkin.taskondemand.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import io.github.elvismanchkin.taskondemand.jira.CurrentIssueService

/** Status bar: `PROJ-123 · In Progress`. Clicking it opens the Task on Demand actions. */
class CurrentIssueWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.MultipleTextValuesPresentation {

    private var statusBar: StatusBar? = null

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        project.messageBus.connect(this).subscribe(
            CurrentIssueService.TOPIC,
            CurrentIssueService.Listener { statusBar.updateWidget(ID) },
        )
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getSelectedValue(): String {
        val snapshot = CurrentIssueService.getInstance(project).snapshot
        val key = snapshot.issueKey ?: return "No issue"
        val status = snapshot.issue?.status ?: if (snapshot.loading) "…" else null
        return if (status.isNullOrBlank()) key else "$key · $status"
    }

    override fun getTooltipText(): String {
        val s = CurrentIssueService.getInstance(project).snapshot
        return when {
            s.issueKey == null -> "No Jira issue in branch '${s.branch ?: "?"}'"
            s.error != null -> "${s.issueKey}: ${s.error}"
            s.issue != null -> "${s.issue.key}: ${s.issue.summary} (${s.issue.issueType}, ${s.issue.assignee ?: "unassigned"})"
            else -> s.issueKey
        }
    }

    override fun getPopup(): JBPopup? {
        val group = ActionManager.getInstance().getAction("TaskOnDemand.Group") as? ActionGroup ?: return null
        val context = SimpleDataContext.getProjectContext(project)
        return JBPopupFactory.getInstance().createActionGroupPopup(
            getSelectedValue(), group, context, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true,
        )
    }

    override fun dispose() {
        statusBar = null
    }

    companion object {
        const val ID = "TaskOnDemand.CurrentIssue"
    }
}

class CurrentIssueWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = CurrentIssueWidget.ID
    override fun getDisplayName(): String = "Current Jira Issue"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = CurrentIssueWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
