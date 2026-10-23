package io.github.elvismanchkin.taskondemand

import io.github.elvismanchkin.taskondemand.core.AuthType
import io.github.elvismanchkin.taskondemand.core.AuthenticationException
import io.github.elvismanchkin.taskondemand.core.CaptchaRequiredException
import io.github.elvismanchkin.taskondemand.core.RemoteException
import io.github.elvismanchkin.taskondemand.core.RestClient
import io.github.elvismanchkin.taskondemand.core.ServerConnection
import io.github.elvismanchkin.taskondemand.core.charsetOf
import io.github.elvismanchkin.taskondemand.core.isSameOrigin
import io.github.elvismanchkin.taskondemand.core.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RestClientTest {
    private fun client(fake: FakeTransport, auth: AuthType = AuthType.BASIC) =
        RestClient(ServerConnection("https://srv.local/ctx/", auth, "jdoe", "s3cret"), fake)

    private fun failure(status: Int, body: String = "", headers: Map<String, String> = emptyMap()): RemoteException {
        try {
            client(FakeTransport().on("/ping", status, body, headers)).get("ping")
        } catch (e: RemoteException) {
            return e
        }
        fail("expected an exception")
        throw AssertionError()
    }

    @Test fun `url joins the context path and encodes the query`() {
        val fake = FakeTransport().on("/ctx/a/b", body = """{"ok":"yes"}""")
        assertEquals("yes", client(fake).get("/a/b", mapOf("q" to "x y&z", "skip" to null)).str("ok"))
        assertEquals("https://srv.local/ctx/a/b?q=x%20y%26z", fake.requests.single().url)
    }

    @Test fun `authorization header per auth type`() {
        fun header(auth: AuthType) = FakeTransport().on("/ping", body = "{}").also { client(it, auth).get("ping") }
            .requests.single().headers["Authorization"]
        assertEquals("Basic amRvZTpzM2NyZXQ=", header(AuthType.BASIC))
        assertEquals("Bearer s3cret", header(AuthType.BEARER))
        assertEquals("ApiKey s3cret", header(AuthType.API_KEY))
        assertNull(header(AuthType.NONE))
    }

    @Test fun `status codes and login pages are mapped`() {
        assertTrue(failure(401) is AuthenticationException)
        assertTrue(failure(403, """{"message":"no"}""").message!!.endsWith("no"))
        assertTrue(failure(403, headers = mapOf("x-seraph-loginreason" to "AUTHENTICATION_DENIED")) is CaptchaRequiredException)
        assertEquals("HTTP 500: boom", failure(500, """{"message":"boom"}""").message)
        assertEquals(404, failure(404, "not json").status)
        assertTrue(failure(200, "<html>Sign in</html>").message!!.contains("non-JSON"))
        assertTrue(failure(200, "true").message!!.contains("non-JSON"))
        assertEquals("""{"errors":{"a":{"b":1}}}""", failure(400, """{"errors":{"a":{"b":1}}}""").responseBody)
    }

    @Test fun `credentials only follow redirects to the same origin`() {
        assertTrue(isSameOrigin("https://jira.corp/rest/x", "https://JIRA.corp:443/jira/rest/x"))
        assertFalse(isSameOrigin("https://jira.corp/rest/x", "http://jira.corp/rest/x"))
        assertFalse(isSameOrigin("https://jira.corp/rest/x", "https://sso.corp/login"))
        assertFalse(isSameOrigin("https://jira.corp/rest/x", "https://jira.corp:8443/rest/x"))
    }

    @Test fun `response charset`() {
        assertEquals(Charsets.ISO_8859_1, charsetOf("text/html; charset=ISO-8859-1"))
        assertEquals(Charsets.UTF_8, charsetOf("application/json"))
        assertEquals(Charsets.UTF_8, charsetOf("text/html; charset=bogus"))
        assertEquals(Charsets.UTF_8, charsetOf(null))
    }
}
