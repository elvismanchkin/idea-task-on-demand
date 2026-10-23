package io.github.elvismanchkin.taskondemand

import io.github.elvismanchkin.taskondemand.core.ServerVersion
import io.github.elvismanchkin.taskondemand.kibana.KibanaUrls
import io.github.elvismanchkin.taskondemand.kibana.Rison
import org.junit.Assert.assertEquals
import org.junit.Test

class KibanaUrlsTest {
    @Test fun rison() {
        assertEquals("abc", Rison.encode("abc"))
        assertEquals("''", Rison.encode(""))
        assertEquals("'a b'", Rison.encode("a b"))
        assertEquals("'-1h'", Rison.encode("-1h"))
        assertEquals("'it!'s!!'", Rison.encode("it's!"))
        assertEquals("(a:1,b:!t,c:!n,d:!(x,'y z'))", Rison.encode(mapOf("a" to 1, "b" to true, "c" to null, "d" to listOf("x", "y z"))))
    }

    @Test fun `legacy discover link for Kibana 6`() {
        val url = KibanaUrls.discover("https://kibana.local/", ServerVersion.parse("6.8.0"), "abc-123", "application:\"orders\"", "now-15m")
        assertEquals(
            "https://kibana.local/app/kibana#/discover?_g=(time:(from:now-15m,to:now))" +
                "&_a=(index:abc-123,query:(language:kuery,query:'application:%22orders%22'))",
            url,
        )
    }

    @Test fun `new discover route since 7_10 and lucene before 6_3`() {
        assertEquals("/app/discover#/", KibanaUrls.discoverRoute(ServerVersion.parse("7.10.2")))
        assertEquals("/app/kibana#/discover", KibanaUrls.discoverRoute(ServerVersion.parse("7.9.3")))
        assertEquals("/app/kibana#/discover", KibanaUrls.discoverRoute(null))
        assertEquals("lucene", KibanaUrls.queryLanguage(ServerVersion.parse("6.2.4")))
        assertEquals("kuery", KibanaUrls.queryLanguage(ServerVersion.parse("6.3.0")))
    }

    @Test fun `combine query with selection`() {
        assertEquals("(app:x) and \"Null \\\"ptr\\\"\"", KibanaUrls.combineQuery("app:x", " Null \"ptr\" ", "kuery"))
        assertEquals("(app:x) AND \"boom\"", KibanaUrls.combineQuery("app:x", "boom", "lucene"))
        assertEquals("\"boom\"", KibanaUrls.combineQuery("", "boom", "kuery"))
        assertEquals("app:x", KibanaUrls.combineQuery("app:x", "  ", "kuery"))
    }
}
