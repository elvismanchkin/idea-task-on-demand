package io.github.elvismanchkin.taskondemand.confluence

import io.github.elvismanchkin.taskondemand.core.HttpTransport
import io.github.elvismanchkin.taskondemand.core.RemoteException
import io.github.elvismanchkin.taskondemand.core.RestClient
import io.github.elvismanchkin.taskondemand.core.ServerConnection
import io.github.elvismanchkin.taskondemand.core.arr
import io.github.elvismanchkin.taskondemand.core.str
import kotlinx.serialization.json.JsonElement

data class ConfluencePage(
    val id: String,
    val title: String,
    val spaceKey: String?,
    val spaceName: String?,
    val url: String,
    val lastUpdated: String?,
)

/** Confluence Server / Data Center REST API (`/rest/api/content`, Confluence 5.5 – 8.x). Blocking; call off the EDT. */
class ConfluenceClient(connection: ServerConnection, transport: HttpTransport) {

    private val rest = RestClient(connection, transport)

    val baseUrl: String get() = rest.baseUrl

    /** Works anonymously too, when the server allows anonymous access. */
    fun ping() {
        rest.get("rest/api/space", mapOf("limit" to 1))
    }

    fun currentUser(): String {
        val json = rest.get("rest/api/user/current")
        if (json.str("type") == "anonymous") throw RemoteException("Logged in as anonymous; check the credentials", 401)
        return json.str("displayName") ?: json.str("username") ?: "?"
    }

    fun page(id: String): ConfluencePage = parsePage(rest.get("rest/api/content/$id", mapOf("expand" to EXPAND)))

    fun pageByTitle(spaceKey: String, title: String): ConfluencePage? =
        rest.get("rest/api/content", mapOf("spaceKey" to spaceKey, "title" to title, "expand" to EXPAND))
            .arr("results").firstOrNull()?.let(::parsePage)

    fun search(cql: String, limit: Int = 25): List<ConfluencePage> =
        rest.get("rest/api/content/search", mapOf("cql" to cql, "limit" to limit, "expand" to EXPAND))
            .arr("results").map(::parsePage)

    fun resolve(ref: PageRef): ConfluencePage? = when (ref) {
        is PageRef.ById -> page(ref.id)
        is PageRef.ByTitle -> pageByTitle(ref.spaceKey, ref.title)
    }

    private fun parsePage(json: JsonElement): ConfluencePage {
        val id = json.str("id") ?: ""
        val webui = json.str("_links.webui")
        return ConfluencePage(
            id = id,
            title = json.str("title") ?: id,
            spaceKey = json.str("space.key"),
            spaceName = json.str("space.name"),
            url = if (webui != null) baseUrl + webui else "$baseUrl/pages/viewpage.action?pageId=$id",
            lastUpdated = json.str("version.when"),
        )
    }

    companion object {
        private const val EXPAND = "space,version"

        /** CQL for a free-text search, optionally limited to one space. */
        fun textSearchCql(text: String, spaceKey: String?): String {
            val quoted = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            val space = if (spaceKey.isNullOrBlank()) "" else " AND space = \"$spaceKey\""
            return "type = page AND (title ~ $quoted OR text ~ $quoted)$space ORDER BY lastmodified DESC"
        }
    }
}
