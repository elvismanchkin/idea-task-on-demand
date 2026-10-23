package io.github.elvismanchkin.taskondemand.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import io.github.elvismanchkin.taskondemand.jira.CurrentIssueService
import io.github.elvismanchkin.taskondemand.jira.IssueKeys
import io.github.elvismanchkin.taskondemand.jira.IssueNavigationSync
import io.github.elvismanchkin.taskondemand.settings.ProjectSettings.AppMapping
import javax.swing.JComponent

/** Settings | Tools | Task on Demand | Project Mapping, stored in `.idea/taskOnDemand.xml` (shareable). */
class ProjectMappingConfigurable(private val project: Project) : Configurable {

    private val settings = ProjectSettings.getInstance(project)

    private val projectKeys = JBTextField()
    private val jql = JBTextField()
    private val branchPattern = JBTextField()
    private val defaultIssueType = JBTextField()
    private val prefixCommit = JBCheckBox("Prefill commit message with the issue key")
    private val registerNavigation = JBCheckBox("Make issue keys clickable in VCS log, commit messages and console")
    private val spaceKey = JBTextField()
    private val pageIds = JBTextField()
    private val scanRepository = JBCheckBox("Find Confluence links in README and docs/ files")

    private val appsModel = CollectionListModel<AppMapping>()
    private val appsList = JBList(appsModel)

    override fun getDisplayName(): String = "Project Mapping"

    override fun createComponent(): JComponent {
        val appsPanel = ToolbarDecorator.createDecorator(appsList)
            .setAddAction { AppMapping().let { if (AppMappingDialog(project, it).showAndGet()) appsModel.add(it) } }
            .setEditAction { editSelected() }
            .setRemoveAction { appsList.selectedIndices.reversed().forEach(appsModel::remove) }
            .createPanel()
        return panel {
            row { comment("Stored in .idea/taskOnDemand.xml. Commit it to share the mapping with the team. It holds no URLs or credentials.") }
            group("Jira") {
                row("Project keys:") { cell(projectKeys).align(AlignX.FILL).comment("Comma-separated, e.g. PROJ, OPS") }
                row("Task list JQL:") {
                    cell(jql).align(AlignX.FILL).comment("Empty means: issues assigned to you that are not resolved, in the projects above")
                }
                row("Branch pattern:") {
                    cell(branchPattern).align(AlignX.FILL)
                        .comment("Optional regex for the issue key in branch names (group 1 is used). Empty matches feature/PROJ-123-…")
                }
                row("Default issue type:") { cell(defaultIssueType).comment("e.g. Task, Story, Bug") }
                row { cell(prefixCommit) }
                row { cell(registerNavigation) }
            }
            group("Confluence") {
                row("Space key:") { cell(spaceKey).comment("Limits search to this space") }
                row("Pinned page IDs:") { cell(pageIds).align(AlignX.FILL).comment("Comma-separated page IDs that always appear for this project") }
                row { cell(scanRepository) }
            }
            group("Applications (Kibana logs and Grafana dashboards)") {
                row { cell(appsPanel).align(Align.FILL) }.resizableRow()
            }
        }
    }

    private fun editSelected() {
        val index = appsList.selectedIndex.takeIf { it >= 0 } ?: return
        val copy = appsModel.getElementAt(index).copy()
        if (AppMappingDialog(project, copy).showAndGet()) appsModel.setElementAt(copy, index)
    }

    override fun isModified(): Boolean {
        val s = settings.state
        return projectKeys.text.trim() != s.jiraProjectKeys || jql.text.trim() != s.jiraJql ||
            branchPattern.text.trim() != s.branchIssuePattern || defaultIssueType.text.trim() != s.defaultIssueType ||
            prefixCommit.isSelected != s.prefixCommitMessage || registerNavigation.isSelected != s.registerIssueNavigation ||
            spaceKey.text.trim() != s.confluenceSpaceKey || pageIds.text.trim() != s.confluencePageIds ||
            scanRepository.isSelected != s.scanRepositoryForConfluenceLinks ||
            !sameApplications(appsModel.items, s.applications)
    }

    override fun apply() {
        val s = settings.state
        s.jiraProjectKeys = projectKeys.text.trim()
        s.jiraJql = jql.text.trim().takeIf { it != IssueKeys.defaultJql(settings.projectKeys) }.orEmpty()
        s.branchIssuePattern = branchPattern.text.trim()
        s.defaultIssueType = defaultIssueType.text.trim()
        s.prefixCommitMessage = prefixCommit.isSelected
        s.registerIssueNavigation = registerNavigation.isSelected
        s.confluenceSpaceKey = spaceKey.text.trim()
        s.confluencePageIds = pageIds.text.trim()
        s.scanRepositoryForConfluenceLinks = scanRepository.isSelected
        s.applications = appsModel.items.map { it.copy() }.toMutableList()
        IssueNavigationSync.sync(project)
        CurrentIssueService.getInstance(project).refresh(force = true)
    }

    override fun reset() {
        val s = settings.state
        projectKeys.text = s.jiraProjectKeys
        jql.text = s.jiraJql
        branchPattern.text = s.branchIssuePattern
        defaultIssueType.text = s.defaultIssueType
        prefixCommit.isSelected = s.prefixCommitMessage
        registerNavigation.isSelected = s.registerIssueNavigation
        spaceKey.text = s.confluenceSpaceKey
        pageIds.text = s.confluencePageIds
        scanRepository.isSelected = s.scanRepositoryForConfluenceLinks
        appsModel.replaceAll(s.applications.map { it.copy() })
    }

    private fun sameApplications(a: List<AppMapping>, b: List<AppMapping>): Boolean =
        a.size == b.size && a.zip(b).all { (x, y) -> JDOMUtil.areElementsEqual(XmlSerializer.serialize(x), XmlSerializer.serialize(y)) }
}
