package io.github.elvismanchkin.taskondemand.jira

/** Extracts Jira issue keys from branch names and builds the default task-list JQL. */
object IssueKeys {

    private val KEY = Regex("""(?<![A-Za-z0-9])([A-Za-z][A-Za-z0-9_]+)-(\d+)(?!\d)""")

    /**
     * `feature/PROJ-123-login` → `PROJ-123`.
     *
     * With [projectKeys] the match is case-insensitive (`bugfix/proj-7` works) and limited to those projects.
     * Without them only upper-case keys count, so `release-2024` is not mistaken for an issue.
     * A non-blank [customPattern] wins. It uses group 1 if present, otherwise the whole match.
     */
    fun fromBranch(branch: String?, projectKeys: Collection<String> = emptyList(), customPattern: String? = null): String? {
        if (branch.isNullOrBlank()) return null
        if (!customPattern.isNullOrBlank()) {
            val match = runCatching { Regex(customPattern).find(branch) }.getOrNull() ?: return null
            return (match.groups[1]?.value ?: match.value).uppercase()
        }
        val keys = projectKeys.map { it.uppercase() }.toSet()
        return KEY.findAll(branch)
            .map { it.groupValues[1] to it.groupValues[2] }
            .firstOrNull { (project, _) ->
                if (keys.isEmpty()) project == project.uppercase() && project.first().isLetter() else project.uppercase() in keys
            }
            ?.let { (project, number) -> "${project.uppercase()}-$number" }
    }

    fun isValidKey(text: String): Boolean = Regex("""[A-Z][A-Z0-9_]+-\d+""").matches(text)

    fun defaultJql(projectKeys: Collection<String>): String {
        val project = if (projectKeys.isEmpty()) "" else "project in (${projectKeys.joinToString(", ")}) AND "
        return "${project}assignee = currentUser() AND resolution = Unresolved ORDER BY updated DESC"
    }

    /** Regex for IntelliJ's Issue Navigation (VCS log, commit messages, console). */
    fun navigationPattern(projectKeys: Collection<String>): String =
        if (projectKeys.isEmpty()) """\b[A-Z][A-Z0-9_]+-\d+\b""" else """\b(?:${projectKeys.joinToString("|")})-\d+\b"""
}
