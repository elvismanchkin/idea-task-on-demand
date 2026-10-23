package io.github.elvismanchkin.taskondemand.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import io.github.elvismanchkin.taskondemand.core.AuthType
import io.github.elvismanchkin.taskondemand.core.ServerConnection
import io.github.elvismanchkin.taskondemand.core.ServerKind
import io.github.elvismanchkin.taskondemand.core.ServerVersion

/** Per-developer server settings (not shared with the team). Secrets live in [Credentials], not here. */
@Service(Service.Level.APP)
@State(name = "TaskOnDemandServers", storages = [Storage("taskOnDemand.xml")])
class AppSettings : PersistentStateComponent<AppSettings.State> {

    class ServerState {
        var url: String = ""
        var authType: String = ""
        var username: String = ""
        /** Last version reported by the server; chooses between legacy and newer API flavours. Written off the EDT. */
        @Volatile
        var detectedVersion: String = ""
    }

    class State {
        var jira = ServerState()
        var confluence = ServerState()
        var kibana = ServerState()
        var grafana = ServerState()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    fun server(kind: ServerKind): ServerState = when (kind) {
        ServerKind.JIRA -> state.jira
        ServerKind.CONFLUENCE -> state.confluence
        ServerKind.KIBANA -> state.kibana
        ServerKind.GRAFANA -> state.grafana
    }

    fun authType(kind: ServerKind): AuthType =
        AuthType.entries.firstOrNull { it.name == server(kind).authType } ?: kind.supportedAuth.first()

    fun version(kind: ServerKind): ServerVersion? = ServerVersion.parse(server(kind).detectedVersion)

    fun rememberVersion(kind: ServerKind, version: ServerVersion?) {
        server(kind).detectedVersion = version?.toString().orEmpty()
    }

    fun isConfigured(kind: ServerKind): Boolean = server(kind).url.isNotBlank()

    /** Reads the secret from the password safe, which may block, so call it off the EDT. */
    fun connection(kind: ServerKind): ServerConnection {
        val server = server(kind)
        val auth = authType(kind)
        val secret = if (auth == AuthType.NONE) "" else Credentials.get(kind, server.url, server.username).orEmpty()
        return ServerConnection(server.url, auth, server.username, secret)
    }

    companion object {
        fun getInstance(): AppSettings = ApplicationManager.getApplication().getService(AppSettings::class.java)
    }
}
