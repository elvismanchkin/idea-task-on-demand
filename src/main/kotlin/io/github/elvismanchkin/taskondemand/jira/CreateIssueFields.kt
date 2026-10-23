package io.github.elvismanchkin.taskondemand.jira

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Maps Jira create-meta field descriptions to editor kinds and to the JSON Jira expects for each. */
object CreateIssueFields {

    /** Fields the create dialog always handles itself, or that Jira fills in. */
    val BUILT_IN = setOf("project", "issuetype", "summary", "description", "reporter", "attachment", "issuelinks")

    enum class Editor { TEXT, MULTILINE, NUMBER, DATE, DATETIME, SELECT, MULTI_SELECT, LABELS, USER, UNSUPPORTED }

    fun editorFor(field: JiraField): Editor = when (field.schemaType) {
        "string" -> if (field.allowedValues.isNotEmpty()) Editor.SELECT else Editor.TEXT
        "number" -> Editor.NUMBER
        "date" -> Editor.DATE
        "datetime" -> Editor.DATETIME
        "user" -> Editor.USER
        "option", "priority", "version", "component", "resolution", "securitylevel" ->
            if (field.allowedValues.isNotEmpty()) Editor.SELECT else Editor.UNSUPPORTED
        "array" -> when {
            field.schemaItems == "string" && field.allowedValues.isEmpty() -> Editor.LABELS
            field.allowedValues.isNotEmpty() -> Editor.MULTI_SELECT
            else -> Editor.UNSUPPORTED
        }
        else -> Editor.UNSUPPORTED
    }

    /** Required fields with no default that the dialog cannot edit; Jira will reject the issue if they're empty. */
    fun unsupportedRequired(fields: List<JiraField>): List<JiraField> =
        fields.filter { it.required && !it.hasDefaultValue && it.id !in BUILT_IN && editorFor(it) == Editor.UNSUPPORTED }

    /** The fields the dialog shows: required ones plus a few commonly used optional ones. */
    fun editable(fields: List<JiraField>): List<JiraField> = fields.filter {
        it.id !in BUILT_IN && editorFor(it) != Editor.UNSUPPORTED &&
            (it.required || it.id in COMMON_OPTIONAL)
    }

    private val COMMON_OPTIONAL = setOf("priority", "components", "labels", "fixVersions", "assignee", "duedate")

    /**
     * Converts a raw editor value into Jira's JSON shape, or null when it's empty.
     * SELECT/MULTI_SELECT values are allowed-value IDs.
     */
    fun toJson(field: JiraField, value: Any?): JsonElement? {
        if (value == null || (value is String && value.isBlank()) || (value is Collection<*> && value.isEmpty())) return null
        return when (editorFor(field)) {
            Editor.TEXT, Editor.MULTILINE, Editor.DATE -> JsonPrimitive(value.toString().trim())
            // Jira Server only accepts the full `yyyy-MM-dd'T'HH:mm:ss.SSSZ` form for datetime fields.
            Editor.DATETIME -> JsonPrimitive(toJiraDateTime(value.toString().trim()) ?: return null)
            Editor.NUMBER -> JsonPrimitive(value.toString().trim().toBigDecimalOrNull() ?: return null)
            // Jira Server identifies users by name, not accountId.
            Editor.USER -> buildJsonObject { put("name", value.toString().trim()) }
            Editor.SELECT -> if (field.schemaType == "string") JsonPrimitive(value.toString()) else idObject(value.toString())
            // Arrays of plain strings (e.g. a multi-select of string values) take the values themselves, others take ids.
            Editor.MULTI_SELECT -> buildJsonArray {
                (value as Collection<*>).forEach { add(if (field.schemaItems == "string") JsonPrimitive(it.toString()) else idObject(it.toString())) }
            }
            Editor.LABELS -> buildJsonArray {
                value.toString().split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(JsonPrimitive(it)) }
            }
            Editor.UNSUPPORTED -> null
        }
    }

    fun build(
        projectKey: String,
        issueTypeId: String,
        summary: String,
        /** Null when the create screen has no description field (Jira rejects unknown fields). */
        description: String?,
        extra: Map<JiraField, Any?>,
    ): JsonObject = buildJsonObject {
        put("project", buildJsonObject { put("key", projectKey) })
        put("issuetype", idObject(issueTypeId))
        put("summary", summary.trim())
        if (!description.isNullOrBlank()) put("description", description)
        extra.forEach { (field, value) -> toJson(field, value)?.let { put(field.id, it) } }
    }

    private fun idObject(id: String) = buildJsonObject { put("id", id) }

    private val DATE_TIME = Regex("""(\d{4}-\d{2}-\d{2})(?:[ T](\d{2}):(\d{2}))?""")

    /** `2024-05-01` or `2024-05-01 13:30` → `2024-05-01T13:30:00.000+0000` (UTC); null if it doesn't parse. */
    fun toJiraDateTime(text: String): String? {
        val match = DATE_TIME.matchEntire(text) ?: return null
        val (date, hour, minute) = match.destructured
        return "${date}T${hour.ifEmpty { "00" }}:${minute.ifEmpty { "00" }}:00.000+0000"
    }
}
