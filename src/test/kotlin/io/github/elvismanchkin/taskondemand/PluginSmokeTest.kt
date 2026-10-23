package io.github.elvismanchkin.taskondemand

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import io.github.elvismanchkin.taskondemand.jira.CurrentIssueService
import io.github.elvismanchkin.taskondemand.settings.ProjectMappingConfigurable
import io.github.elvismanchkin.taskondemand.settings.ProjectSettings
import io.github.elvismanchkin.taskondemand.settings.ServersConfigurable
import io.github.elvismanchkin.taskondemand.ui.ConfluencePanel
import io.github.elvismanchkin.taskondemand.ui.CurrentIssueWidget
import io.github.elvismanchkin.taskondemand.ui.JiraPanel
import io.github.elvismanchkin.taskondemand.ui.ObservabilityPanel

/** Loads the plugin in a headless IDE: checks plugin.xml wiring and that the UI builds without servers configured. */
class PluginSmokeTest : BasePlatformTestCase() {

    fun testActionsAreRegistered() {
        val manager = ActionManager.getInstance()
        listOf(
            "TaskOnDemand.Group", "TaskOnDemand.OpenCurrentIssue", "TaskOnDemand.GoToIssue", "TaskOnDemand.CreateIssue",
            "TaskOnDemand.TransitionIssue", "TaskOnDemand.RefreshCurrentIssue", "TaskOnDemand.OpenLogs",
            "TaskOnDemand.OpenDashboard", "TaskOnDemand.ConfluencePages", "TaskOnDemand.Settings",
            "TaskOnDemand.SearchLogsForSelection",
        ).forEach { assertNotNull("missing action $it", manager.getAction(it)) }
    }

    fun testStatusBarWidgetIsRegistered() {
        assertTrue(StatusBarWidgetFactory.EP_NAME.extensionList.any { it.id == CurrentIssueWidget.ID })
        val widget = CurrentIssueWidget(project)
        assertEquals("No issue", widget.getSelectedValue())
        Disposer.dispose(widget)
    }

    fun testSettingsPagesBuildAndResetCleanly() {
        val servers = ServersConfigurable()
        servers.createComponent()
        servers.reset()
        assertFalse(servers.isModified)

        ProjectSettings.getInstance(project).state.applications += ProjectSettings.AppMapping().apply { name = "orders" }
        val mapping = ProjectMappingConfigurable(project)
        mapping.createComponent()
        mapping.reset()
        assertFalse(mapping.isModified)
    }

    fun testPanelsBuildWithoutServers() {
        listOf(JiraPanel(project), ConfluencePanel(project)).forEach { Disposer.dispose(it) }
        ObservabilityPanel(project)
    }

    fun testNoIssueWithoutGitRepository() {
        CurrentIssueService.getInstance(project).refresh(force = true)
        assertNull(CurrentIssueService.getInstance(project).snapshot.issueKey)
    }

    fun testProjectSettingsSerializeApplications() {
        val state = ProjectSettings.State().apply {
            jiraProjectKeys = "PROJ, OPS"
            applications += ProjectSettings.AppMapping().apply {
                name = "orders"
                kibanaQuery = "application:\"orders\""
                grafanaVariables = "service=orders\nenv=dev"
            }
        }
        val restored = XmlSerializer.deserialize(XmlSerializer.serialize(state), ProjectSettings.State::class.java)
        assertEquals("PROJ, OPS", restored.jiraProjectKeys)
        assertEquals("application:\"orders\"", restored.applications.single().kibanaQuery)
        assertEquals("service=orders\nenv=dev", restored.applications.single().grafanaVariables)
    }
}
