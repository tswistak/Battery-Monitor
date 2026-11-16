/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.common

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NotificationSettingsNavigatorTest {
    @Test
    fun `app-wide navigation targets the app and per-type navigation targets its own channel`() {
        val context = RecordingContext()
        assertTrue(NotificationSettingsNavigator.openNotifications(context))
        assertTrue(NotificationSettingsNavigator.openNotificationChannel(context, "charge_drops"))

        val (app, channel) = context.launched
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, app.action)
        assertNull(app.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, channel.action)
        assertEquals("charge_drops", channel.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals(
            listOf(context.packageName, context.packageName),
            context.launched.map { it.getStringExtra(Settings.EXTRA_APP_PACKAGE) })
    }

    @Test
    fun `unavailable notification settings fall back to the app details page`() {
        val context = RecordingContext(
            Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, Settings.ACTION_APP_NOTIFICATION_SETTINGS
        )
        assertTrue(NotificationSettingsNavigator.openNotificationChannel(context, "charge_drops"))
        assertEquals(
            listOf(
                Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS,
                Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            ), context.launched.map { it.action })
        assertEquals("package:${context.packageName}", context.launched.last().dataString)

        context.launched.clear()
        assertTrue(NotificationSettingsNavigator.openNotifications(context))
        assertEquals(
            listOf(
                Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            ), context.launched.map { it.action })
    }

    private class RecordingContext(private vararg val unavailableActions: String) :
        ContextWrapper(RuntimeEnvironment.getApplication()) {
        val launched = mutableListOf<Intent>()

        override fun startActivity(intent: Intent) {
            launched += intent
            if (intent.action in unavailableActions) throw ActivityNotFoundException()
        }
    }
}
