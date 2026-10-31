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
package codes.swistak.batterymonitor.ui.dialogs

import android.app.Application
import android.content.Intent
import codes.swistak.batterymonitor.alarms.AlarmEditActivity
import codes.swistak.batterymonitor.app.AlarmEditorDestination
import codes.swistak.batterymonitor.app.AlarmEditorNavigation
import codes.swistak.batterymonitor.app.BatteryInfoActivity
import codes.swistak.batterymonitor.ui.navigation.SectionOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AlarmNavigationStateTest {
    @Test
    fun pending_alarm_navigation_restores_each_target_without_an_activity_callback() {
        for (destination in AlarmEditorDestination.entries.filter { it != AlarmEditorDestination.INTENT }) {
            val original = AlarmEditorNavigation(
                destination,
                section = SectionOwner.SETTINGS.takeIf { destination == AlarmEditorDestination.SECTION },
                alarmId = 42.takeIf { destination == AlarmEditorDestination.EDIT_ALARM })
            assertEquals(original, AlarmEditorNavigation.restore(original.save()))
        }
        val intent = Intent().putExtra(BatteryInfoActivity.EXTRA_SECTION, "history")
            .putExtra(BatteryInfoActivity.EXTRA_DETAIL, "alarm-edit")
            .putExtra(BatteryInfoActivity.EXTRA_HISTORY_HOURS, 24)
            .putExtra(AlarmEditActivity.EXTRA_ALARM_ID, 42)
            .putExtra("unrelated_payload", ByteArray(1024))
        val restored = requireNotNull(
            AlarmEditorNavigation.restore(
                AlarmEditorNavigation.route(intent).save()
            )
        )
        assertEquals(AlarmEditorDestination.INTENT, restored.destination)
        val extras = requireNotNull(restored.intentExtras)
        assertEquals("history", extras.getString(BatteryInfoActivity.EXTRA_SECTION))
        assertEquals("alarm-edit", extras.getString(BatteryInfoActivity.EXTRA_DETAIL))
        assertEquals(24, extras.getInt(BatteryInfoActivity.EXTRA_HISTORY_HOURS))
        assertEquals(42, extras.getInt(AlarmEditActivity.EXTRA_ALARM_ID))
        assertFalse(extras.containsKey("unrelated_payload"))
    }

    @Test
    fun malformed_pending_alarm_navigation_does_not_navigate_to_a_different_section() {
        assertNull(AlarmEditorNavigation.restore(null))
        assertNull(
            AlarmEditorNavigation.restore(
                android.os.Bundle().apply { putString("destination", "unknown") })
        )
        assertNull(AlarmEditorNavigation.restore(AlarmEditorNavigation(AlarmEditorDestination.SECTION).save()))
        assertNull(AlarmEditorNavigation.restore(AlarmEditorNavigation(AlarmEditorDestination.INTENT).save()))
    }
}
