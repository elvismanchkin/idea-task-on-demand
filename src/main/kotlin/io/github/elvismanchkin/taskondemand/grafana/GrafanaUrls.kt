package io.github.elvismanchkin.taskondemand.grafana

import io.github.elvismanchkin.taskondemand.core.ServerVersion
import java.net.URLEncoder

object GrafanaUrls {

    /**
     * Dashboard link with template variables and time range.
     * Grafana 5.0+ addresses dashboards by UID (`/d/{uid}/{slug}`). Older versions only know `/dashboard/db/{slug}`.
     */
    fun dashboard(
        baseUrl: String,
        version: ServerVersion?,
        uid: String,
        slug: String,
        orgId: String = "",
        variables: List<Pair<String, String>> = emptyList(),
        from: String = "now-6h",
        to: String = "now",
    ): String {
        val legacy = (version != null && !version.isAtLeast(5)) || uid.isBlank()
        val path = if (legacy) "/dashboard/db/${slug.ifBlank { uid }}" else "/d/$uid" + if (slug.isNotBlank()) "/$slug" else ""
        val params = buildList {
            if (orgId.isNotBlank()) add("orgId" to orgId)
            variables.forEach { (name, value) -> add("var-$name" to value) }
            add("from" to from.ifBlank { "now-6h" })
            add("to" to to.ifBlank { "now" })
        }
        return baseUrl.trimEnd('/') + path + "?" + params.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
    }

    /** Parses `name=value` lines (also `&`-separated). Repeating a name gives a multi-value variable. */
    fun parseVariables(text: String): List<Pair<String, String>> =
        text.split('\n', '&')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .map { it.substringBefore('=').trim().removePrefix("var-") to it.substringAfter('=').trim() }
            .filter { it.first.isNotEmpty() }

    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}
