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

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class RootShellSessionInstrumentedTest {
    private fun requireExplicitRootTest(denied: Boolean = false) {
        val arguments = InstrumentationRegistry.getArguments()
        val deniedMode = arguments.getString("rootDeniedTests") == "true"
        assumeTrue(
            "Run only with an explicit rooted-device test flag and matching Magisk policy",
            if (denied) deniedMode
            else !deniedMode && arguments.getString("rootSessionTests") == "true"
        )
    }

    @Test
    fun rawCommandsReuseRootAndPreserveBytesStatusAndIsolation() {
        requireExplicitRootTest()
        val owner = RootSessionOwner(factory = { RootShellSession() })
        owner.setEnabled(true)
        fun root() = RootExecutor(allowed = { true }, owner = owner)
        val opens = RootShellSession.openCount.get()
        try {
            assertEquals(
                "The test package needs an existing Magisk root grant",
                "0",
                root().runRaw("id -u")?.trim()
            )
            assertEquals(opens + 1, RootShellSession.openCount.get())
            for (text in listOf("", "  Zażółć 😀\r\n\t", "last line", "last line\n")) {
                assertEquals(text, root().runRaw("printf '%s' ${quote(text)}"))
            }
            assertEquals("outerr", root().runRaw("printf out; printf err >&2"))
            assertNull(root().runRaw("export BM_TEST_STATE=changed; cd /; exit 17"))
            assertEquals("unset", root().runRaw($$"printf '%s' \"${BM_TEST_STATE-unset}\""))
            assertEquals(150000, root().runRaw("head -c 150000 /dev/zero | tr '\\000' x")?.length)
            assertEquals(
                256 * 1024, root().runRaw("head -c 262144 /dev/zero | tr '\\000' x")?.length
            )
            assertNull(root().runRaw("head -c 262145 /dev/zero"))
            assertEquals("still alive", root().runRaw("printf 'still alive'"))
            assertEquals(opens + 1, RootShellSession.openCount.get())
        } finally {
            owner.setEnabled(false)
        }
    }

    @Test
    fun timeoutDisableAndInterruptionStopActualChildrenAndAllowRecovery() {
        requireExplicitRootTest()
        for (mode in listOf("timeout", "disable", "interrupt")) {
            val directory = File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "root-session-$mode-${System.nanoTime()}"
            ).apply { mkdirs() }
            val started = File(directory, "started")
            val late = File(directory, "late")
            val owner = RootSessionOwner(
                factory = { RootShellSession() },
                timeoutMillis = if (mode == "timeout") 1_000 else 10_000
            )
            owner.setEnabled(true)
            var result: String? = "pending"
            var interrupted = false
            val worker = Thread {
                result = owner.run(
                    "printf started > ${quote(started.absolutePath)}; sleep 2; printf late > ${
                        quote(
                            late.absolutePath
                        )
                    }", owner.generation
                ) { true }
                interrupted = Thread.currentThread().isInterrupted
            }
            try {
                worker.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!started.exists() && worker.isAlive && System.nanoTime() < deadline) Thread.sleep(
                    10
                )
                assertTrue("$mode command did not start", started.exists())
                when (mode) {
                    "disable" -> owner.setEnabled(false)
                    "interrupt" -> worker.interrupt()
                }
                worker.join(2_000)
                assertFalse("$mode caller remained blocked", worker.isAlive)
                assertNull("$mode published stale output", result)
                if (mode == "interrupt") assertTrue(interrupted)
                Thread.sleep(2_100)
                assertFalse("$mode left the actual root command running", late.exists())
                owner.setEnabled(false)
                owner.setEnabled(true)
                assertEquals("recovered", owner.run("printf recovered", owner.generation) { true })
            } finally {
                owner.setEnabled(false)
                worker.interrupt()
                worker.join(2_000)
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun deniedMagiskPolicyStopsAutomaticRetriesAndPermitsOneExplicitRetry() {
        requireExplicitRootTest(denied = true)
        val owner = RootSessionOwner(factory = { RootShellSession() })
        val opens = RootShellSession.openCount.get()
        try {
            owner.setEnabled(true)
            assertNull(
                "Magisk must deny root to the test package",
                owner.run("id", owner.generation) { true })
            repeat(10) {
                assertNull(owner.run("id", owner.generation) { true })
            }
            assertEquals(
                "Automatic denial must latch after one acquisition",
                opens + 1,
                RootShellSession.openCount.get()
            )

            assertFalse(owner.requestAccess(allowed = { true }))
            assertEquals(
                "Explicit retry must attempt root once", opens + 2, RootShellSession.openCount.get()
            )
            repeat(10) {
                assertNull(owner.run("id", owner.generation) { true })
            }
            assertEquals(
                "Explicit denial must stop subsequent automatic retries",
                opens + 2,
                RootShellSession.openCount.get()
            )
        } finally {
            owner.setEnabled(false)
        }
    }

    private fun quote(text: String) = "'${text.replace("'", "'\\''")}'"
}
