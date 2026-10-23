package io.github.elvismanchkin.taskondemand.core

import io.github.elvismanchkin.taskondemand.confluence.ConfluenceClient
import io.github.elvismanchkin.taskondemand.grafana.GrafanaClient
import io.github.elvismanchkin.taskondemand.jira.JiraClient
import io.github.elvismanchkin.taskondemand.kibana.KibanaClient
import io.github.elvismanchkin.taskondemand.settings.AppSettings

/** Creates clients from the saved settings. Reads secrets, so call these off the EDT. */
object Clients {
    private val transport: HttpTransport by lazy { PlatformHttpTransport() }

    private fun settings() = AppSettings.getInstance()

    fun jira(): JiraClient {
        val client = JiraClient(settings().connection(ServerKind.JIRA), transport, settings().version(ServerKind.JIRA))
        if (client.version == null) {
            // Needed to choose the create-meta API; failures here surface on the actual call instead.
            runCatching { client.serverVersion() }.getOrNull()?.let { settings().rememberVersion(ServerKind.JIRA, it) }
        }
        return client
    }

    fun confluence() = ConfluenceClient(settings().connection(ServerKind.CONFLUENCE), transport)

    fun kibana() = KibanaClient(settings().connection(ServerKind.KIBANA), transport)

    fun grafana() = GrafanaClient(settings().connection(ServerKind.GRAFANA), transport)

    /**
     * Checks a connection that hasn't been saved yet (from the settings page).
     * Returns a short description, such as `Jira 8.13.2, logged in as Jane Doe`, and the detected version.
     */
    fun test(kind: ServerKind, connection: ServerConnection): Pair<String, ServerVersion?> = when (kind) {
        ServerKind.JIRA -> {
            val jira = JiraClient(connection, transport)
            val version = jira.serverVersion()
            "Jira $version, logged in as ${jira.myself()}" to version
        }
        ServerKind.CONFLUENCE -> {
            val confluence = ConfluenceClient(connection, transport)
            confluence.ping()
            val user = if (connection.authType == AuthType.NONE) "anonymous" else confluence.currentUser()
            "Confluence reachable, logged in as $user" to null
        }
        ServerKind.KIBANA -> {
            val kibana = KibanaClient(connection, transport)
            val version = kibana.serverVersion()
            "Kibana $version, ${kibana.indexPatterns().size} index patterns" to version
        }
        ServerKind.GRAFANA -> {
            val grafana = GrafanaClient(connection, transport)
            val version = grafana.serverVersion()
            "Grafana $version, ${grafana.searchDashboards().size} dashboards visible" to version
        }
    }

    /** Version detection for servers whose links depend on it (Kibana, Grafana), cached in settings. */
    fun cachedVersion(kind: ServerKind): ServerVersion? {
        settings().version(kind)?.let { return it }
        val detected = runCatching {
            when (kind) {
                ServerKind.KIBANA -> kibana().serverVersion()
                ServerKind.GRAFANA -> grafana().serverVersion()
                ServerKind.JIRA -> jira().version
                ServerKind.CONFLUENCE -> null
            }
        }.getOrNull()
        if (detected != null) settings().rememberVersion(kind, detected)
        return detected
    }
}
