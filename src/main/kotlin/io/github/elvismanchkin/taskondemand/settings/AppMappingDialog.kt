package io.github.elvismanchkin.taskondemand.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.elvismanchkin.taskondemand.core.Clients
import io.github.elvismanchkin.taskondemand.core.ServerKind
import io.github.elvismanchkin.taskondemand.core.runInBackground
import io.github.elvismanchkin.taskondemand.core.userMessage
import io.github.elvismanchkin.taskondemand.grafana.GrafanaDashboard
import io.github.elvismanchkin.taskondemand.kibana.KibanaIndexPattern
import io.github.elvismanchkin.taskondemand.settings.ProjectSettings.AppMapping
import javax.swing.JButton
import javax.swing.JComponent

/** Edits one application's Kibana and Grafana mapping. Lists index patterns and dashboards from the servers. */
class AppMappingDialog(private val project: Project?, private val mapping: AppMapping) : DialogWrapper(project) {

    private val name = JBTextField(mapping.name)

    private val indexPatternModel = CollectionComboBoxModel<KibanaIndexPattern>()
    private val indexPattern = ComboBox(indexPatternModel)
    private val kibanaQuery = JBTextField(mapping.kibanaQuery)
    private val kibanaTime = JBTextField(mapping.kibanaTimeFrom)
    private val kibanaStatus = JBLabel()

    private val dashboardUid = JBTextField(mapping.grafanaDashboardUid)
    private val dashboardSlug = JBTextField(mapping.grafanaDashboardSlug)
    private var dashboardTitle = mapping.grafanaDashboardTitle
    private val orgId = JBTextField(mapping.grafanaOrgId)
    private val variables = JBTextArea(mapping.grafanaVariables, 4, 40)
    private val grafanaTime = JBTextField(mapping.grafanaTimeFrom)
    private val pickDashboard = JButton("Pick…")

    init {
        title = "Application Mapping"
        if (mapping.kibanaIndexPatternId.isNotBlank()) {
            val current = KibanaIndexPattern(mapping.kibanaIndexPatternId, mapping.kibanaIndexPatternTitle.ifBlank { mapping.kibanaIndexPatternId })
            indexPatternModel.add(current)
            indexPattern.selectedItem = current
        }
        pickDashboard.addActionListener { pickDashboard() }
        init()
        loadIndexPatterns()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") { cell(name).align(AlignX.FILL).comment("Service or module name, e.g. orders-service") }
        group("Kibana Logs") {
            row("Index pattern:") { cell(indexPattern).align(AlignX.FILL) }
            row("Query:") {
                cell(kibanaQuery).align(AlignX.FILL)
                    .comment("""KQL that selects this app's logs, e.g. <code>application:"orders" and env:"dev"</code>. Kibana &lt; 6.3 uses Lucene syntax.""")
            }
            row("Time range from:") { cell(kibanaTime).comment("Kibana time expression, e.g. now-15m, now-1h, now-7d") }
            row { cell(kibanaStatus) }
        }
        group("Grafana Dashboard") {
            row("Dashboard UID:") { cell(dashboardUid).align(AlignX.FILL); cell(pickDashboard) }
            row("Slug:") { cell(dashboardSlug).align(AlignX.FILL).comment("Needed only for Grafana < 5 (/dashboard/db/slug)") }
            row("Org ID:") { cell(orgId) }
            row("Variables:") {
                cell(variables).align(AlignX.FILL).comment("One <code>name=value</code> per line, sent as var-name=value")
            }
            row("Time range from:") { cell(grafanaTime) }
        }
    }

    override fun doValidate(): ValidationInfo? =
        if (name.text.isBlank()) ValidationInfo("Name is required", name) else null

    override fun doOKAction() {
        mapping.name = name.text.trim()
        val pattern = indexPattern.selectedItem as? KibanaIndexPattern
        mapping.kibanaIndexPatternId = pattern?.id.orEmpty()
        mapping.kibanaIndexPatternTitle = pattern?.title.orEmpty()
        mapping.kibanaQuery = kibanaQuery.text.trim()
        mapping.kibanaTimeFrom = kibanaTime.text.trim()
        mapping.grafanaDashboardUid = dashboardUid.text.trim()
        mapping.grafanaDashboardSlug = dashboardSlug.text.trim()
        mapping.grafanaDashboardTitle = dashboardTitle
        mapping.grafanaOrgId = orgId.text.trim()
        mapping.grafanaVariables = variables.text.trim()
        mapping.grafanaTimeFrom = grafanaTime.text.trim()
        super.doOKAction()
    }

    private fun loadIndexPatterns() {
        if (!AppSettings.getInstance().isConfigured(ServerKind.KIBANA)) {
            kibanaStatus.text = "Configure the Kibana server first to list index patterns."
            return
        }
        kibanaStatus.text = "Loading index patterns…"
        runInBackground(
            project, null,
            work = { Clients.kibana().indexPatterns() },
            onError = { kibanaStatus.text = "Cannot load index patterns: ${it.userMessage()}" },
            onSuccess = { patterns ->
                val selectedId = (indexPattern.selectedItem as? KibanaIndexPattern)?.id
                indexPatternModel.replaceAll(patterns)
                indexPattern.selectedItem = patterns.firstOrNull { it.id == selectedId } ?: indexPattern.selectedItem
                kibanaStatus.text = "${patterns.size} index patterns"
            },
        )
    }

    private fun pickDashboard() {
        if (!AppSettings.getInstance().isConfigured(ServerKind.GRAFANA)) return
        pickDashboard.isEnabled = false
        runInBackground(
            project, null,
            work = { Clients.grafana().searchDashboards() },
            onError = { pickDashboard.isEnabled = true; setErrorText("Grafana: ${it.userMessage()}") },
            onSuccess = { dashboards ->
                pickDashboard.isEnabled = true
                JBPopupFactory.getInstance().createPopupChooserBuilder(dashboards)
                    .setTitle("Grafana Dashboards")
                    .setNamerForFiltering(GrafanaDashboard::toString)
                    .setItemChosenCallback { d ->
                        dashboardUid.text = d.uid
                        dashboardSlug.text = d.slug
                        dashboardTitle = d.title
                        if (name.text.isBlank()) name.text = d.title
                    }
                    .createPopup()
                    .showUnderneathOf(pickDashboard)
            },
        )
    }
}
