package io.github.elvismanchkin.taskondemand

import io.github.elvismanchkin.taskondemand.core.AuthType
import io.github.elvismanchkin.taskondemand.core.ServerConnection
import io.github.elvismanchkin.taskondemand.grafana.GrafanaClient
import io.github.elvismanchkin.taskondemand.kibana.KibanaClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObservabilityClientsTest {
    @Test fun `kibana sends xsrf header and api key`() {
        val fake = FakeTransport()
            .on("api/status", body = """{"version":{"number":"6.8.23"}}""")
            .on("api/saved_objects/_find", body = """{"saved_objects":[{"id":"b","attributes":{"title":"logs-*"}},{"id":"a","attributes":{"title":"apm-*"}}]}""")
        val kibana = KibanaClient(ServerConnection("http://kibana:5601", AuthType.API_KEY, secret = "abc"), fake)
        assertEquals("6.8.23", kibana.serverVersion().toString())
        assertEquals(listOf("apm-*", "logs-*"), kibana.indexPatterns().map { it.title })
        assertEquals("true", fake.requests[0].headers["kbn-xsrf"])
        assertEquals("ApiKey abc", fake.requests[0].headers["Authorization"])
        assertEquals("http://kibana:5601/api/saved_objects/_find?type=index-pattern&fields=title&per_page=1000", fake.requests[1].url)
    }

    @Test fun `grafana version falls back to frontend settings on old versions`() {
        val fake = FakeTransport()
            .on("api/health", status = 404)
            .on("api/frontend/settings", body = """{"buildInfo":{"version":"5.4.3"}}""")
        val grafana = GrafanaClient(ServerConnection("https://grafana", AuthType.NONE), fake)
        assertEquals("5.4.3", grafana.serverVersion().toString())
        assertNull(fake.requests[0].headers["Authorization"])
    }

    @Test fun `grafana dashboard search parses slug for old and new versions`() {
        val fake = FakeTransport().on(
            "api/search",
            body = """[{"uid":"u1","title":"JVM","uri":"db/jvm","url":"/d/u1/jvm","folderTitle":"Backend"},
                {"uid":"u2","title":"HTTP","url":"/d/u2/http-overview"}]""",
        )
        val dashboards = GrafanaClient(ServerConnection("https://grafana", AuthType.BEARER, secret = "t"), fake).searchDashboards("j")
        assertEquals(listOf("jvm", "http-overview"), dashboards.map { it.slug })
        assertEquals("Backend / JVM", dashboards[0].toString())
        assertEquals("https://grafana/api/search?type=dash-db&query=j", fake.requests.single().url)
    }
}
