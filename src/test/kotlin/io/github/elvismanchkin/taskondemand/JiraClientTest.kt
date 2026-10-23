package io.github.elvismanchkin.taskondemand

import io.github.elvismanchkin.taskondemand.core.AuthType
import io.github.elvismanchkin.taskondemand.core.AuthenticationException
import io.github.elvismanchkin.taskondemand.core.CaptchaRequiredException
import io.github.elvismanchkin.taskondemand.core.RemoteException
import io.github.elvismanchkin.taskondemand.core.ServerConnection
import io.github.elvismanchkin.taskondemand.core.ServerVersion
import io.github.elvismanchkin.taskondemand.jira.CreateIssueFields
import io.github.elvismanchkin.taskondemand.jira.JiraClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JiraClientTest {
    private val connection = ServerConnection("https://jira.local/jira/", AuthType.BASIC, "jdoe", "secret")

    @Test fun `search posts jql with basic auth`() {
        val fake = FakeTransport().on(
            "/rest/api/2/search",
            body = """{"issues":[{"key":"PROJ-1","fields":{"summary":"Login","status":{"name":"Open","statusCategory":{"key":"new"}},
                "issuetype":{"name":"Task"},"assignee":null,"priority":{"name":"Major"}}}]}""",
        )
        val issues = JiraClient(connection, fake).search("project = PROJ")
        assertEquals(1, issues.size)
        assertEquals("PROJ-1", issues[0].key)
        assertEquals("Open", issues[0].status)
        assertEquals(null, issues[0].assignee)
        val request = fake.requests.single()
        assertEquals("https://jira.local/jira/rest/api/2/search", request.url)
        assertEquals("POST", request.method)
        assertEquals("Basic amRvZTpzZWNyZXQ=", request.headers["Authorization"])
        assertTrue(request.body!!.contains("\"jql\":\"project = PROJ\""))
    }

    @Test fun `create meta uses new endpoint from 8_4`() {
        val fake = FakeTransport().on(
            "createmeta/PROJ/issuetypes",
            body = """{"values":[{"id":"10","name":"Task","subtask":false},{"id":"11","name":"Sub-task","subtask":true}]}""",
        )
        val types = JiraClient(connection, fake, ServerVersion.parse("8.20.1")).issueTypes("PROJ")
        assertEquals(listOf("Task", "Sub-task"), types.map { it.name })
        assertTrue(fake.requests.single().url.contains("/rest/api/2/issue/createmeta/PROJ/issuetypes"))
    }

    @Test fun `create meta uses legacy expand form before 8_4`() {
        val fake = FakeTransport().on(
            "createmeta?projectKeys=PROJ&issuetypeIds=10",
            body = """{"projects":[{"key":"PROJ","issuetypes":[{"id":"10","name":"Task","fields":{
                "summary":{"required":true,"name":"Summary","schema":{"type":"string"}},
                "customfield_100":{"required":true,"name":"Team","schema":{"type":"option"},"allowedValues":[{"id":"1","value":"Core"}]}
            }}]}]}""",
        )
        val fields = JiraClient(connection, fake, ServerVersion.parse("7.13.0")).fields("PROJ", "10")
        assertEquals(listOf("summary", "customfield_100"), fields.map { it.id })
        assertEquals("Core", fields[1].allowedValues.single().name)
        assertTrue(fake.requests.single().url.contains("expand=projects.issuetypes.fields"))
    }

    @Test fun `unknown version falls back to legacy create meta on 404`() {
        val fake = FakeTransport()
            .on("createmeta/PROJ/issuetypes", status = 404)
            .on("createmeta?projectKeys=PROJ", body = """{"projects":[{"issuetypes":[{"id":"3","name":"Bug"}]}]}""")
        assertEquals("Bug", JiraClient(connection, fake).issueTypes("PROJ").single().name)
        assertEquals(2, fake.requests.size)
    }

    @Test fun `remote links`() {
        val fake = FakeTransport().on(
            "/remotelink",
            body = """[{"application":{"type":"com.atlassian.confluence","name":"Wiki"},
                "object":{"url":"https://wiki/pages/viewpage.action?pageId=5","title":"Design"}},
                {"object":{"url":"https://ci/build/1","title":"Build"}}, {"object":{}}]""",
        )
        val links = JiraClient(connection, fake).remoteLinks("PROJ-1")
        assertEquals(listOf("Design", "Build"), links.map { it.title })
        assertEquals("com.atlassian.confluence", links[0].applicationType)
    }

    @Test fun `errors are mapped`() {
        fun failure(status: Int, body: String = "", headers: Map<String, String> = emptyMap()): RemoteException {
            val client = JiraClient(connection, FakeTransport().on("myself", status, body, headers))
            try {
                client.myself()
            } catch (e: RemoteException) {
                return e
            }
            fail("expected an exception")
            throw AssertionError()
        }
        assertTrue(failure(401) is AuthenticationException)
        assertTrue(failure(403, headers = mapOf("X-Seraph-LoginReason" to "AUTHENTICATION_DENIED")) is CaptchaRequiredException)
        assertEquals("HTTP 400: Field 'x' is wrong; customfield_1: required", failure(400, """{"errorMessages":["Field 'x' is wrong"],"errors":{"customfield_1":"required"}}""").message)
        assertEquals("HTTP 400: Field \"summary\" is required", failure(400, """{"errorMessages":["Field \"summary\" is required"]}""").message)
        assertTrue(failure(200, "<html>login</html>").message!!.contains("non-JSON"))
    }

    @Test fun `create issue payload`() {
        val fake = FakeTransport().on("/rest/api/2/issue", status = 201, body = """{"id":"1","key":"PROJ-77"}""")
        val team = JiraClient.parseField("customfield_100", io.github.elvismanchkin.taskondemand.core.parseJson(
            """{"required":true,"name":"Team","schema":{"type":"option"},"allowedValues":[{"id":"1","value":"Core"}]}""",
        ))
        val labels = JiraClient.parseField("labels", io.github.elvismanchkin.taskondemand.core.parseJson(
            """{"required":false,"name":"Labels","schema":{"type":"array","items":"string"}}""",
        ))
        val payload = CreateIssueFields.build("PROJ", "10", " Fix it ", null, mapOf(team to "1", labels to "a, b"))
        assertEquals(
            """{"project":{"key":"PROJ"},"issuetype":{"id":"10"},"summary":"Fix it","customfield_100":{"id":"1"},"labels":["a","b"]}""",
            payload.toString(),
        )
        assertEquals("PROJ-77", JiraClient(connection, fake).createIssue(payload))
    }

    @Test fun `modern create meta reads every page`() {
        val fake = FakeTransport()
            .on("startAt=0", body = """{"startAt":0,"maxResults":1,"total":2,"isLast":false,"values":[{"id":"1","name":"Task"}]}""")
            .on("startAt=1", body = """{"startAt":1,"maxResults":1,"total":2,"isLast":true,"values":[{"id":"2","name":"Bug"}]}""")
        val types = JiraClient(connection, fake, ServerVersion.parse("9.4.0")).issueTypes("PROJ")
        assertEquals(listOf("Task", "Bug"), types.map { it.name })
    }

    @Test fun `jira error on modern create meta is not hidden by the legacy fallback`() {
        val fake = FakeTransport().on("createmeta/NOPE/issuetypes", 404, """{"errorMessages":["Project NOPE not found"],"errors":{}}""")
        try {
            JiraClient(connection, fake).issueTypes("NOPE")
            fail("expected an exception")
        } catch (e: RemoteException) {
            assertTrue(e.message!!.contains("Project NOPE not found"))
        }
        assertEquals(1, fake.requests.size)
    }
}
