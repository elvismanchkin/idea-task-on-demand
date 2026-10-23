package io.github.elvismanchkin.taskondemand.jira

import io.github.elvismanchkin.taskondemand.core.HttpTransport
import io.github.elvismanchkin.taskondemand.core.RemoteException
import io.github.elvismanchkin.taskondemand.core.RestClient
import io.github.elvismanchkin.taskondemand.core.ServerConnection
import io.github.elvismanchkin.taskondemand.core.ServerVersion
import io.github.elvismanchkin.taskondemand.core.arr
import io.github.elvismanchkin.taskondemand.core.arrOrField
import io.github.elvismanchkin.taskondemand.core.at
import io.github.elvismanchkin.taskondemand.core.bool
import io.github.elvismanchkin.taskondemand.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Jira Server / Data Center REST API v2 (Jira 7.x – 9.x). Blocking; call off the EDT.
 *
 * @param version detected server version, or null if unknown. It selects the create-meta API, see [issueTypes].
 */
class JiraClient(connection: ServerConnection, transport: HttpTransport, var version: ServerVersion? = null) {

    private val rest = RestClient(connection, transport)

    val baseUrl: String get() = rest.baseUrl

    fun browseUrl(key: String): String = "$baseUrl/browse/$key"

    fun serverVersion(): ServerVersion? =
        ServerVersion.parse(rest.get("rest/api/2/serverInfo").str("version")).also { version = it }

    /** Display name of the authenticated user; the cheapest call to check a connection. */
    fun myself(): String {
        val json = rest.get("rest/api/2/myself")
        return json.str("displayName") ?: json.str("name") ?: "?"
    }

    fun search(jql: String, maxResults: Int = 50): List<JiraIssue> {
        val body = buildJsonObject {
            put("jql", jql)
            put("maxResults", maxResults)
            putJsonArray("fields") { ISSUE_FIELDS.forEach { add(JsonPrimitive(it)) } }
        }
        return rest.post("rest/api/2/search", body).arr("issues").map(::parseIssue)
    }

    fun issue(key: String): JiraIssue =
        parseIssue(rest.get("rest/api/2/issue/$key", mapOf("fields" to ISSUE_FIELDS.joinToString(","))))

    /**
     * Issue types that can be created in [projectKey].
     *
     * Jira 8.4 added `createmeta/{project}/issuetypes`; Jira 9.0 removed the old `createmeta?expand=` form.
     * Pick by version, and fall back to the other form when the version is unknown or the call 404s.
     */
    fun issueTypes(projectKey: String): List<JiraIssueType> = withCreateMetaFallback(
        modern = {
            rest.get("rest/api/2/issue/createmeta/$projectKey/issuetypes", mapOf("maxResults" to 200))
                .arrOrField("values").map(::parseIssueType)
        },
        legacy = {
            legacyCreateMeta(projectKey, null).arr("issuetypes").map(::parseIssueType)
        },
    )

    fun fields(projectKey: String, issueTypeId: String): List<JiraField> = withCreateMetaFallback(
        modern = {
            rest.get("rest/api/2/issue/createmeta/$projectKey/issuetypes/$issueTypeId", mapOf("maxResults" to 500))
                .arrOrField("values").map { parseField(it.str("fieldId") ?: "", it) }
        },
        legacy = {
            val type = legacyCreateMeta(projectKey, issueTypeId).arr("issuetypes").firstOrNull()
            (type.at("fields") as? JsonObject)?.map { (id, json) -> parseField(id, json) }.orEmpty()
        },
    )

    /** Creates an issue from a `fields` object (see [CreateIssueFields]) and returns the new key. */
    fun createIssue(fields: JsonObject): String {
        val response = rest.post("rest/api/2/issue", buildJsonObject { put("fields", fields) })
        return response.str("key") ?: throw RemoteException("Jira did not return the key of the created issue")
    }

    fun remoteLinks(key: String): List<JiraRemoteLink> =
        (rest.get("rest/api/2/issue/$key/remotelink") as? JsonArray).orEmpty().mapNotNull { link ->
            val url = link.str("object.url") ?: return@mapNotNull null
            JiraRemoteLink(
                title = link.str("object.title") ?: url,
                url = url,
                applicationType = link.str("application.type"),
                applicationName = link.str("application.name"),
            )
        }

    fun transitions(key: String): List<JiraTransition> =
        rest.get("rest/api/2/issue/$key/transitions").arr("transitions").map {
            JiraTransition(it.str("id") ?: "", it.str("name") ?: "?", it.str("to.name"))
        }

    fun transition(key: String, transitionId: String) {
        rest.post("rest/api/2/issue/$key/transitions", buildJsonObject { putJsonObject("transition") { put("id", transitionId) } })
    }

    private fun legacyCreateMeta(projectKey: String, issueTypeId: String?): JsonElement? =
        rest.get(
            "rest/api/2/issue/createmeta",
            mapOf(
                "projectKeys" to projectKey,
                "issuetypeIds" to issueTypeId,
                "expand" to if (issueTypeId != null) "projects.issuetypes.fields" else null,
            ),
        ).arr("projects").firstOrNull() ?: throw RemoteException("Project $projectKey not found or you cannot create issues in it")

    private fun <T> withCreateMetaFallback(modern: () -> T, legacy: () -> T): T {
        val v = version
        return when {
            v != null && v.isAtLeast(8, 4) -> modern()
            v != null -> legacy()
            else -> try {
                modern()
            } catch (e: RemoteException) {
                if (e.status != 404) throw e
                legacy()
            }
        }
    }

    companion object {
        val ISSUE_FIELDS = listOf("summary", "status", "issuetype", "assignee", "priority", "updated")

        fun parseIssue(json: JsonElement): JiraIssue = JiraIssue(
            key = json.str("key") ?: "",
            summary = json.str("fields.summary") ?: "",
            status = json.str("fields.status.name") ?: "",
            statusCategory = json.str("fields.status.statusCategory.key") ?: "",
            issueType = json.str("fields.issuetype.name") ?: "",
            assignee = json.str("fields.assignee.displayName"),
            priority = json.str("fields.priority.name"),
            updated = json.str("fields.updated"),
        )

        private fun parseIssueType(json: JsonElement) =
            JiraIssueType(json.str("id") ?: "", json.str("name") ?: "?", json.bool("subtask") ?: false)

        fun parseField(id: String, json: JsonElement): JiraField = JiraField(
            id = id.ifEmpty { json.str("key") ?: "" },
            name = json.str("name") ?: id,
            required = json.bool("required") ?: false,
            hasDefaultValue = json.bool("hasDefaultValue") ?: false,
            schemaType = json.str("schema.type") ?: "any",
            schemaItems = json.str("schema.items"),
            allowedValues = json.arr("allowedValues").map {
                JiraAllowedValue(it.str("id") ?: "", it.str("name") ?: it.str("value") ?: it.str("id") ?: "?")
            },
        )
    }
}
