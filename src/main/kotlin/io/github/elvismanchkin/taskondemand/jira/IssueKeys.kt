package io.github.elvismanchkin.taskondemand.jira

/** Extracts Jira issue keys from branch names and builds the default task-list JQL. */
object IssueKeys {

    private val KEY = Regex("""(?<![A-Za-z0-9])([A-Za-z][A-Za-z0-9_]+)-(\d+)(?!\d)""")

    /**
     * Prefixes like `UTF-8`, `JDK-17` or `RFC-7231` look like issue keys. They are ignored only when no project keys are
     * configured; configured keys always win.
     */
    private val NOT_PROJECTS = setOf(
        "UTF", "ISO", "SHA", "MD", "JDK", "JRE", "JAVA", "RFC", "CVE", "HTTP", "TLS", "SSL", "IPV", "ES", "RHEL", "X",
    )

    /**
     * `feature/PROJ-123-login` → `PROJ-123`.
     *
     * With [projectKeys] the match is case-insensitive (`bugfix/proj-7` works) and limited to those projects.
     * Without them only upper-case keys count, so `release-2024` is not mistaken for an issue.
     * Underscores may belong to the key (`MY_PROJ-1`) or separate it (`bugfix_PROJ-1`); both are tried.
     *
     * A non-blank [customPattern] wins. It uses group 1 if present, otherwise the whole match. A bare number
     * (`task/(\d+)`) gets the project key prefixed when exactly one is configured. Anything that isn't a valid key
     * afterwards is ignored, so a number never reaches Jira as an internal issue ID.
     */
    fun fromBranch(branch: String?, projectKeys: Collection<String> = emptyList(), customPattern: String? = null): String? {
        if (branch.isNullOrBlank()) return null
        val keys = projectKeys.map { it.uppercase() }.toSet()
        if (!customPattern.isNullOrBlank()) {
            val match = runCatching { Regex(customPattern).find(branch) }.getOrNull() ?: return null
            val found = (match.groups[1]?.value ?: match.value).trim().uppercase()
            val key = if (found.all(Char::isDigit) && keys.size == 1) "${keys.single()}-$found" else found
            return key.takeIf { isValidKey(it) && (keys.isEmpty() || it.substringBeforeLast('-') in keys) }
        }
        return KEY.findAll(branch).firstNotNullOfOrNull { match ->
            val number = match.groupValues[2]
            projectCandidates(match.groupValues[1])
                .firstOrNull { project ->
                    if (keys.isEmpty()) project == project.uppercase() && project.uppercase() !in NOT_PROJECTS
                    else project.uppercase() in keys
                }
                ?.let { "${it.uppercase()}-$number" }
        }
    }

    /** `bugfix_my_PROJ` → `bugfix_my_PROJ`, `my_PROJ`, `PROJ`: the whole word first, then each part after an `_`. */
    private fun projectCandidates(word: String): List<String> =
        word.indices.filter { it == 0 || word[it - 1] == '_' }.map { word.substring(it) }
            .filter { it.length >= 2 && it.first().isLetter() }

    fun isValidKey(text: String): Boolean = Regex("""[A-Z][A-Z0-9_]+-\d+""").matches(text)

    fun defaultJql(projectKeys: Collection<String>): String {
        val project = if (projectKeys.isEmpty()) "" else "project in (${projectKeys.joinToString(", ")}) AND "
        return "${project}assignee = currentUser() AND resolution = Unresolved ORDER BY updated DESC"
    }

    /** Regex for IntelliJ's Issue Navigation (VCS log, commit messages, console). */
    fun navigationPattern(projectKeys: Collection<String>): String =
        if (projectKeys.isEmpty()) """\b[A-Z][A-Z0-9_]+-\d+\b""" else """\b(?:${projectKeys.joinToString("|")})-\d+\b"""
}
