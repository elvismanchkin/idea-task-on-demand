package io.github.elvismanchkin.taskondemand

import io.github.elvismanchkin.taskondemand.core.HttpRequest
import io.github.elvismanchkin.taskondemand.core.HttpResponse
import io.github.elvismanchkin.taskondemand.core.HttpTransport

/** Answers requests by URL substring; records every request for assertions. */
class FakeTransport : HttpTransport {
    private val routes = mutableListOf<Pair<String, HttpResponse>>()
    val requests = mutableListOf<HttpRequest>()

    fun on(urlPart: String, status: Int = 200, body: String = "", headers: Map<String, String> = emptyMap()) = apply {
        routes += urlPart to HttpResponse(status, body, headers)
    }

    override fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return routes.firstOrNull { request.url.contains(it.first) }?.second ?: HttpResponse(404, """{"message":"no route"}""")
    }
}
