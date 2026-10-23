package io.github.elvismanchkin.taskondemand.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import io.github.elvismanchkin.taskondemand.settings.ProjectSettings
import io.github.elvismanchkin.taskondemand.settings.ProjectSettings.AppMapping
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel

/** Logs & Metrics tab: the project's applications, each with its Kibana logs and Grafana dashboard. */
class ObservabilityPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val settings = ProjectSettings.getInstance(project)
    private val model = DefaultListModel<AppMapping>()
    private val list = JBList(model)

    init {
        list.cellRenderer = object : ColoredListCellRenderer<AppMapping>() {
            override fun customizeCellRenderer(list: JList<out AppMapping>, value: AppMapping, index: Int, selected: Boolean, hasFocus: Boolean) {
                append(value.toString())
                val parts = listOfNotNull(
                    value.kibanaIndexPatternTitle.ifBlank { null }?.let { "logs: $it ${value.kibanaQuery}".trim() },
                    value.grafanaDashboardTitle.ifBlank { value.grafanaDashboardUid.ifBlank { null } }?.let { "dashboard: $it" },
                )
                append("   " + parts.joinToString(" · "), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        list.emptyText.text = "No applications mapped"
        list.emptyText.appendSecondaryText("Configure…", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { openSettings() }
        list.addListSelectionListener { list.selectedValue?.let { settings.state.selectedApplication = it.name } }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                Navigator.openLogs(project, list.selectedValue ?: return false)
                return true
            }
        }.installOn(list)

        val toolbar = toolbar(
            "TaskOnDemand.Observability", this,
            panelAction("Open Logs in Kibana", AllIcons.Debugger.Console, { list.selectedValue?.hasKibana == true }) {
                Navigator.openLogs(project, list.selectedValue)
            },
            panelAction("Open Grafana Dashboard", AllIcons.Actions.ProfileCPU, { list.selectedValue?.hasGrafana == true }) {
                Navigator.openDashboard(project, list.selectedValue)
            },
            panelAction("Reload", AllIcons.Actions.Refresh) { reload() },
            panelAction("Edit Mappings…", AllIcons.General.Settings) { openSettings() },
        )
        add(toolbar.component, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER)
        reload()
    }

    fun reload() {
        model.clear()
        settings.state.applications.forEach(model::addElement)
        list.setSelectedValue(settings.activeApplication, true)
    }

    private fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "Project Mapping")
        reload()
    }
}
