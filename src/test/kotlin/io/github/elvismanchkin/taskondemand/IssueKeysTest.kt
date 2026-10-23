package io.github.elvismanchkin.taskondemand

import io.github.elvismanchkin.taskondemand.jira.IssueKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals("OPS-42", IssueKeys.fromBranch("task/42-ops", customPattern = """task/(\d+)""")?.let { "OPS-$it" })
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
}
