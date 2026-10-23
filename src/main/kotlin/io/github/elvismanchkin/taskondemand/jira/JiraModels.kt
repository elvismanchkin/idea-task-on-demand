package io.github.elvismanchkin.taskondemand.jira

data class JiraIssue(
    val key: String,
    val summary: String,
    val status: String,
    /** `new`, `indeterminate` or `done`; empty on very old Jira. */
    val statusCategory: String,
    val issueType: String,
    val assignee: String?,
    val priority: String?,
    val updated: String?,
)

data class JiraIssueType(val id: String, val name: String, val subtask: Boolean) {
    override fun toString(): String = name
}

data class JiraAllowedValue(val id: String, val name: String) {
    override fun toString(): String = name
}

data class JiraField(
    val id: String,
    val name: String,
    val required: Boolean,
    val hasDefaultValue: Boolean,
    /** `schema.type`: string, number, option, array, user, priority, date, ... */
    val schemaType: String,
    /** `schema.items` for arrays: option, component, version, string, ... */
    val schemaItems: String?,
    val allowedValues: List<JiraAllowedValue>,
)

data class JiraRemoteLink(val title: String, val url: String, val applicationType: String?, val applicationName: String?)

data class JiraTransition(val id: String, val name: String, val toStatus: String?) {
    override fun toString(): String = if (toStatus == null || toStatus == name) name else "$name → $toStatus"
}
