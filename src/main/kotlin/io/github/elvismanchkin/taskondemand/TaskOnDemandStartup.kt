package io.github.elvismanchkin.taskondemand

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.elvismanchkin.taskondemand.jira.CurrentIssueService
import io.github.elvismanchkin.taskondemand.jira.IssueNavigationSync

class TaskOnDemandStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        IssueNavigationSync.sync(project)
        CurrentIssueService.getInstance(project).refresh()
    }
}
