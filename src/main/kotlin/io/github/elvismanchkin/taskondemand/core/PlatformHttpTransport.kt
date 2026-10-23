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
            .tuner { connection -> request.headers.forEach { (k, v) -> connection.setRequestProperty(k, v) } }
            .connect { r ->
                if (request.body != null) r.write(request.body)
                val connection = r.connection as HttpURLConnection
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                val headers = connection.headerFields.entries
                    .filter { it.key != null }
                    .associate { it.key to it.value.joinToString(",") }
                HttpResponse(status, body, headers)
            }
    }
}
