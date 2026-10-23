package io.github.elvismanchkin.taskondemand.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.awt.RelativePoint
import io.github.elvismanchkin.taskondemand.confluence.LinkedPage
import io.github.elvismanchkin.taskondemand.confluence.LinkedPagesCollector
import io.github.elvismanchkin.taskondemand.core.Clients
import io.github.elvismanchkin.taskondemand.core.Notifier
import io.github.elvismanchkin.taskondemand.core.ServerKind
import io.github.elvismanchkin.taskondemand.core.runInBackground
import io.github.elvismanchkin.taskondemand.core.userMessage
import io.github.elvismanchkin.taskondemand.grafana.GrafanaUrls
import io.github.elvismanchkin.taskondemand.kibana.KibanaUrls
import io.github.elvismanchkin.taskondemand.settings.AppSettings
import io.github.elvismanchkin.taskondemand.settings.ProjectSettings
import io.github.elvismanchkin.taskondemand.settings.ProjectSettings.AppMapping
import java.nio.file.Path

/** Opens Jira, Kibana, Grafana and Confluence in the browser, shared by actions, the tool window and the status bar. */
object Navigator {

    fun openIssue(project: Project, key: String) {
        val url = AppSettings.getInstance().server(ServerKind.JIRA).url.trim().trimEnd('/')
        if (url.isEmpty()) return Notifier.notConfigured(project, "Jira")
        BrowserUtil.browse("$url/browse/$key")
    }

    fun openLogs(project: Project, app: AppMapping?, extraText: String? = null) {
        val settings = AppSettings.getInstance()
        if (!settings.isConfigured(ServerKind.KIBANA)) return Notifier.notConfigured(project, "Kibana")
        if (app == null || !app.hasKibana) return Notifier.notConfigured(project, "Kibana mapping for this project")
        runInBackground(project, "Opening Kibana", { Clients.cachedVersion(ServerKind.KIBANA) }) { version ->
            val query = KibanaUrls.combineQuery(app.kibanaQuery, extraText, KibanaUrls.queryLanguage(version))
            BrowserUtil.browse(
                KibanaUrls.discover(settings.server(ServerKind.KIBANA).url, version, app.kibanaIndexPatternId, query, app.kibanaTimeFrom),
            )
        }
    }

    fun openDashboard(project: Project, app: AppMapping?) {
        val settings = AppSettings.getInstance()
        if (!settings.isConfigured(ServerKind.GRAFANA)) return Notifier.notConfigured(project, "Grafana")
        if (app == null || !app.hasGrafana) return Notifier.notConfigured(project, "Grafana dashboard for this project")
        runInBackground(project, "Opening Grafana", { Clients.cachedVersion(ServerKind.GRAFANA) }) { version ->
            BrowserUtil.browse(
                GrafanaUrls.dashboard(
                    settings.server(ServerKind.GRAFANA).url, version, app.grafanaDashboardUid, app.grafanaDashboardSlug,
                    app.grafanaOrgId, GrafanaUrls.parseVariables(app.grafanaVariables), app.grafanaTimeFrom,
                ),
            )
        }
    }

    /** Runs [action] for the active application, or asks which one when the project has several. */
    fun withApplication(project: Project, where: RelativePoint?, filter: (AppMapping) -> Boolean, action: (AppMapping?) -> Unit) {
        val apps = ProjectSettings.getInstance(project).state.applications.filter(filter)
        if (apps.size <= 1 || where == null) {
            action(apps.firstOrNull { it.name == ProjectSettings.getInstance(project).state.selectedApplication } ?: apps.firstOrNull())
            return
        }
        JBPopupFactory.getInstance().createListPopup(object : BaseListPopupStep<AppMapping>("Application", apps) {
            override fun onChosen(selectedValue: AppMapping, finalChoice: Boolean): PopupStep<*>? {
                ProjectSettings.getInstance(project).state.selectedApplication = selectedValue.name
                return doFinalStep { action(selectedValue) }
            }
        }).show(where)
    }

    /**
     * Loads the pages linked to the project and to [issueKey]. With [onError] it runs silently (no progress, no
     * balloon), for refreshes nobody asked for such as branch changes.
     */
    fun collectPages(
        project: Project,
        issueKey: String?,
        onError: ((Throwable) -> Unit)? = null,
        onDone: (List<LinkedPage>) -> Unit,
    ) {
        val app = AppSettings.getInstance()
        if (!app.isConfigured(ServerKind.CONFLUENCE)) return Notifier.notConfigured(project, "Confluence")
        val settings = ProjectSettings.getInstance(project)
        runInBackground(
            project, if (onError == null) "Collecting Confluence pages" else null,
            work = {
                val links = if (issueKey != null && app.isConfigured(ServerKind.JIRA)) {
                    runCatching { Clients.jira().remoteLinks(issueKey) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                val root = project.basePath?.takeIf { settings.state.scanRepositoryForConfluenceLinks }?.let { Path.of(it) }
                LinkedPagesCollector(Clients.confluence(), app.server(ServerKind.CONFLUENCE).url.trim().trimEnd('/'))
                    .collect(settings.pinnedConfluencePageIds, issueKey, links, root)
            },
            onError = onError ?: { Notifier.error(project, "Confluence: ${it.userMessage()}") },
            onSuccess = onDone,
        )
    }

    fun showPagesPopup(project: Project, issueKey: String?, where: RelativePoint) {
        collectPages(project, issueKey) { pages ->
            if (pages.isEmpty()) return@collectPages Notifier.info(project, "No linked Confluence pages found")
            JBPopupFactory.getInstance().createListPopup(object : BaseListPopupStep<LinkedPage>("Confluence Pages", pages) {
                override fun getTextFor(value: LinkedPage) = "${value.title}  —  ${value.source.label}"
                override fun onChosen(selectedValue: LinkedPage, finalChoice: Boolean): PopupStep<*>? {
                    BrowserUtil.browse(selectedValue.url)
                    return FINAL_CHOICE
                }
            }).show(where)
        }
    }
}
