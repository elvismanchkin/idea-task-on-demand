package io.github.elvismanchkin.taskondemand

import io.github.elvismanchkin.taskondemand.core.ServerVersion
import io.github.elvismanchkin.taskondemand.grafana.GrafanaUrls
import org.junit.Assert.assertEquals
import org.junit.Test

class GrafanaUrlsTest {
    @Test fun `uid link with variables`() {
        val url = GrafanaUrls.dashboard(
            "https://grafana.local/", ServerVersion.parse("7.5.0"), "AbC12", "jvm-overview", "2",
            GrafanaUrls.parseVariables("service=orders api\nvar-env=prod\n# comment\nbroken"), "now-1h",
        )
        assertEquals("https://grafana.local/d/AbC12/jvm-overview?orgId=2&var-service=orders%20api&var-env=prod&from=now-1h&to=now", url)
    }

    @Test fun `legacy slug link before Grafana 5`() {
        assertEquals(
            "https://g/dashboard/db/jvm?from=now-6h&to=now",
            GrafanaUrls.dashboard("https://g", ServerVersion.parse("4.6.3"), "AbC12", "jvm"),
        )
    }

    @Test fun `multi value variables keep order`() {
        assertEquals(listOf("host" to "a", "host" to "b"), GrafanaUrls.parseVariables("host=a&host=b"))
    }

    @Test fun `values pasted from a url are not encoded twice`() {
        assertEquals(listOf("host" to "web 01"), GrafanaUrls.parseVariables("var-host=web%2001"))
    }

    @Test fun `uid and slug are encoded as path segments`() {
        assertEquals(
            "https://g/d/a%20b/x%3Fy?from=now-6h&to=now",
            GrafanaUrls.dashboard("https://g", ServerVersion.parse("9.0.0"), "a b", "x?y"),
        )
    }
}
