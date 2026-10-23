package io.github.elvismanchkin.taskondemand.core

import kotlinx.serialization.json.JsonElement
import java.net.URLEncoder
import java.util.Base64

data class ServerConnection(
    val baseUrl: String,
    val authType: AuthType,
    val username: String = "",
    val secret: String = "",
) {
    val normalizedUrl: String get() = baseUrl.trim().trimEnd('/')
    val isConfigured: Boolean get() = normalizedUrl.isNotEmpty()
}

open class RemoteException(message: String, val status: Int = 0, cause: Throwable? = null) : Exception(message, cause)

/** Jira/Confluence lock Basic auth behind a CAPTCHA after repeated failures; retrying only makes it worse. */
class CaptchaRequiredException(serverUrl: String) : RemoteException(
    "Login now requires a CAPTCHA. Log in once in the browser at $serverUrl, then retry.", 403,
)

class AuthenticationException(message: String, status: Int) : RemoteException(message, status)

/** Thin JSON-over-HTTP client shared by all integrations. Blocking; call from a background thread. */
open class RestClient(val connection: ServerConnection, private val transport: HttpTransport) {

    val baseUrl: String get() = connection.normalizedUrl

    protected open fun defaultHeaders(): Map<String, String> = buildMap {
        put("Accept", "application/json")
        authorizationHeader(connection)?.let { put("Authorization", it) }
    }

    fun get(path: String, query: Map<String, Any?> = emptyMap()): JsonElement =
        send(HttpRequest("GET", url(path, query), defaultHeaders()))

    fun post(path: String, body: JsonElement, extraHeaders: Map<String, String> = emptyMap()): JsonElement =
        send(
            HttpRequest(
                "POST", url(path), defaultHeaders() + ("Content-Type" to "application/json") + extraHeaders,
                body.toString(),
            ),
        )

    fun url(path: String, query: Map<String, Any?> = emptyMap()): String {
        val params = query.filterValues { it != null }
            .map { (k, v) -> "${encode(k)}=${encode(v.toString())}" }
        return baseUrl + "/" + path.trimStart('/') + if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }

    private fun send(request: HttpRequest): JsonElement {
        if (!connection.isConfigured) throw RemoteException("Server URL is not configured")
        val response = try {
            transport.execute(request)
        } catch (e: java.io.IOException) {
            throw RemoteException("Cannot reach $baseUrl: ${e.message}", cause = e)
        }
        checkStatus(response)
        if (response.body.isBlank()) return kotlinx.serialization.json.JsonNull
        // SSO proxies typically answer with an HTML login page. The tree parser accepts bare literals, so check the shape first.
        val body = response.body.trimStart()
        val parsed = if (body.startsWith('{') || body.startsWith('[')) runCatching { parseJson(body) }.getOrNull() else null
        return parsed ?: throw RemoteException("Unexpected non-JSON response from $baseUrl (login page or proxy?)", response.status)
    }

    private fun checkStatus(response: HttpResponse) {
        if (response.header("X-Seraph-LoginReason")?.contains("AUTHENTICATION_DENIED") == true) {
            throw CaptchaRequiredException(baseUrl)
        }
        when (response.status) {
            in 200..299 -> return
            401 -> throw AuthenticationException("Authentication failed (401). Check the credentials.", 401)
            403 -> throw AuthenticationException("Access denied (403).${errorDetails(response)}", 403)
            else -> throw RemoteException("HTTP ${response.status}${errorDetails(response)}", response.status)
        }
    }

    private fun errorDetails(response: HttpResponse): String {
        val json = runCatching { parseJson(response.body) }.getOrNull() ?: return ""
        // Jira: errorMessages[] + errors{}; Confluence/Kibana/Grafana: message.
        val messages = json.arr("errorMessages").mapNotNull { it.str("") ?: it.toString().trim('"') } +
            ((json.at("errors") as? kotlinx.serialization.json.JsonObject)?.map { (k, v) -> "$k: ${v.toString().trim('"')}" }
                .orEmpty()) +
            listOfNotNull(json.str("message"))
        return if (messages.isEmpty()) "" else ": " + messages.joinToString("; ")
    }

    companion object {
        fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

        fun authorizationHeader(c: ServerConnection): String? = when (c.authType) {
            AuthType.NONE -> null
            AuthType.BASIC -> "Basic " + Base64.getEncoder()
                .encodeToString("${c.username}:${c.secret}".toByteArray(Charsets.UTF_8))
            AuthType.BEARER -> "Bearer ${c.secret}"
            AuthType.API_KEY -> "ApiKey ${c.secret}"
        }
    }
}
