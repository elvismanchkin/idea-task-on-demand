package io.github.elvismanchkin.taskondemand

import io.github.elvismanchkin.taskondemand.confluence.ConfluenceLinks
import io.github.elvismanchkin.taskondemand.confluence.LinkedPagesCollector
import io.github.elvismanchkin.taskondemand.confluence.PageRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import io.github.elvismanchkin.taskondemand.confluence.ConfluenceClient
import io.github.elvismanchkin.taskondemand.core.AuthType
import io.github.elvismanchkin.taskondemand.core.RemoteException
import io.github.elvismanchkin.taskondemand.core.ServerConnection
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

class ConfluenceLinksTest {
    @Test fun `parse legacy url shapes`() {
        assertEquals(PageRef.ById("12345"), ConfluenceLinks.parse("https://wiki/pages/viewpage.action?pageId=12345"))
        assertEquals(PageRef.ById("777"), ConfluenceLinks.parse("https://wiki/spaces/DEV/pages/777/Title"))
        assertEquals(PageRef.ByTitle("DEV", "Release Notes 2.0"), ConfluenceLinks.parse("https://wiki/display/DEV/Release+Notes+2.0"))
        assertEquals(PageRef.ByTitle("DEV", "A&B"), ConfluenceLinks.parse("https://wiki/display/DEV/A%26B#anchor"))
        assertNull(ConfluenceLinks.parse("https://wiki/display/DEV/2024/01/31/Blog"))
        assertNull(ConfluenceLinks.parse("https://wiki/dashboard.action"))
    }

    @Test fun `tiny links round trip`() {
        for (id in listOf(1L, 65537L, 123456789L, 2_147_483_648L)) {
            val code = ConfluenceLinks.encodeTinyLink(id)
            assertEquals(id, ConfluenceLinks.decodeTinyLink(code))
            assertEquals(PageRef.ById(id.toString()), ConfluenceLinks.parse("https://wiki/x/$code"))
        }
    }

    @Test fun `find urls of the configured server only`() {
        val text = """
            See [design](https://wiki.corp/confluence/pages/viewpage.action?pageId=42). Also http://wiki.corp/confluence/display/OPS/Runbook,
            and https://other.corp/x/abc or <https://wiki.corp/confluence/x/AbC>.
        """.trimIndent()
        assertEquals(
            listOf(
                "https://wiki.corp/confluence/pages/viewpage.action?pageId=42",
                "http://wiki.corp/confluence/display/OPS/Runbook",
                "https://wiki.corp/confluence/x/AbC",
            ),
            ConfluenceLinks.findUrls(text, "https://wiki.corp:443/confluence/"),
        )
    }

    @Test fun `server prefix must end at a path boundary`() {
        assertEquals(true, ConfluenceLinks.isConfluenceUrl("https://wiki.corp/display/A/B", "https://wiki.corp"))
        assertEquals(true, ConfluenceLinks.isConfluenceUrl("http://WIKI.corp:443/x/1", "https://wiki.corp/"))
        assertEquals(false, ConfluenceLinks.isConfluenceUrl("https://wiki.corporate.com/display/A/B", "https://wiki.corp"))
        assertEquals(false, ConfluenceLinks.isConfluenceUrl("https://wiki.corp/confluence2/x", "https://wiki.corp/confluence"))
    }

    @Test fun `scan repository docs`() {
        val root = Files.createTempDirectory("tod")
        Files.writeString(root.resolve("README.md"), "Docs: https://wiki.corp/display/DEV/Home")
        Files.createDirectories(root.resolve("docs/arch"))
        Files.writeString(root.resolve("docs/arch/overview.adoc"), "https://wiki.corp/pages/viewpage.action?pageId=9")
        Files.writeString(root.resolve("docs/arch/image.png"), "https://wiki.corp/pages/viewpage.action?pageId=10")
        Files.writeString(root.resolve("Main.kt"), "https://wiki.corp/pages/viewpage.action?pageId=11")

        val found = LinkedPagesCollector(null, "https://wiki.corp").scanRepository(root).toMap()
        assertEquals(
            mapOf(
                "https://wiki.corp/display/DEV/Home" to "README.md",
                "https://wiki.corp/pages/viewpage.action?pageId=9" to "docs/arch/overview.adoc",
            ),
            found,
        )
    }

    @Test fun `tiny link only right after the host or context path`() {
        assertEquals(PageRef.ById("1"), ConfluenceLinks.parse("https://wiki/confluence/x/AQ"))
        assertNull(ConfluenceLinks.parse("https://wiki/download/attachments/1/x/AQ"))
    }

    @Test fun `parentheses inside a url are kept`() {
        assertEquals(
            listOf("https://wiki/display/DEV/Foo+(bar)"),
            ConfluenceLinks.findUrls("[Foo](https://wiki/display/DEV/Foo+(bar)) and (https://wiki/x/AQ", "https://wiki").take(1),
        )
        assertEquals(listOf("https://wiki/x/AQ"), ConfluenceLinks.findUrls("(see https://wiki/x/AQ)", "https://wiki"))
    }

    @Test fun `cql quotes the issue key as a phrase and escapes search text`() {
        assertEquals("type = page AND text ~ \"\\\"PROJ-1\\\"\" ORDER BY lastmodified DESC", ConfluenceClient.mentionsCql("PROJ-1"))
        assertEquals(
            "type = page AND (title ~ \"release notes\" OR text ~ \"release notes\") AND space = \"DEV\" ORDER BY lastmodified DESC",
            ConfluenceClient.textSearchCql("release notes", "DEV"),
        )
        assertEquals("\"\\\"at Foo.bar(Foo.java:12)\\\"\"", ConfluenceClient.phrase("at Foo.bar(Foo.java:12)"))
        assertTrue(ConfluenceClient.textSearchCql("at Foo.bar(Foo.java:12)", null).contains(ConfluenceClient.phrase("at Foo.bar(Foo.java:12)")))
    }

    @Test fun `unresolvable pinned pages still show and search needs 5_7`() {
        val fake = FakeTransport() // every request answers 404
        val client = ConfluenceClient(ServerConnection("https://wiki", AuthType.NONE), fake)
        val pages = LinkedPagesCollector(client, "https://wiki").collect(listOf("42"), "PROJ-1", emptyList(), null)
        assertEquals(listOf("https://wiki/pages/viewpage.action?pageId=42"), pages.map { it.url })
        try {
            client.search("type = page")
            fail("expected an exception")
        } catch (e: RemoteException) {
            assertTrue(e.message!!.contains("5.7"))
        }
    }
}
