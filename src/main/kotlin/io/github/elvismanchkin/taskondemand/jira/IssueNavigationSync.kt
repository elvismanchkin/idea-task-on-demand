package io.github.elvismanchkin.taskondemand.jira

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.IssueNavigationConfiguration
import com.intellij.openapi.vcs.IssueNavigationLink
import io.github.elvismanchkin.taskondemand.core.ServerKind
import io.github.elvismanchkin.taskondemand.settings.AppSettings
import io.github.elvismanchkin.taskondemand.settings.ProjectSettings

/**
 * Keeps one IntelliJ Issue Navigation rule (Settings | Version Control | Issue Navigation) pointing at the Jira server,
 * so issue keys are links in the VCS log, commit messages and the console. Other rules are left alone.
 */
object IssueNavigationSync {

    fun sync(project: Project) {
        val settings = ProjectSettings.getInstance(project)
        val jiraUrl = AppSettings.getInstance().server(ServerKind.JIRA).url.trim().trimEnd('/')
        if (!settings.state.registerIssueNavigation || jiraUrl.isEmpty()) return

        val configuration = IssueNavigationConfiguration.getInstance(project)
        val link = "$jiraUrl/browse/$0"
        val pattern = IssueKeys.navigationPattern(settings.projectKeys)
        val links = configuration.links
        if (links.any { it.issueRegexp == pattern && it.linkRegexp == link }) return

        // Replace our earlier rule (same Jira URL) instead of piling up variants when the project keys change.
        val updated = links.filterNot { it.linkRegexp == link } + IssueNavigationLink(pattern, link)
        configuration.links = updated
    }
}
