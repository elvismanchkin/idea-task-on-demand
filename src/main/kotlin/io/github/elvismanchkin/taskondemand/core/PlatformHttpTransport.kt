package io.github.elvismanchkin.taskondemand.core

import com.intellij.util.io.HttpRequests
import java.net.HttpURLConnection

/**
 * Uses the IDE's [HttpRequests], which honours the IDE proxy settings and the certificates trusted under
 * Settings | Tools | Server Certificates. That's how internal-CA and self-signed on-prem servers work.
 */
class PlatformHttpTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpTransport {

    override fun execute(request: HttpRequest): HttpResponse {
        val builder = when (request.method) {
            "GET" -> HttpRequests.request(request.url)
            "POST" -> HttpRequests.post(request.url, request.headers["Content-Type"] ?: "application/json")
            "PUT" -> HttpRequests.put(request.url, request.headers["Content-Type"] ?: "application/json")
            else -> throw IllegalArgumentException("Unsupported method ${request.method}")
        }
        return builder
            .connectTimeout(connectTimeoutMs)
            .readTimeout(readTimeoutMs)
            .throwStatusCodeException(false)
            .productNameAsUserAgent()
            // HttpRequests follows GET redirects itself and runs the tuner again for every hop.
            .tuner { connection ->
                request.headers.forEach { (k, v) ->
                    if (!k.equals("Authorization", ignoreCase = true) || isSameOrigin(request.url, connection.url.toString())) {
                        connection.setRequestProperty(k, v)
                    }
                }
            }
            .connect { r ->
                if (request.body != null) r.write(request.body)
                val connection = r.connection as HttpURLConnection
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val body = stream?.use { it.readBytes().toString(charsetOf(connection.contentType)) }.orEmpty()
                val headers = connection.headerFields.entries
                    .filter { it.key != null }
                    .associate { it.key to it.value.joinToString(",") }
                HttpResponse(status, body, headers)
            }
    }
}

/**
 * Credentials are only sent while a redirect chain stays on the configured scheme, host and port. A redirect to an
 * SSO host, another server or plain http then gets no `Authorization` header and ends as a "login page" error.
 */
fun isSameOrigin(original: String, target: String): Boolean {
    val a = runCatching { java.net.URI(original) }.getOrNull() ?: return false
    val b = runCatching { java.net.URI(target) }.getOrNull() ?: return false
    fun port(u: java.net.URI) = if (u.port != -1) u.port else if (u.scheme.equals("https", true)) 443 else 80
    return a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && port(a) == port(b)
}

/** Charset from a `Content-Type` header. Old Jira/Confluence error pages may still be ISO-8859-1. */
fun charsetOf(contentType: String?): java.nio.charset.Charset {
    val name = contentType?.split(';')?.map { it.trim() }
        ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
        ?.substringAfter('=')?.trim('"', ' ')
    return name?.let { runCatching { java.nio.charset.Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
}
