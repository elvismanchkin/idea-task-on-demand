package io.github.elvismanchkin.taskondemand.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.awt.RelativePoint
import io.github.elvismanchkin.taskondemand.core.Clients
import io.github.elvismanchkin.taskondemand.core.Notifier
import io.github.elvismanchkin.taskondemand.core.ServerKind
import io.github.elvismanchkin.taskondemand.core.runInBackground
import io.github.elvismanchkin.taskondemand.core.userMessage
import io.github.elvismanchkin.taskondemand.jira.CreateIssueDialog
import io.github.elvismanchkin.taskondemand.jira.CurrentIssueService
import io.github.elvismanchkin.taskondemand.jira.IssueKeys
import io.github.elvismanchkin.taskondemand.jira.JiraTransition
import io.github.elvismanchkin.taskondemand.settings.AppSettings
import io.github.elvismanchkin.taskondemand.ui.Navigator

/** Base class: enabled only with a project; actions run their own background work. */
abstract class ProjectAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    final override fun actionPerformed(e: AnActionEvent) {
        perform(e.project ?: return, e)
    }

    abstract fun perform(project: Project, e: AnActionEvent)

    protected fun popupPoint(e: AnActionEvent): RelativePoint? =
        runCatching { JBPopupFactory.getInstance().guessBestPopupLocation(e.dataContext) }.getOrNull()

    protected fun currentKey(project: Project) = CurrentIssueService.getInstance(project).snapshot.issueKey
}

class OpenCurrentIssueAction : ProjectAction() {
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && currentKey(project) != null
        e.project?.let { currentKey(it) }?.let { e.presentation.text = "Open $it in Browser" }
    }

    override fun perform(project: Project, e: AnActionEvent) {
        currentKey(project)?.let { Navigator.openIssue(project, it) }
    }
}

class GoToIssueAction : ProjectAction() {
    override fun perform(project: Project, e: AnActionEvent) {
        val key = Messages.showInputDialog(
            project, "Issue key:", "Go to Jira Issue", null, currentKey(project).orEmpty(), null,
        )?.trim()?.uppercase() ?: return
        if (!IssueKeys.isValidKey(key)) return Notifier.error(project, "'$key' is not a Jira issue key")
        Navigator.openIssue(project, key)
    }
}

class CreateIssueAction : ProjectAction() {
    override fun perform(project: Project, e: AnActionEvent) {
        if (!AppSettings.getInstance().isConfigured(ServerKind.JIRA)) return Notifier.notConfigured(project, "Jira")
        CreateIssueDialog.show(project)
    }
}

class RefreshCurrentIssueAction : ProjectAction() {
    override fun perform(project: Project, e: AnActionEvent) = CurrentIssueService.getInstance(project).refresh(force = true)
}

class TransitionIssueAction : ProjectAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { currentKey(it) } != null
    }

    override fun perform(project: Project, e: AnActionEvent) {
        val key = currentKey(project) ?: return
        val where = popupPoint(e)
        runInBackground(project, "Loading transitions", { Clients.jira().transitions(key) }, { Notifier.error(project, it.userMessage()) }) { transitions ->
            if (transitions.isEmpty()) return@runInBackground Notifier.info(project, "No transitions available for $key")
            val popup = JBPopupFactory.getInstance().createListPopup(object : BaseListPopupStep<JiraTransition>("Move $key To", transitions) {
                override fun onChosen(selectedValue: JiraTransition, finalChoice: Boolean): PopupStep<*>? = doFinalStep {
                    runInBackground(project, "Transitioning $key", { Clients.jira().transition(key, selectedValue.id) }, { Notifier.error(project, it.userMessage()) }) {
                        CurrentIssueService.getInstance(project).refresh(force = true)
                    }
                }
            })
            if (where != null) popup.show(where) else popup.showCenteredInCurrentWindow(project)
        }
    }
}

class OpenLogsAction : ProjectAction() {
    override fun perform(project: Project, e: AnActionEvent) {
        Navigator.withApplication(project, popupPoint(e), { it.hasKibana }) { Navigator.openLogs(project, it) }
    }
}

/** Editor context menu: searches the application's logs for the selected text (exception message, trace id, ...). */
class SearchLogsForSelectionAction : ProjectAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true
    }

    override fun perform(project: Project, e: AnActionEvent) {
        val text = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText?.lineSequence()?.firstOrNull()?.take(200)
        Navigator.withApplication(project, popupPoint(e), { it.hasKibana }) { Navigator.openLogs(project, it, text) }
    }
}

class OpenDashboardAction : ProjectAction() {
    override fun perform(project: Project, e: AnActionEvent) {
        Navigator.withApplication(project, popupPoint(e), { it.hasGrafana }) { Navigator.openDashboard(project, it) }
    }
}

class ShowConfluencePagesAction : ProjectAction() {
    override fun perform(project: Project, e: AnActionEvent) {
        val where = popupPoint(e) ?: return
        Navigator.showPagesPopup(project, currentKey(project), where)
    }
}

class OpenSettingsAction : ProjectAction() {
    override fun perform(project: Project, e: AnActionEvent) =
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "Task on Demand")
}
