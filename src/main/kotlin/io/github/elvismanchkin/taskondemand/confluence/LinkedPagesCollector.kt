package io.github.elvismanchkin.taskondemand.confluence

import com.intellij.openapi.diagnostic.logger
import io.github.elvismanchkin.taskondemand.core.RemoteException
import io.github.elvismanchkin.taskondemand.jira.JiraRemoteLink
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

enum class PageSource(val label: String) {
    PINNED("Pinned in project settings"),
    JIRA_LINK("Linked from issue"),
    JIRA_MENTION("Mentions issue"),
    REPOSITORY("Linked from repository"),
    SEARCH("Search result"),
}

data class LinkedPage(
    val title: String,
    val url: String,
    val source: PageSource,
    val detail: String? = null,
    val spaceKey: String? = null,
    val id: String? = null,
)

/**
 * Collects the Confluence pages relevant to a project and its current issue, from:
 * pinned page IDs, Jira remote links, pages mentioning the issue key, and Confluence URLs in the repository docs.
 * Blocking; call off the EDT.
 */
class LinkedPagesCollector(
    private val client: ConfluenceClient?,
    private val confluenceBaseUrl: String,
) {

    fun collect(
        pinnedIds: List<String>,
        issueKey: String?,
        issueLinks: List<JiraRemoteLink>,
        repositoryRoot: Path?,
    ): List<LinkedPage> {
        val result = mutableListOf<LinkedPage>()
        pinnedIds.forEach { id -> resolve(PageRef.ById(id), "$confluenceBaseUrl/pages/viewpage.action?pageId=$id", PageSource.PINNED, null)?.let(result::add) }

        issueLinks
            .filter { it.applicationType == CONFLUENCE_APP_TYPE || ConfluenceLinks.isConfluenceUrl(it.url, confluenceBaseUrl) }
            .forEach { link ->
                val ref = ConfluenceLinks.parse(link.url)
                result += (if (ref != null) resolve(ref, link.url, PageSource.JIRA_LINK, issueKey) else null)
                    ?: LinkedPage(link.title, link.url, PageSource.JIRA_LINK, issueKey)
            }

        if (issueKey != null && client != null) {
            try {
                client.search("type = page AND text ~ \"$issueKey\" ORDER BY lastmodified DESC", 10)
                    .forEach { result += it.toLinked(PageSource.JIRA_MENTION, issueKey) }
            } catch (e: RemoteException) {
                LOG.info("Confluence search for $issueKey failed: ${e.message}")
            }
        }

        if (repositoryRoot != null) {
            scanRepository(repositoryRoot).forEachIndexed { index, (url, file) ->
                // Each lookup is a request; past the limit, list the raw links without titles.
                val ref = ConfluenceLinks.parse(url)?.takeIf { index < MAX_RESOLVED_REPOSITORY_LINKS }
                result += (if (ref != null) resolve(ref, url, PageSource.REPOSITORY, file) else null)
                    ?: LinkedPage(url.substringAfterLast('/').ifBlank { url }, url, PageSource.REPOSITORY, file)
            }
        }
        // The same page can be linked several ways; keep the first (most specific) occurrence.
        return result.distinctBy { it.id ?: it.url }
    }

    /** Confluence URLs in README/CONTRIBUTING files and docs folders, mapped to the file that mentions them. */
    fun scanRepository(root: Path): List<Pair<String, String>> {
        if (confluenceBaseUrl.isBlank() || !Files.isDirectory(root)) return emptyList()
        val candidates = mutableListOf<Path>()
        Files.list(root).use { files ->
            files.filter { it.isRegularFile() && ROOT_FILES.any { prefix -> it.name.uppercase().startsWith(prefix) } }
                .forEach(candidates::add)
        }
        DOC_DIRS.map(root::resolve).filter { Files.isDirectory(it) }.forEach { dir ->
            Files.walk(dir, 4).use { walk ->
                walk.filter { it.isRegularFile() && it.extension.lowercase() in DOC_EXTENSIONS }.limit(500).forEach(candidates::add)
            }
        }
        return candidates.flatMap { file ->
            val text = runCatching { if (Files.size(file) > MAX_FILE_SIZE) "" else Files.readString(file) }.getOrDefault("")
            ConfluenceLinks.findUrls(text, confluenceBaseUrl).map { it to root.relativize(file).toString() }
        }.distinctBy { it.first }
    }

    private fun resolve(ref: PageRef, url: String, source: PageSource, detail: String?): LinkedPage? {
        if (client == null) return null
        return try {
            client.resolve(ref)?.toLinked(source, detail)
        } catch (e: RemoteException) {
            LOG.info("Cannot resolve Confluence page $url: ${e.message}")
            null
        }
    }

    private fun ConfluencePage.toLinked(source: PageSource, detail: String?) =
        LinkedPage(title, url, source, detail, spaceKey, id)

    companion object {
        const val CONFLUENCE_APP_TYPE = "com.atlassian.confluence"
        private val LOG = logger<LinkedPagesCollector>()
        private val ROOT_FILES = listOf("README", "CONTRIBUTING", "DEVELOPMENT", "ARCHITECTURE", "RUNBOOK")
        private val DOC_DIRS = listOf("docs", "doc", "documentation", ".github")
        private val DOC_EXTENSIONS = setOf("md", "adoc", "txt", "rst", "markdown")
        private const val MAX_FILE_SIZE = 1_000_000L
        private const val MAX_RESOLVED_REPOSITORY_LINKS = 30
    }
}
