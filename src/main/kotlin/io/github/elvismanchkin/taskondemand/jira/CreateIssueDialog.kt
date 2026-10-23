package io.github.elvismanchkin.taskondemand.jira

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.elvismanchkin.taskondemand.core.Clients
import io.github.elvismanchkin.taskondemand.core.runInBackground
import io.github.elvismanchkin.taskondemand.core.userMessage
import io.github.elvismanchkin.taskondemand.jira.CreateIssueFields.Editor
import io.github.elvismanchkin.taskondemand.settings.ProjectSettings
import io.github.elvismanchkin.taskondemand.ui.Navigator
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Creates a Jira issue. Issue types and fields come from create-meta (version-aware, see [JiraClient.issueTypes]),
 * so required custom fields of the project's create screen are shown too.
 */
class CreateIssueDialog(private val project: Project, private val onCreated: ((String) -> Unit)?) : DialogWrapper(project) {

    private val settings = ProjectSettings.getInstance(project)
    private val projectCombo = ComboBox(CollectionComboBoxModel(settings.projectKeys.toMutableList())).apply { isEditable = true }
    private val typeModel = CollectionComboBoxModel<JiraIssueType>()
    private val typeCombo = ComboBox(typeModel)
    private val summary = JBTextField(50)
    private val description = JBTextArea(6, 50).apply { lineWrap = true; wrapStyleWord = true }
    private val descriptionScroll = ScrollPaneFactory.createScrollPane(description)
    private val extraPanel = JPanel(BorderLayout())
    private val statusLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    private var fields: List<JiraField> = emptyList()
    private val editors = mutableMapOf<JiraField, () -> Any?>()
    private var loadedProject: String? = null

    init {
        title = "Create Jira Issue"
        setOKButtonText("Create")
        projectCombo.addActionListener { loadIssueTypes() }
        typeCombo.addActionListener { loadFields() }
        init()
        val currentProject = CurrentIssueService.getInstance(project).snapshot.issueKey?.substringBefore('-')
        projectCombo.selectedItem = currentProject?.takeIf { it in settings.projectKeys } ?: settings.projectKeys.firstOrNull()
        loadIssueTypes()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Project key:", projectCombo)
        .addLabeledComponent("Issue type:", typeCombo)
        .addLabeledComponent("Summary:", summary)
        .addLabeledComponent("Description:", descriptionScroll, true)
        .addComponent(extraPanel)
        .addComponent(statusLabel)
        .panel.apply { preferredSize = JBUI.size(560, 380) }

    override fun getPreferredFocusedComponent(): JComponent = summary

    private fun projectKey(): String = (projectCombo.editor.item ?: projectCombo.selectedItem)?.toString()?.trim()?.uppercase().orEmpty()

    private fun loadIssueTypes() {
        val key = projectKey()
        if (key.isEmpty() || key == loadedProject) return
        loadedProject = key
        statusLabel.text = "Loading issue types for $key…"
        runInBackground(
            project, null,
            work = { Clients.jira().issueTypes(key).filterNot { it.subtask } },
            onError = { statusLabel.text = it.userMessage(); typeModel.removeAll() },
            onSuccess = { types ->
                if (key != projectKey()) return@runInBackground
                typeModel.replaceAll(types)
                val preferred = settings.state.defaultIssueType.ifBlank { "Task" }
                typeCombo.selectedItem = types.firstOrNull { it.name.equals(preferred, true) } ?: types.firstOrNull()
                statusLabel.text = ""
                loadFields()
            },
        )
    }

    private fun loadFields() {
        val key = projectKey()
        val type = typeCombo.selectedItem as? JiraIssueType ?: return
        statusLabel.text = "Loading fields…"
        runInBackground(
            project, null,
            work = { Clients.jira().fields(key, type.id) },
            onError = { statusLabel.text = it.userMessage() },
            onSuccess = { loaded ->
                if (typeCombo.selectedItem != type) return@runInBackground
                fields = loaded
                rebuildExtraFields()
            },
        )
    }

    private fun rebuildExtraFields() {
        editors.clear()
        val form = FormBuilder.createFormBuilder()
        CreateIssueFields.editable(fields).forEach { field ->
            val (component, value) = editorComponent(field)
            editors[field] = value
            form.addLabeledComponent(field.name + (if (field.required) " *:" else ":"), component)
        }
        extraPanel.removeAll()
        extraPanel.add(form.panel, BorderLayout.CENTER)
        descriptionScroll.isVisible = fields.isEmpty() || fields.any { it.id == "description" }
        val unsupported = CreateIssueFields.unsupportedRequired(fields)
        statusLabel.text = if (unsupported.isEmpty()) "" else
            "Required fields not supported here: ${unsupported.joinToString { it.name }}. Jira may reject the issue."
        extraPanel.revalidate()
        pack()
    }

    private fun editorComponent(field: JiraField): Pair<JComponent, () -> Any?> = when (CreateIssueFields.editorFor(field)) {
        Editor.SELECT -> {
            val options = (if (field.required) emptyList() else listOf<JiraAllowedValue?>(null)) + field.allowedValues
            val combo = ComboBox(CollectionComboBoxModel(options.toMutableList()))
            combo.renderer = object : SimpleListCellRenderer<JiraAllowedValue?>() {
                override fun customize(list: JList<out JiraAllowedValue?>, value: JiraAllowedValue?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    text = value?.name ?: "—"
                }
            }
            combo to { (combo.selectedItem as? JiraAllowedValue)?.let { if (field.schemaType == "string") it.name else it.id } }
        }
        Editor.MULTI_SELECT -> {
            val list = JBList(field.allowedValues).apply {
                selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
                visibleRowCount = 4
            }
            ScrollPaneFactory.createScrollPane(list) to { list.selectedValuesList.map { it.id } }
        }
        Editor.DATE -> JBTextField().apply { emptyText.text = "yyyy-MM-dd" }.let { it to { it.text } }
        Editor.USER -> JBTextField().apply { emptyText.text = "username" }.let { it to { it.text } }
        Editor.LABELS -> JBTextField().apply { emptyText.text = "comma-separated" }.let { it to { it.text } }
        else -> JBTextField().let { it to { it.text } }
    }

    override fun doValidate(): ValidationInfo? = when {
        projectKey().isEmpty() -> ValidationInfo("Enter a project key", projectCombo)
        typeCombo.selectedItem == null -> ValidationInfo("Select an issue type", typeCombo)
        summary.text.isBlank() -> ValidationInfo("Summary is required", summary)
        else -> editors.entries.firstOrNull { (field, value) ->
            field.required && !field.hasDefaultValue && CreateIssueFields.toJson(field, value()) == null
        }?.let { ValidationInfo("${it.key.name} is required") }
    }

    override fun doOKAction() {
        val type = typeCombo.selectedItem as JiraIssueType
        val payload = CreateIssueFields.build(
            projectKey(), type.id, summary.text,
            description.text.takeIf { descriptionScroll.isVisible },
            editors.mapValues { (_, value) -> value() },
        )
        val key = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<String, Exception>(
                { Clients.jira().createIssue(payload) }, "Creating Issue", true, project,
            )
        } catch (e: Exception) {
            setErrorText(e.userMessage())
            return
        }
        super.doOKAction()
        notifyCreated(key)
        onCreated?.invoke(key)
    }

    private fun notifyCreated(key: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Task on Demand")
            .createNotification("Created $key", summary.text, NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimple("Open") { Navigator.openIssue(project, key) })
            .addAction(NotificationAction.createSimple("Copy key") { CopyPasteManager.getInstance().setContents(StringSelection(key)) })
            .notify(project)
    }

    companion object {
        fun show(project: Project, onCreated: ((String) -> Unit)? = null) {
            CreateIssueDialog(project, onCreated).show()
        }
    }
}
