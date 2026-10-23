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

    /**
     * Adds, updates or removes our rule. Rules we wrote earlier are recognised by shape (a `…/browse/$0` link with a
     * pattern from [IssueKeys.navigationPattern]), so a changed Jira URL or key list replaces them instead of adding
     * a second link per key, and turning the option off removes them.
     */
    fun sync(project: Project) {
        val settings = ProjectSettings.getInstance(project)
        val jiraUrl = AppSettings.getInstance().server(ServerKind.JIRA).url.trim().trimEnd('/')
        val configuration = IssueNavigationConfiguration.getInstance(project)
        val links = configuration.links
        val others = links.filterNot { isOurRule(it.issueRegexp, it.linkRegexp) }

        val wanted = if (settings.state.registerIssueNavigation && jiraUrl.isNotEmpty()) {
            IssueNavigationLink(IssueKeys.navigationPattern(settings.projectKeys), "$jiraUrl/browse/$0")
        } else {
            null
        }
        val updated = others + listOfNotNull(wanted)
        val unchanged = updated.size == links.size &&
            updated.all { u -> links.any { it.issueRegexp == u.issueRegexp && it.linkRegexp == u.linkRegexp } }
        if (!unchanged) configuration.links = updated
    }

    private val OUR_PATTERN = Regex("""\\b\(\?:[A-Z0-9_|]+\)-\\d\+\\b""")

    fun isOurRule(issueRegexp: String, linkRegexp: String): Boolean =
        linkRegexp.endsWith("/browse/$0") &&
            (issueRegexp == IssueKeys.navigationPattern(emptyList()) || OUR_PATTERN.matches(issueRegexp))
}
