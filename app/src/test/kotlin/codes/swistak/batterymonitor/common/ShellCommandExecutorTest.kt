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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

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

    @Test
    fun `interrupted shell waits preserve interruption and skip later commands`() {
        val directory = Files.createTempDirectory("battery-shell-interrupt").toFile()
        val startedMarker = directory.resolve("started")
        val nextMarker = directory.resolve("next")
        val quotedDirectory = directory.absolutePath.replace("'", "'\\''")
        var firstResult: String? = "not completed"
        var nextResult: String? = "not completed"
        var interruptedAfterWait = false
        var interruptedAfterNext = false
        val worker = Thread {
            val shell = PrivilegedShellExecutor()
            firstResult = shell.run(": > '$quotedDirectory/started'; exec sleep 30")
            interruptedAfterWait = Thread.currentThread().isInterrupted
            nextResult = shell.runRaw(": > '$quotedDirectory/next'; printf next")
            interruptedAfterNext = Thread.currentThread().isInterrupted
        }.apply { isDaemon = true }
        val joinTimeoutMillis = TimeUnit.SECONDS.toMillis(2)

        try {
            worker.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (worker.isAlive && !startedMarker.exists() && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertTrue("The first shell command did not start", startedMarker.exists())
            assertTrue("The first shell command did not wait", worker.isAlive)

            worker.interrupt()
            worker.join(joinTimeoutMillis)

            assertFalse("The interrupted shell command did not return", worker.isAlive)
            assertNull(firstResult)
            assertTrue("The shell wait cleared interruption", interruptedAfterWait)
            assertNull(nextResult)
            assertTrue("The next command cleared interruption", interruptedAfterNext)
            assertFalse("The next shell command ran after interruption", nextMarker.exists())
        } finally {
            worker.interrupt()
            worker.join(joinTimeoutMillis)
            directory.deleteRecursively()
        }
    }
}
