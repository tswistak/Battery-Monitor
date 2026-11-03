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
package codes.swistak.batterymonitor.logs

import android.app.Application
import android.content.Context
import android.os.Bundle
import codes.swistak.batterymonitor.settings.SettingsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AutoLogExportProviderTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val settings
        get() = context.getSharedPreferences(
            SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
        )

    @Before
    fun registerProvider() {
        Robolectric.setupContentProvider(
            AutoLogExportProvider::class.java, "${context.packageName}.log-export"
        )
        settings.edit().clear().commit()
    }

    @Test
    fun `stale monitor snapshots preserve both current watermarks and respect their removal`() {
        val manual = SettingsContract.KEY_LAST_LOG_EXPORT_TIME
        val automatic = SettingsContract.KEY_LAST_AUTO_LOG_EXPORT_TIME
        settings.edit().putLong(manual, 100).putLong(automatic, 200).commit()
        val remote = context.getSharedPreferences("stale-monitor", Context.MODE_PRIVATE)
        val stale = Bundle().apply {
            putLong(manual, 10)
            putLong(automatic, 20)
            putBoolean(SettingsContract.KEY_DISMISS_LOW_BATTERY_ON_RECOVERY, true)
        }
        assertTrue(AutoLogExportProvider.applyMonitoringSnapshot(context, remote, stale))
        assertEquals(100, remote.getLong(manual, 0))
        assertEquals(200, remote.getLong(automatic, 0))
        assertTrue(remote.getBoolean(SettingsContract.KEY_DISMISS_LOW_BATTERY_ON_RECOVERY, false))
        settings.edit().remove(manual).remove(automatic).commit()
        assertTrue(AutoLogExportProvider.applyMonitoringSnapshot(context, remote, stale))
        assertFalse(remote.contains(manual))
        assertFalse(remote.contains(automatic))
    }
}
