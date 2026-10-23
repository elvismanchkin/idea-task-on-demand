package io.github.elvismanchkin.taskondemand.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import io.github.elvismanchkin.taskondemand.confluence.ConfluenceClient
import io.github.elvismanchkin.taskondemand.confluence.LinkedPage
import io.github.elvismanchkin.taskondemand.confluence.PageSource
import io.github.elvismanchkin.taskondemand.core.Clients
import io.github.elvismanchkin.taskondemand.core.ServerKind
import io.github.elvismanchkin.taskondemand.core.runInBackground
import io.github.elvismanchkin.taskondemand.core.userMessage
import io.github.elvismanchkin.taskondemand.jira.CurrentIssueService
import io.github.elvismanchkin.taskondemand.settings.AppSettings
import io.github.elvismanchkin.taskondemand.settings.ProjectSettings
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel

/** Confluence tab: pages linked to the project and current issue, plus free-text search. */
class ConfluencePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val model = DefaultListModel<LinkedPage>()
    private val list = JBList(model)
    private val search = SearchTextField(false)
    private var lastIssueKey: String? = null

    init {
        list.cellRenderer = object : ColoredListCellRenderer<LinkedPage>() {
            override fun customizeCellRenderer(list: JList<out LinkedPage>, value: LinkedPage, index: Int, selected: Boolean, hasFocus: Boolean) {
                icon = if (value.source == PageSource.REPOSITORY) AllIcons.Nodes.Folder else AllIcons.FileTypes.Html
                append(value.title)
                val details = listOfNotNull(value.spaceKey, value.source.label, value.detail).joinToString(" · ")
                append("   $details", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                list.selectedValue?.let { BrowserUtil.browse(it.url) } ?: return false
                return true
            }
        }.installOn(list)
        search.textEditor.emptyText.text = "Search Confluence (Enter)"
        search.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) runSearch(search.text.trim())
            }
        })

        val toolbar = toolbar(
            "TaskOnDemand.Confluence", this,
            panelAction("Show Linked Pages", AllIcons.Actions.Refresh) { reload() },
            panelAction("Open in Browser", AllIcons.General.Web, { list.selectedValue != null }) {
                list.selectedValue?.let { BrowserUtil.browse(it.url) }
            },
        )
        add(JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(search, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER)

        project.messageBus.connect(this).subscribe(CurrentIssueService.TOPIC, CurrentIssueService.Listener {
            if (it.issueKey != lastIssueKey) reload()
        })
        reload()
    }

    fun reload() {
        if (!AppSettings.getInstance().isConfigured(ServerKind.CONFLUENCE)) {
            list.emptyText.text = "Confluence server is not configured (Settings | Tools | Task on Demand)"
            return
        }
        lastIssueKey = CurrentIssueService.getInstance(project).snapshot.issueKey
        list.emptyText.text = "Loading…"
        Navigator.collectPages(project, lastIssueKey) { show(it, "No linked pages. Link pages in the Jira issue, the README, or project settings.") }
    }

    private fun runSearch(text: String) {
        if (text.isEmpty()) return reload()
        if (!AppSettings.getInstance().isConfigured(ServerKind.CONFLUENCE)) return
        val space = ProjectSettings.getInstance(project).state.confluenceSpaceKey
        list.emptyText.text = "Searching…"
        runInBackground(
            project, null,
            work = { Clients.confluence().search(ConfluenceClient.textSearchCql(text, space)) },
            onError = { show(emptyList(), it.userMessage()) },
            onSuccess = { pages -> show(pages.map { LinkedPage(it.title, it.url, PageSource.SEARCH, null, it.spaceKey, it.id) }, "Nothing found") },
        )
    }

    private fun show(pages: List<LinkedPage>, emptyText: String) {
        model.clear()
        pages.forEach(model::addElement)
        list.emptyText.text = emptyText
    }

    override fun dispose() = Unit
}
