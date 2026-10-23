package io.github.elvismanchkin.taskondemand

import io.github.elvismanchkin.taskondemand.core.ServerVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerVersionTest {
    @Test fun parsing() {
        assertEquals(listOf(8, 13, 2), ServerVersion.parse("8.13.2")!!.parts)
        assertEquals(listOf(7, 10, 0), ServerVersion.parse("7.10.0-SNAPSHOT")!!.parts)
        assertEquals(listOf(6, 5, 4), ServerVersion.parse("v6.5.4")!!.parts)
        assertNull(ServerVersion.parse(""))
        assertNull(ServerVersion.parse(null))
    }

    @Test fun comparison() {
        val v = ServerVersion.parse("8.4")!!
        assertTrue(v.isAtLeast(8, 4))
        assertTrue(v.isAtLeast(8))
        assertFalse(v.isAtLeast(8, 4, 1))
        assertTrue(ServerVersion.parse("10.0")!! > ServerVersion.parse("9.12.3")!!)
    }

    @Test fun `overlong numbers are not a version`() {
        assertNull(ServerVersion.parse("20240101123456"))
    }
}
