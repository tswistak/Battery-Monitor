/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.widgets

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class WidgetSnapshotInstrumentedTest {
    @Test
    fun no_installed_widgets_keeps_latest_measurement_for_preview_without_writing_a_snapshot() =
        runBlocking {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(target.cacheDir, "widget-snapshot-${System.nanoTime()}").apply {
                mkdirs()
            }
            val context = object : ContextWrapper(target) {
                override fun getApplicationContext(): Context = this
                override fun getPackageName(): String = "${target.packageName}.snapshot_test"
                override fun getFilesDir(): File = directory
                override fun getNoBackupFilesDir(): File = directory
            }
            val initial = WidgetSnapshot(
                72, 2, 316, 4080, -420_000, 3_020_000, 580, 80, false, 1_000_000
            )
            val latest = initial.copy(currentMicroAmps = -430_000, observedAtMillis = 1_001_000)
            try {
                WidgetUpdateDispatcher.publish(context, initial, force = true)
                WidgetUpdateDispatcher.publish(context, latest)
                assertEquals(latest, WidgetUpdateDispatcher.previewData(context, null).snapshot)
                assertNull(WidgetSnapshotStore.read(context))
            } finally {
                directory.deleteRecursively()
            }
        }
}
