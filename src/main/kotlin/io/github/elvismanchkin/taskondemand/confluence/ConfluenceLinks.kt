package io.github.elvismanchkin.taskondemand.confluence

import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** A reference to a Confluence page found in a URL (from Jira remote links, READMEs, ...). */
sealed interface PageRef {
    data class ById(val id: String) : PageRef
    data class ByTitle(val spaceKey: String, val title: String) : PageRef
}

object ConfluenceLinks {

    private val URL_IN_TEXT = Regex("""https?://[^\s<>"'`)\]|]+""")
    private val PAGE_ID = Regex("""[?&]pageId=(\d+)""")
    private val DISPLAY = Regex("""/display/([^/?#]+)/([^?#]+)""")
    private val SPACES_PAGES = Regex("""/spaces/([^/?#]+)/pages/(\d+)""")
    private val TINY = Regex("""/x/([A-Za-z0-9_\-]+)""")

    /** All URLs in [text] that point to the Confluence server at [baseUrl] (scheme and trailing slash ignored). */
    fun findUrls(text: String, baseUrl: String): List<String> {
        if (baseUrl.isBlank()) return emptyList()
        return URL_IN_TEXT.findAll(text)
            .map { it.value.trimEnd('.', ',', ';', ':') }
            .filter { isConfluenceUrl(it, baseUrl) }
            .distinct()
            .toList()
    }

    /** True when [url] is on the server at [baseUrl], including its context path (`wiki.corp` ≠ `wiki.corporate.com`). */
    fun isConfluenceUrl(url: String, baseUrl: String): Boolean {
        val base = stripScheme(baseUrl)
        val candidate = stripScheme(url)
        if (base.isEmpty() || !candidate.startsWith(base)) return false
        return candidate.length == base.length || candidate[base.length] in "/?#"
    }

    fun parse(url: String): PageRef? {
        PAGE_ID.find(url)?.let { return PageRef.ById(it.groupValues[1]) }
        SPACES_PAGES.find(url)?.let { return PageRef.ById(it.groupValues[2]) }
        DISPLAY.find(url)?.let {
            val title = URLDecoder.decode(it.groupValues[2].replace("+", " "), Charsets.UTF_8)
            // Blog posts look like /display/SPACE/2024/01/31/Title. Those are not resolvable by title alone.
            if (!title.contains('/')) return PageRef.ByTitle(URLDecoder.decode(it.groupValues[1], Charsets.UTF_8), title)
        }
        TINY.find(url)?.let { match -> decodeTinyLink(match.groupValues[1])?.let { return PageRef.ById(it.toString()) } }
        return null
    }

    /**
     * Confluence tiny links (`/x/AbCd`) are the page ID as 8 little-endian bytes, base64-encoded, with trailing `A`
     * (zero) padding stripped and `/`, `+` replaced by `-`, `_`.
     */
    fun decodeTinyLink(code: String): Long? = runCatching {
        val base64 = code.replace('-', '/').replace('_', '+').padEnd(11, 'A') + "="
        val bytes = Base64.getDecoder().decode(base64)
        ByteBuffer.wrap(bytes.copyOf(8)).order(ByteOrder.LITTLE_ENDIAN).long
    }.getOrNull()?.takeIf { it > 0 }

    fun encodeTinyLink(pageId: Long): String {
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(pageId).array()
        return Base64.getEncoder().encodeToString(bytes).trimEnd('=').trimEnd('A').replace('/', '-').replace('+', '_')
    }

    private fun stripScheme(url: String): String =
        url.trim().substringAfter("://").trimEnd('/').lowercase().let { stripDefaultPort(it) }

    private fun stripDefaultPort(hostAndPath: String): String =
        hostAndPath.replaceFirst(Regex("""^([^/:]+):(80|443)(?=/|$)"""), "$1")
}
