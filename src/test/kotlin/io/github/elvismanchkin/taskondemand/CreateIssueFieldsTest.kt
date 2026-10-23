package io.github.elvismanchkin.taskondemand

import io.github.elvismanchkin.taskondemand.jira.CreateIssueFields
import io.github.elvismanchkin.taskondemand.jira.CreateIssueFields.Editor
import io.github.elvismanchkin.taskondemand.jira.JiraAllowedValue
import io.github.elvismanchkin.taskondemand.jira.JiraField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CreateIssueFieldsTest {
    private fun field(id: String, type: String, items: String? = null, required: Boolean = false, values: Int = 0, default: Boolean = false) =
        JiraField(id, id, required, default, type, items, (1..values).map { JiraAllowedValue("$it", "v$it") })

    @Test fun editors() {
        assertEquals(Editor.TEXT, CreateIssueFields.editorFor(field("a", "string")))
        assertEquals(Editor.SELECT, CreateIssueFields.editorFor(field("a", "option", values = 2)))
        assertEquals(Editor.UNSUPPORTED, CreateIssueFields.editorFor(field("a", "option")))
        assertEquals(Editor.MULTI_SELECT, CreateIssueFields.editorFor(field("components", "array", "component", values = 1)))
        assertEquals(Editor.LABELS, CreateIssueFields.editorFor(field("labels", "array", "string")))
        assertEquals(Editor.UNSUPPORTED, CreateIssueFields.editorFor(field("x", "any")))
    }

    @Test fun `editable and unsupported required`() {
        val fields = listOf(
            field("summary", "string", required = true),
            field("customfield_1", "string", required = true),
            field("customfield_2", "any", required = true),
            field("customfield_3", "any", required = true, default = true),
            field("priority", "priority", values = 3),
            field("environment", "string"),
        )
        assertEquals(listOf("customfield_1", "priority"), CreateIssueFields.editable(fields).map { it.id })
        assertEquals(listOf("customfield_2"), CreateIssueFields.unsupportedRequired(fields).map { it.id })
    }

    @Test fun `json values`() {
        assertNull(CreateIssueFields.toJson(field("a", "string"), "  "))
        assertEquals("5.5", CreateIssueFields.toJson(field("n", "number"), "5.5").toString())
        assertNull(CreateIssueFields.toJson(field("n", "number"), "abc"))
        assertEquals("""{"name":"jdoe"}""", CreateIssueFields.toJson(field("assignee", "user"), "jdoe").toString())
        assertEquals("\"v1\"", CreateIssueFields.toJson(field("s", "string", values = 1), "v1").toString())
        assertEquals("""[{"id":"1"},{"id":"2"}]""", CreateIssueFields.toJson(field("c", "array", "component", values = 2), listOf("1", "2")).toString())
    }

    @Test fun `datetime fields get the full Jira format`() {
        val due = field("customfield_7", "datetime")
        assertEquals(Editor.DATETIME, CreateIssueFields.editorFor(due))
        assertEquals("\"2024-05-01T00:00:00.000+0000\"", CreateIssueFields.toJson(due, "2024-05-01").toString())
        assertEquals("\"2024-05-01T13:30:00.000+0000\"", CreateIssueFields.toJson(due, "2024-05-01 13:30").toString())
        assertNull(CreateIssueFields.toJson(due, "tomorrow"))
        assertEquals("\"2024-05-01\"", CreateIssueFields.toJson(field("duedate", "date"), "2024-05-01").toString())
    }

    @Test fun `string arrays with allowed values send the values themselves`() {
        val strings = field("customfield_8", "array", "string", values = 2)
        assertEquals(Editor.MULTI_SELECT, CreateIssueFields.editorFor(strings))
        assertEquals("""["v1","v2"]""", CreateIssueFields.toJson(strings, listOf("v1", "v2")).toString())
        val components = field("components", "array", "component", values = 2)
        assertEquals("""[{"id":"1"}]""", CreateIssueFields.toJson(components, listOf("1")).toString())
    }
}
