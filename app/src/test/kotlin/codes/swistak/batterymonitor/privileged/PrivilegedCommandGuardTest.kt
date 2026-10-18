/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/
package codes.swistak.batterymonitor.privileged

import codes.swistak.batterymonitor.common.CommandExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivilegedCommandGuardTest {
    @Test
    fun `revoked access prevents regular and raw delegate launches`() {
        val executor = GuardedPrivilegedCommandExecutor(object : CommandExecutor {
            override fun run(command: String): String = error("Revoked access launched a command")
        }) { false }

        assertNull(executor.run("id"))
        assertNull(executor.runRaw("dumpsys battery"))
    }

    @Test
    fun `disabling access during either command discards output and prevents later launches`() {
        for (raw in listOf(false, true)) {
            var enabled = true
            val commands = mutableListOf<String>()
            val executor = GuardedPrivilegedCommandExecutor(object : CommandExecutor {
                override fun run(command: String): String {
                    commands += command
                    enabled = false
                    return "late output"
                }
            }) { enabled }

            val output = if (raw) executor.runRaw("in flight") else executor.run("in flight")

            assertNull(output)
            assertNull(executor.run("next regular"))
            assertNull(executor.runRaw("next raw"))
            assertEquals(listOf("in flight"), commands)
        }
    }

    @Test
    fun `simulated Binder death revision discards late output and prevents later collection commands`() {
        var revision = 0L
        val capturedRevision = revision
        val allowed = { revision == capturedRevision }

        val output = whilePrivilegedAccessAllowed(allowed) {
            // Simulate Binder death's revision change without loading the Android singleton.
            revision++
            "late Binder output"
        }
        val executor = GuardedPrivilegedCommandExecutor(object : CommandExecutor {
            override fun run(command: String): String = error("Stale revision launched a command")
        }, allowed)

        assertEquals(1L, revision)
        assertNull(output)
        assertNull(executor.run("ls /sys/class/power_supply"))
        assertNull(executor.runRaw("dumpsys battery"))
    }

    @Test
    fun `unchanged access preserves raw multiline and empty output`() {
        val values = mapOf("multiline" to "  first\n second\n", "empty" to "")
        val commands = mutableListOf<String>()
        val executor = GuardedPrivilegedCommandExecutor(object : CommandExecutor {
            override fun run(command: String): String = error("Raw command used the regular reader")

            override fun runRaw(command: String): String {
                commands += command
                return values.getValue(command)
            }
        }) { true }

        assertEquals("  first\n second\n", executor.runRaw("multiline"))
        assertEquals("", executor.runRaw("empty"))
        assertEquals(listOf("multiline", "empty"), commands)
    }
}
