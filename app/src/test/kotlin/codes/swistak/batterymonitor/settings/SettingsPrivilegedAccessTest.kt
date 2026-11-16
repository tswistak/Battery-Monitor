/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.settings

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentHostCallback
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.privileged.PrivilegedAccess
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import rikka.shizuku.Shizuku.OnBinderReceivedListener

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SettingsPrivilegedAccessTest {
    private lateinit var fragment: SettingsFragment
    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences
        get() = context.getSharedPreferences(
            "privileged-retry-test", Context.MODE_PRIVATE
        )

    @Before
    fun setUp() {
        preferences.edit().clear().commit()
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        fragment = SettingsFragment()
        val host = object : FragmentHostCallback<FragmentActivity>(
            activity, Handler(Looper.getMainLooper()), 0
        ) {
            override fun onGetHost() = activity
        }
        Fragment::class.java.getDeclaredField("mHost").apply { isAccessible = true }
            .set(fragment, host)
        Fragment::class.java.getDeclaredField("mAdded").apply { isAccessible = true }
            .setBoolean(fragment, true)
        field("mSharedPreferences").set(fragment, preferences)
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        call("cancelPrivilegedAccessRequest")
        PrivilegedAccess.setEnabled(false)
    }

    @Test
    fun `each deliberate retry while waiting for Shizuku reports failure without another request`() {
        beginWaitingForShizuku()
        val listener = pendingListener()
        assertNotNull(listener)
        assertEquals(1, ShadowToast.shownToastCount())
        val generation = field("privilegedAccessRequestGeneration").getLong(fragment)

        repeat(3) { retry ->
            call("requestPrivilegedAccessEnable")
            assertEquals(ShadowToast.shownToastCount(), retry + 2)
            assertEquals(
                ShadowToast.getTextOfLatestToast(), context.getString(R.string.shizuku_not_running)
            )
            assertSame(listener, pendingListener())
            assertEquals(generation, field("privilegedAccessRequestGeneration").getLong(fragment))
            assertFalse(preferences.getBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false))
        }
    }

    @Test
    fun `an active root or permission request is not started again by retries`() {
        field("privilegedAccessRequestInProgress").setBoolean(fragment, true)
        val generation = field("privilegedAccessRequestGeneration").getLong(fragment)
        repeat(3) { call("requestPrivilegedAccessEnable") }
        assertEquals(0, ShadowToast.shownToastCount())
        assertNull(pendingListener())
        assertEquals(generation, field("privilegedAccessRequestGeneration").getLong(fragment))
        assertFalse(preferences.getBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false))
    }

    @Test
    fun `leaving cancels waiting and stale binder delivery cannot affect a newer attempt`() {
        beginWaitingForShizuku()
        val stale = requireNotNull(pendingListener())
        fragment.onHiddenChanged(true)
        assertNull(pendingListener())
        assertFalse(field("privilegedAccessRequestInProgress").getBoolean(fragment))

        beginWaitingForShizuku()
        val current = pendingListener()
        val feedback = ShadowToast.shownToastCount()
        stale.onBinderReceived()
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(current, pendingListener())
        assertEquals(feedback, ShadowToast.shownToastCount())
        assertFalse(preferences.getBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false))

        fragment.onPause()
        requireNotNull(current).onBinderReceived()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(pendingListener())
        assertFalse(field("privilegedAccessRequestInProgress").getBoolean(fragment))
        assertFalse(preferences.getBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false))
    }

    @Test
    fun `a verified successful request persists access and clears its pending listener`() {
        beginWaitingForShizuku()
        call("completePrivilegedAccessEnable")
        assertTrue(preferences.getBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false))
        assertFalse(field("privilegedAccessRequestInProgress").getBoolean(fragment))
        assertNull(pendingListener())
    }

    @Test
    fun `a queued permission grant cannot enable a new request after cancellation`() {
        shadowOf(Looper.getMainLooper()).pause()
        field("privilegedAccessRequestInProgress").setBoolean(fragment, true)
        val permissionListener =
            field("privilegedShizukuPermissionListener").get(fragment) as rikka.shizuku.Shizuku.OnRequestPermissionResultListener
        permissionListener.onRequestPermissionResult(
            7001, android.content.pm.PackageManager.PERMISSION_GRANTED
        )
        assertFalse(preferences.getBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false))

        call("cancelPrivilegedAccessRequest")
        beginWaitingForShizuku()
        val current = pendingListener()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(field("privilegedAccessRequestInProgress").getBoolean(fragment))
        assertSame(current, pendingListener())
        assertFalse(preferences.getBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false))
    }

    private fun beginWaitingForShizuku() {
        // Enter the existing fallback after root denial without running a real root command.
        field("privilegedAccessRequestInProgress").setBoolean(fragment, true)
        call("requestPrivilegedShizukuPermission")
    }

    private fun pendingListener() =
        field("pendingPrivilegedShizukuBinderListener").get(fragment) as OnBinderReceivedListener?

    private fun field(name: String) =
        SettingsFragment::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun call(name: String) {
        SettingsFragment::class.java.getDeclaredMethod(name).apply { isAccessible = true }
            .invoke(fragment)
    }
}
