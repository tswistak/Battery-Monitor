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
*/
package codes.swistak.batterymonitor.common

import org.junit.Assert.*
import org.junit.Test

class ShellCommandExecutorTest {
    @Test
    fun `raw shell reads preserve whitespace and drain output larger than a pipe buffer`() {
        val shell = PrivilegedShellExecutor()
        assertEquals("  first\n second\n", shell.runRaw("printf '  first\n second\n'"))
        assertEquals("first\n second", shell.run("printf '  first\n second\n'"))
        val command = "head -c 150000 /dev/zero | tr '\\000' x"
        assertEquals("x".repeat(150000), shell.runRaw(command))
        assertNull(shell.runRaw("head -c 300000 /dev/zero"))
    }
}
