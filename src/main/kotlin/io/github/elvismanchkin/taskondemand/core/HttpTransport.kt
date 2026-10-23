package io.github.elvismanchkin.taskondemand.core

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

data class HttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** Seam between REST clients and the network, so clients can be unit-tested with a fake. */
fun interface HttpTransport {
    fun execute(request: HttpRequest): HttpResponse
}
