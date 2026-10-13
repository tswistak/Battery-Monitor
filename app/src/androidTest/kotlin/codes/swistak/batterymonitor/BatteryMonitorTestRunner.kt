/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor

import android.app.Instrumentation
import android.os.Bundle
import codes.swistak.batterymonitor.alarms.AlarmDatabaseInstrumentedTest
import codes.swistak.batterymonitor.logs.BatteryHistoryInstrumentedTest
import codes.swistak.batterymonitor.logs.DetailedHistoryInstrumentedTest
import codes.swistak.batterymonitor.ui.history.FullscreenChartStateInstrumentedTest
import org.junit.runner.Description
import org.junit.runner.JUnitCore
import org.junit.runner.Request
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener

class BatteryMonitorTestRunner : Instrumentation() {
    companion object {
        lateinit var instrumentation: Instrumentation
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        instrumentation = this
        start()
    }

    override fun onStart() {
        val junit = JUnitCore()
        val runner = Request.classes(
            AlarmDatabaseInstrumentedTest::class.java,
            BatteryHistoryInstrumentedTest::class.java,
            DetailedHistoryInstrumentedTest::class.java,
            FullscreenChartStateInstrumentedTest::class.java
        ).runner
        val total = runner.testCount()
        var failed = false
        var index = 0
        junit.addListener(object : RunListener() {
            override fun testStarted(description: Description) {
                failed = false
                index++
                sendStatus(1, status(description))
            }

            override fun testFailure(failure: Failure) {
                failed = true
                sendStatus(
                    -2, status(failure.description).apply { putString("stack", failure.trace) })
            }

            override fun testFinished(description: Description) {
                if (!failed) sendStatus(0, status(description))
            }

            private fun status(description: Description) = Bundle().apply {
                putString("class", description.className)
                putString("test", description.methodName)
                putInt("current", index)
                putString("id", "BatteryMonitorTestRunner")
                putInt("numtests", total)
            }
        })
        val result = junit.run(runner)
        finish(-1, Bundle().apply {
            putString(
                "stream", "Tests run: ${result.runCount}, failures: ${result.failureCount}\n${
                    result.failures.joinToString("\n")
                }"
            )
        })
    }
}
