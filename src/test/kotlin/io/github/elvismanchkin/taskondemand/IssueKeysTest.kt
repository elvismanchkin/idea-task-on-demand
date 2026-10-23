package io.github.elvismanchkin.taskondemand

import io.github.elvismanchkin.taskondemand.jira.IssueKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import io.github.elvismanchkin.taskondemand.jira.IssueNavigationSync
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueKeysTest {
    @Test fun `typical branch names`() {
        assertEquals("PROJ-123", IssueKeys.fromBranch("feature/PROJ-123-login-page"))
        assertEquals("PROJ-123", IssueKeys.fromBranch("PROJ-123"))
        assertEquals("AB2-7", IssueKeys.fromBranch("bugfix/AB2-7_fix"))
    }

    @Test fun `no key or lower case without project keys`() {
        assertNull(IssueKeys.fromBranch("main"))
        assertNull(IssueKeys.fromBranch("release-2024"))
        assertNull(IssueKeys.fromBranch("bugfix/proj-123"))
        assertNull(IssueKeys.fromBranch(null))
    }

    @Test fun `project keys allow lower case and filter other prefixes`() {
        assertEquals("PROJ-5", IssueKeys.fromBranch("bugfix/proj-5-x", listOf("PROJ")))
        assertEquals("PROJ-9", IssueKeys.fromBranch("feature/UTF-8-PROJ-9", listOf("proj")))
        assertNull(IssueKeys.fromBranch("feature/UTF-8-encoding", listOf("PROJ")))
    }

    @Test fun `does not match inside longer tokens`() {
        assertNull(IssueKeys.fromBranch("feature/XPROJ-1", listOf("PROJ")))
        assertEquals("PROJ-12", IssueKeys.fromBranch("PROJ-12", listOf("PROJ")))
    }

    @Test fun `custom pattern uses group 1`() {
        assertEquals("OPS-42", IssueKeys.fromBranch("task/42-ops", listOf("OPS"), """task/(\d+)"""))
        // A bare number is never used as a key: Jira would read it as an internal issue id.
        assertNull(IssueKeys.fromBranch("task/42-ops", customPattern = """task/(\d+)"""))
        assertNull(IssueKeys.fromBranch("task/../x", customPattern = """task/(.+)"""))
        assertEquals("ABC-1", IssueKeys.fromBranch("x_abc-1_y", customPattern = """([a-z]+-\d+)"""))
        assertNull(IssueKeys.fromBranch("main", customPattern = """([A-Z]+-\d+)"""))
        assertNull(IssueKeys.fromBranch("main", customPattern = """(broken"""))
    }

    @Test fun `default jql and navigation pattern`() {
        assertEquals(
            "project in (A, B) AND assignee = currentUser() AND resolution = Unresolved ORDER BY updated DESC",
            IssueKeys.defaultJql(listOf("A", "B")),
        )
        assertEquals("""\b(?:A|B)-\d+\b""", IssueKeys.navigationPattern(listOf("A", "B")))
    }

    @Test fun `underscore before the key`() {
        assertEquals("PROJ-12", IssueKeys.fromBranch("bugfix_PROJ-12"))
        assertEquals("PROJ-12", IssueKeys.fromBranch("feature/my_PROJ-12_x", listOf("PROJ")))
        assertEquals("MY_PROJ-3", IssueKeys.fromBranch("feature/MY_PROJ-3"))
    }

    @Test fun `common non-issue prefixes are ignored without project keys`() {
        assertNull(IssueKeys.fromBranch("feature/UTF-8-support"))
        assertNull(IssueKeys.fromBranch("JDK-17-upgrade"))
        assertEquals("PROJ-4", IssueKeys.fromBranch("feature/UTF-8-PROJ-4"))
        assertEquals("JDK-17", IssueKeys.fromBranch("JDK-17-upgrade", listOf("JDK")))
    }

    @Test fun `issue navigation rules written by the plugin are recognised`() {
        assertTrue(IssueNavigationSync.isOurRule(IssueKeys.navigationPattern(emptyList()), "https://old/browse/$0"))
        assertTrue(IssueNavigationSync.isOurRule(IssueKeys.navigationPattern(listOf("A", "B_2")), "http://x/jira/browse/$0"))
        assertFalse(IssueNavigationSync.isOurRule("""#(\d+)""", "https://github.com/o/r/issues/$1"))
        assertFalse(IssueNavigationSync.isOurRule(IssueKeys.navigationPattern(emptyList()), "https://youtrack/issue/$0"))
    }
}
