package io.github.elvismanchkin.taskondemand.kibana

import io.github.elvismanchkin.taskondemand.core.ServerVersion
import java.net.URLEncoder

/** Builds Discover deep links. The route and query language depend on the Kibana version. */
object KibanaUrls {

    fun discover(
        baseUrl: String,
        version: ServerVersion?,
        indexPatternId: String,
        query: String,
        timeFrom: String = "now-1h",
        timeTo: String = "now",
    ): String {
        val global = mapOf("time" to mapOf("from" to timeFrom.ifBlank { "now-1h" }, "to" to timeTo.ifBlank { "now" }))
        val app = buildMap<String, Any> {
            if (indexPatternId.isNotBlank()) put("index", indexPatternId)
            put("query", mapOf("language" to queryLanguage(version), "query" to query))
        }
        val state = "_g=" + escape(Rison.encode(global)) + "&_a=" + escape(Rison.encode(app))
        return baseUrl.trimEnd('/') + discoverRoute(version) + "?" + state
    }

    /**
     * Kibana 7.10 moved Discover to `/app/discover#/`. Older versions use the legacy `/app/kibana#/discover` hash route.
     * If the version is unknown we assume legacy, because newer Kibana still redirects legacy links.
     */
    fun discoverRoute(version: ServerVersion?): String =
        if (version != null && version.isAtLeast(7, 10)) "/app/discover#/" else "/app/kibana#/discover"

    /**
     * KQL arrived in Kibana 6.3; earlier versions only understand Lucene syntax.
     * An unknown version deliberately means KQL, the exception to "assume legacy": the mapped queries are written for
     * the team's Kibana, which is almost always 6.3+, and a KQL query such as `app:"x" and env:"dev"` run as Lucene
     * silently returns the wrong logs, while the reverse fails visibly.
     */
    fun queryLanguage(version: ServerVersion?): String =
        if (version != null && !version.isAtLeast(6, 3)) "lucene" else "kuery"

    /** Appends free text (an exception message, a trace id) to an application's base query. */
    fun combineQuery(baseQuery: String, extra: String?, language: String): String {
        val text = extra?.trim()?.takeIf { it.isNotEmpty() } ?: return baseQuery
        val phrase = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        if (baseQuery.isBlank()) return phrase
        val and = if (language == "lucene") "AND" else "and"
        return "($baseQuery) $and $phrase"
    }

    /** Keeps the characters Rison relies on readable, and encodes the rest for a URL. */
    private fun escape(rison: String): String =
        URLEncoder.encode(rison, Charsets.UTF_8)
            .replace("+", "%20")
            .replace("%28", "(").replace("%29", ")")
            .replace("%3A", ":").replace("%2C", ",")
            .replace("%27", "'").replace("%21", "!")
}
