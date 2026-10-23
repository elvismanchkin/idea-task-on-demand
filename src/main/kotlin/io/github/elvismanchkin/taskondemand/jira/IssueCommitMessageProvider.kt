package io.github.elvismanchkin.taskondemand.jira

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.LocalChangeList
import com.intellij.openapi.vcs.changes.ui.CommitMessageProvider
import io.github.elvismanchkin.taskondemand.settings.ProjectSettings

/** Prefills an empty commit message with `PROJ-123 ` taken from the current branch. */
class IssueCommitMessageProvider : CommitMessageProvider {
    override fun getCommitMessage(forChangelist: LocalChangeList, project: Project): String? {
        if (!ProjectSettings.getInstance(project).state.prefixCommitMessage) return null
        if (!forChangelist.comment.isNullOrBlank()) return null
        val key = CurrentIssueService.getInstance(project).snapshot.issueKey ?: return null
        return "$key "
    }
}
