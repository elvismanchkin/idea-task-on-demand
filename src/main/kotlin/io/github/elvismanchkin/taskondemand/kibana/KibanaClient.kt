package io.github.elvismanchkin.taskondemand.kibana

import io.github.elvismanchkin.taskondemand.core.HttpTransport
import io.github.elvismanchkin.taskondemand.core.RestClient
import io.github.elvismanchkin.taskondemand.core.ServerConnection
import io.github.elvismanchkin.taskondemand.core.ServerVersion
import io.github.elvismanchkin.taskondemand.core.arr
import io.github.elvismanchkin.taskondemand.core.str

data class KibanaIndexPattern(val id: String, val title: String) {
    override fun toString(): String = title
}

/** Kibana 6.x – 8.x HTTP API. Blocking; call off the EDT. */
class KibanaClient(connection: ServerConnection, transport: HttpTransport) {

    private val rest = object : RestClient(connection, transport) {
        override fun defaultHeaders() = super.defaultHeaders() + ("kbn-xsrf" to "true")
    }

    val baseUrl: String get() = rest.baseUrl

    fun serverVersion(): ServerVersion? = ServerVersion.parse(rest.get("api/status").str("version.number"))

    /** Index patterns (called data views in 8.x); the saved-objects API covers 6.x through 8.x. */
    fun indexPatterns(): List<KibanaIndexPattern> =
        rest.get("api/saved_objects/_find", mapOf("type" to "index-pattern", "fields" to "title", "per_page" to 1000))
            .arr("saved_objects")
            .map { KibanaIndexPattern(it.str("id") ?: "", it.str("attributes.title") ?: it.str("id") ?: "?") }
            .sortedBy { it.title.lowercase() }
}
