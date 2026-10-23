package io.github.elvismanchkin.taskondemand.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import io.github.elvismanchkin.taskondemand.core.AuthType
import io.github.elvismanchkin.taskondemand.core.Clients
import io.github.elvismanchkin.taskondemand.core.ServerConnection
import io.github.elvismanchkin.taskondemand.core.ServerKind
import io.github.elvismanchkin.taskondemand.core.runInBackground
import io.github.elvismanchkin.taskondemand.core.userMessage
import io.github.elvismanchkin.taskondemand.jira.CurrentIssueService
import io.github.elvismanchkin.taskondemand.jira.IssueNavigationSync
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList

/** Settings | Tools | Task on Demand: server URLs and credentials (per developer). */
class ServersConfigurable : Configurable {

    private val forms = ServerKind.entries.associateWith { ServerForm(it) }

    override fun getDisplayName(): String = "Task on Demand"

    override fun createComponent(): JComponent = panel {
        row {
            comment(
                "On-premise servers. Secrets are kept in the IDE password safe. " +
                    "For internal or self-signed certificates, accept them under Tools | Server Certificates.",
            )
        }
        forms.values.forEach { form ->
            group(form.kind.displayName) {
                row("URL:") { cell(form.url).align(AlignX.FILL).comment(form.urlHint()) }
                row("Authentication:") { cell(form.auth) }
                row("Username:") { cell(form.username).align(AlignX.FILL) }.visibleIf(form.needsUsername)
                row { cell(form.secretLabel); cell(form.secret).align(AlignX.FILL) }.visibleIf(form.needsSecret)
                row { cell(form.testButton); cell(form.testResult) }
            }
        }
    }

    override fun isModified(): Boolean = forms.values.any { it.isModified() }

    override fun apply() {
        forms.values.forEach { it.apply() }
        // Once the secrets are saved, point issue links at the (possibly new) Jira URL and re-fetch the current issue.
        Credentials.afterPendingWrites {
            ApplicationManager.getApplication().invokeLater {
                ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }.forEach { project ->
                    IssueNavigationSync.sync(project)
                    CurrentIssueService.getInstance(project).refresh(force = true)
                }
            }
        }
    }

    override fun reset() = forms.values.forEach { it.reset() }

    private class ServerForm(val kind: ServerKind) {
        private val settings = AppSettings.getInstance()
        val url = JBTextField()
        val auth = ComboBox(CollectionComboBoxModel(kind.supportedAuth)).apply {
            renderer = object : SimpleListCellRenderer<AuthType>() {
                override fun customize(list: JList<out AuthType>, value: AuthType?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    text = value?.displayName.orEmpty()
                }
            }
        }
        val username = JBTextField()
        val secret = JBPasswordField()
        val secretLabel = JBLabel("Password:").apply { preferredSize = JBUI.size(150, preferredSize.height) }
        val testResult = JBLabel()
        val testButton = JButton("Test Connection").apply { addActionListener { test() } }
        val needsUsername = ComboPredicate(auth) { it.needsUsername }
        val needsSecret = ComboPredicate(auth) { it.secretLabel != null }

        /** Secret as loaded, to detect edits without hitting the password safe again. */
        private var loadedSecret: String = ""
        /** The password safe is read in the background; until then the secret field is disabled and never saved. */
        private var secretLoaded = false
        private var resetGeneration = 0

        init {
            auth.addActionListener { secretLabel.text = selectedAuth().secretLabel.orEmpty() }
        }

        fun urlHint(): String = when (kind) {
            ServerKind.JIRA -> "e.g. https://jira.company.local or https://tools.company.local/jira"
            ServerKind.CONFLUENCE -> "e.g. https://wiki.company.local (include the context path such as /confluence)"
            ServerKind.KIBANA -> "e.g. https://kibana.company.local:5601"
            ServerKind.GRAFANA -> "e.g. https://grafana.company.local"
        }

        fun selectedAuth(): AuthType = auth.selectedItem as? AuthType ?: kind.supportedAuth.first()

        private fun currentSecret() = String(secret.password)

        /** Stored form of the URL field; compared and saved the same way. */
        private fun enteredUrl() = url.text.trim().trimEnd('/')

        fun isModified(): Boolean {
            val state = settings.server(kind)
            return enteredUrl() != state.url || selectedAuth() != settings.authType(kind) ||
                username.text.trim() != state.username || (secretLoaded && currentSecret() != loadedSecret)
        }

        fun apply() {
            val state = settings.server(kind)
            val oldUrl = state.url
            val oldUser = state.username
            state.url = enteredUrl()
            state.authType = selectedAuth().name
            state.username = username.text.trim()
            if (state.url != oldUrl) state.detectedVersion = ""
            val secretToSave = when {
                selectedAuth() == AuthType.NONE -> ""
                secretLoaded -> currentSecret()
                else -> null
            }
            Credentials.saveAsync(kind, oldUrl, oldUser, state.url, state.username, secretToSave)
            if (secretLoaded) loadedSecret = currentSecret()
            url.text = state.url
        }

        fun reset() {
            val state = settings.server(kind)
            url.text = state.url
            auth.selectedItem = settings.authType(kind)
            secretLabel.text = settings.authType(kind).secretLabel.orEmpty()
            username.text = state.username
            testResult.text = state.detectedVersion.takeIf { it.isNotBlank() }?.let { "Last detected version: $it" }.orEmpty()
            loadSecret(state.url, state.username)
        }

        private fun loadSecret(savedUrl: String, savedUser: String) {
            val request = ++resetGeneration
            secretLoaded = false
            secret.text = ""
            secret.isEnabled = false
            testButton.isEnabled = false
            fun loaded(value: String) {
                if (request != resetGeneration) return
                loadedSecret = value
                secret.text = value
                secretLoaded = true
                secret.isEnabled = true
                testButton.isEnabled = true
            }
            if (savedUrl.isBlank()) return loaded("")
            runInBackground(
                null, null,
                work = { Credentials.getAfterPendingWrites(kind, savedUrl, savedUser).orEmpty() },
                // Leave the field disabled: saving now would overwrite a secret we could not read.
                onError = { if (request == resetGeneration) testButton.isEnabled = true },
                onSuccess = { loaded(it) },
            )
        }

        private fun test() {
            val connection = ServerConnection(url.text, selectedAuth(), username.text.trim(), currentSecret())
            if (!connection.isConfigured) {
                testResult.text = "Enter the URL first"
                return
            }
            testResult.text = "Connecting…"
            testButton.isEnabled = false
            runInBackground(
                null, null,
                work = { Clients.test(kind, connection) },
                onError = { testResult.text = "✗ " + it.userMessage(); testButton.isEnabled = true },
                onSuccess = { (message, version) ->
                    testResult.text = "✓ $message"
                    testButton.isEnabled = true
                    if (connection.normalizedUrl == settings.server(kind).url) settings.rememberVersion(kind, version)
                },
            )
        }
    }
}
