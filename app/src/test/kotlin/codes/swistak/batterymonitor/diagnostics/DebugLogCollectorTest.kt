/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class DebugLogCollectorTest {
    @get:Rule
    val directory = TemporaryFolder()

    @Test
    fun `collector starts only when a non service process is identified`() {
        assertTrue(DebugLogCollector.shouldStartInProcess("codes.swistak.batterymonitor"))
        assertFalse(DebugLogCollector.shouldStartInProcess("codes.swistak.batterymonitor.BIS"))
        assertFalse(DebugLogCollector.shouldStartInProcess(null))
        assertFalse(DebugLogCollector.shouldStartInProcess(""))
    }

    @Test
    fun `rotation bounds retained bytes and preserves complete recent records`() {
        val active = File(directory.root, "debug.log")
        val files = DebugLogFiles(active, 64)
        repeat(100) { index ->
            files.append(record(index))
            assertTrue(directory.root.listFiles()!!.sumOf(File::length) <= 128)
        }
        val logs = report(files).lineSequence().filter { it.startsWith("log ") }.toList()
        assertTrue(logs.size > 1)
        assertEquals((100 - logs.size until 100).map(::record), logs)

        assertTrue(files.clear())
        assertTrue(directory.root.listFiles()!!.isEmpty())
        assertEquals("", report(files))
    }

    @Test
    fun `legacy oversized log has a bounded export and keeps its latest records on restart`() {
        val active = directory.newFile()
        active.writeText(
            (0 until 100).joinToString(
                separator = "\n", postfix = "\n", transform = ::record
            )
        )
        val files = DebugLogFiles(active, 64)

        val exported = report(files).substringAfter("Debug logs\n")
        assertTrue(exported.toByteArray().size <= 128)
        val lines = exported.trimEnd().lines()
        assertEquals((100 - lines.size until 100).map(::record), lines)

        files.append(record(100))
        assertTrue(directory.root.listFiles()!!.sumOf(File::length) <= 128)
        val afterRestart = report(files)
        assertTrue(afterRestart.contains(record(99)))
        assertTrue(afterRestart.endsWith("${record(100)}\n"))
    }

    @Test
    fun `exports during collection contain whole records in order across rotations`() {
        val files = DebugLogFiles(File(directory.root, "debug.log"), 64)
        val firstRecord = CountDownLatch(1)
        val firstExport = CountDownLatch(1)
        val collection = FutureTask {
            files.append(record(0))
            firstRecord.countDown()
            check(firstExport.await(5, TimeUnit.SECONDS))
            (1 until 100).forEach { files.append(record(it)) }
        }
        Thread(collection).apply { isDaemon = true }.start()
        assertTrue(firstRecord.await(5, TimeUnit.SECONDS))
        assertTrue(report(files).endsWith("${record(0)}\n"))
        firstExport.countDown()
        repeat(100) {
            val logs = report(files).lineSequence().drop(2).filter(String::isNotEmpty).toList()
            val indices = logs.map { it.removePrefix("log ").substringBefore(' ').toInt() }
            assertEquals((indices.first()..indices.last()).toList(), indices)
            assertEquals(indices.map(::record), logs)
        }
        collection.get(5, TimeUnit.SECONDS)
        assertTrue(report(files).endsWith("${record(99)}\n"))
    }

    private fun record(index: Int) = "log $index żółć"

    private fun report(files: DebugLogFiles) = buildString { files.appendTo(this) }
}
