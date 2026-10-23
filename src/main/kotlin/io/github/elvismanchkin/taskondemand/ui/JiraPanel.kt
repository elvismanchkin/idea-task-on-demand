package io.github.elvismanchkin.taskondemand.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import io.github.elvismanchkin.taskondemand.core.Clients
import io.github.elvismanchkin.taskondemand.core.ServerKind
import io.github.elvismanchkin.taskondemand.core.runInBackground
import io.github.elvismanchkin.taskondemand.core.userMessage
import io.github.elvismanchkin.taskondemand.jira.CreateIssueDialog
import io.github.elvismanchkin.taskondemand.jira.CurrentIssueService
import io.github.elvismanchkin.taskondemand.jira.IssueKeys
import io.github.elvismanchkin.taskondemand.jira.JiraIssue
import io.github.elvismanchkin.taskondemand.settings.AppSettings
import io.github.elvismanchkin.taskondemand.settings.ProjectSettings
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/** Jira tab: issues from the project's JQL, with the current branch's issue highlighted. */
class JiraPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val settings = ProjectSettings.getInstance(project)
    private val jqlField = JBTextField()
    private val currentLabel = JBLabel()
    private val model: ListTableModel<JiraIssue> = ListTableModel(
        column("Key", 90) { it.key },
        column("Summary", 420) { it.summary },
        column("Status", 110) { it.status },
        column("Type", 90) { it.issueType },
        column("Assignee", 130) { it.assignee ?: "" },
        column("Priority", 80) { it.priority ?: "" },
    )
    private val table: TableView<JiraIssue> = TableView(model)
    /** Bumped per search; only the newest answer is shown, and nothing after the tab is closed. */
    private var generation = 0
    private var disposed = false

    init {
        jqlField.emptyText.text = "JQL (Enter to search)"
        jqlField.addActionListener {
            settings.state.jiraJql = jqlField.text.trim().takeIf { it != IssueKeys.defaultJql(settings.projectKeys) }.orEmpty()
            reload()
        }
        table.setShowGrid(false)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                table.selectedObject?.let { Navigator.openIssue(project, it.key) } ?: return false
                return true
            }
        }.installOn(table)

        val toolbar = toolbar(
            "TaskOnDemand.Jira", this,
            panelAction("Refresh", AllIcons.Actions.Refresh) { reload(); CurrentIssueService.getInstance(project).refresh(force = true) },
            panelAction("Create Issue…", AllIcons.General.Add) { CreateIssueDialog.show(project) { reload() } },
            panelAction("Open in Browser", AllIcons.General.Web, { table.selectedObject != null }) {
                table.selectedObject?.let { Navigator.openIssue(project, it.key) }
            },
            Separator.getInstance(),
            ActionManager.getInstance().getAction("TaskOnDemand.TransitionIssue"),
            panelAction("Settings", AllIcons.General.Settings) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "Task on Demand")
            },
        )
        val top = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(jqlField, BorderLayout.CENTER)
            add(currentLabel.apply { border = JBUI.Borders.empty(0, 8) }, BorderLayout.SOUTH)
        }
        add(top, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)

        project.messageBus.connect(this).subscribe(CurrentIssueService.TOPIC, CurrentIssueService.Listener { showCurrent(it) })
        showCurrent(CurrentIssueService.getInstance(project).snapshot)
        reload()
    }

    private fun effectiveJql(): String = settings.state.jiraJql.ifBlank { IssueKeys.defaultJql(settings.projectKeys) }

    fun reload() {
        val request = ++generation
        jqlField.text = effectiveJql()
        if (!AppSettings.getInstance().isConfigured(ServerKind.JIRA)) {
            table.emptyText.text = "Jira server is not configured (Settings | Tools | Task on Demand)"
            return
        }
        table.emptyText.text = "Loading…"
        val jql = effectiveJql()
        runInBackground(
            project, null,
            work = { Clients.jira().search(jql) },
            onError = {
                if (isCurrent(request)) {
                    model.items = emptyList()
                    table.emptyText.text = it.userMessage()
                }
            },
            onSuccess = { issues ->
                if (!isCurrent(request)) return@runInBackground
                model.items = issues
                table.emptyText.text = "No issues match the JQL"
            },
        )
    }

    private fun showCurrent(s: CurrentIssueService.Snapshot) {
        currentLabel.text = when {
            s.issueKey == null -> "Branch '${s.branch ?: "—"}' has no issue key"
            s.issue != null -> "Current: ${s.issue.key} — ${s.issue.summary} [${s.issue.status}]"
            s.error != null -> "Current: ${s.issueKey} (${s.error})"
            else -> "Current: ${s.issueKey}"
        }
        table.repaint()
    }

    private fun isCurrent(request: Int) = !disposed && request == generation

    override fun dispose() {
        disposed = true
    }

    private fun column(name: String, width: Int, value: (JiraIssue) -> String): ColumnInfo<JiraIssue, String> = object : ColumnInfo<JiraIssue, String>(name) {
        override fun valueOf(item: JiraIssue): String = value(item)
        override fun getPreferredStringValue(): String = "M".repeat(width / 10)
        override fun getRenderer(item: JiraIssue): TableCellRenderer = currentIssueRenderer
    }

    /** Bold for the issue of the current branch. */
    private val currentIssueRenderer: TableCellRenderer by lazy {
        object : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, false, row, column)
            val current = CurrentIssueService.getInstance(project).snapshot.issueKey
            val isCurrent = model.getItem(this@JiraPanel.table.convertRowIndexToModel(row)).key == current
            component.font = component.font.deriveFont(if (isCurrent) Font.BOLD else Font.PLAIN)
            return component
        }
        }
    }
}
