package io.github.elvismanchkin.taskondemand.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.XCollection

/**
 * Project mapping to Jira/Confluence/Kibana/Grafana. Stored in `.idea/taskOnDemand.xml`, which can be committed
 * so the whole team shares it. It contains no URLs or credentials.
 */
@Service(Service.Level.PROJECT)
@State(name = "TaskOnDemandProject", storages = [Storage("taskOnDemand.xml")])
class ProjectSettings : PersistentStateComponent<ProjectSettings.State> {

    class State {
        /** Comma-separated Jira project keys, e.g. `PROJ, OPS`. */
        var jiraProjectKeys: String = ""
        /** Custom JQL for the task list; empty means [io.github.elvismanchkin.taskondemand.jira.IssueKeys.defaultJql]. */
        var jiraJql: String = ""
        /** Optional regex to extract the issue key from a branch name (group 1, or the whole match). */
        var branchIssuePattern: String = ""
        var defaultIssueType: String = ""
        var prefixCommitMessage: Boolean = true
        var registerIssueNavigation: Boolean = true

        var confluenceSpaceKey: String = ""
        /** Comma-separated Confluence page IDs that always show for this project. */
        var confluencePageIds: String = ""
        var scanRepositoryForConfluenceLinks: Boolean = true

        @get:XCollection(style = XCollection.Style.v2)
        var applications: MutableList<AppMapping> = mutableListOf()
        var selectedApplication: String = ""
    }

    /** One deployable application of this project (a repo may hold several services). */
    class AppMapping {
        var name: String = ""

        var kibanaIndexPatternId: String = ""
        var kibanaIndexPatternTitle: String = ""
        /** KQL (or Lucene on Kibana < 6.3) that selects this application's logs, e.g. `application:"orders"`. */
        var kibanaQuery: String = ""
        var kibanaTimeFrom: String = "now-1h"

        var grafanaDashboardUid: String = ""
        var grafanaDashboardSlug: String = ""
        var grafanaDashboardTitle: String = ""
        var grafanaOrgId: String = ""
        /** One `name=value` per line; becomes `var-name=value` URL parameters. */
        var grafanaVariables: String = ""
        var grafanaTimeFrom: String = "now-6h"

        fun copy(): AppMapping = AppMapping().also { XmlSerializerUtil.copyBean(this, it) }

        val hasKibana: Boolean get() = kibanaIndexPatternId.isNotBlank() || kibanaQuery.isNotBlank()
        val hasGrafana: Boolean get() = grafanaDashboardUid.isNotBlank() || grafanaDashboardSlug.isNotBlank()

        override fun toString(): String = name.ifBlank { "(unnamed)" }
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    val projectKeys: List<String>
        get() = splitList(state.jiraProjectKeys).map { it.uppercase() }

    val pinnedConfluencePageIds: List<String>
        get() = splitList(state.confluencePageIds)

    /** The selected application, or the only one, or the first. */
    val activeApplication: AppMapping?
        get() = state.applications.firstOrNull { it.name == state.selectedApplication } ?: state.applications.firstOrNull()

    companion object {
        fun getInstance(project: Project): ProjectSettings = project.getService(ProjectSettings::class.java)

        fun splitList(value: String): List<String> = value.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
