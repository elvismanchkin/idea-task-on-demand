package io.github.elvismanchkin.taskondemand.grafana

import io.github.elvismanchkin.taskondemand.core.HttpTransport
import io.github.elvismanchkin.taskondemand.core.RemoteException
import io.github.elvismanchkin.taskondemand.core.RestClient
import io.github.elvismanchkin.taskondemand.core.ServerConnection
import io.github.elvismanchkin.taskondemand.core.ServerVersion
import io.github.elvismanchkin.taskondemand.core.str
import kotlinx.serialization.json.JsonArray

data class GrafanaDashboard(val uid: String, val title: String, val slug: String, val folder: String?) {
    override fun toString(): String = if (folder.isNullOrBlank()) title else "$folder / $title"
}

/** Grafana 4.x – 10.x HTTP API. Blocking; call off the EDT. */
class GrafanaClient(connection: ServerConnection, transport: HttpTransport) {

    private val rest = RestClient(connection, transport)

    val baseUrl: String get() = rest.baseUrl

    /** `/api/health` exists since Grafana 6, and `/api/frontend/settings` covers older versions. */
    fun serverVersion(): ServerVersion? {
        val health = try {
            rest.get("api/health").str("version")
        } catch (e: RemoteException) {
            if (e.status != 404) throw e
            null
        }
        return ServerVersion.parse(health ?: rest.get("api/frontend/settings").str("buildInfo.version"))
    }

    fun searchDashboards(query: String = "", tag: String = ""): List<GrafanaDashboard> =
        (rest.get("api/search", mapOf("type" to "dash-db", "query" to query, "tag" to tag.ifBlank { null })) as? JsonArray)
            .orEmpty()
            .map {
                val url = it.str("url") ?: ""
                GrafanaDashboard(
                    uid = it.str("uid") ?: "",
                    title = it.str("title") ?: "?",
                    // `uri` is `db/{slug}` on old versions; `url` ends with the slug on newer ones.
                    slug = it.str("uri")?.removePrefix("db/") ?: url.substringAfterLast('/'),
                    folder = it.str("folderTitle"),
                )
            }
}
