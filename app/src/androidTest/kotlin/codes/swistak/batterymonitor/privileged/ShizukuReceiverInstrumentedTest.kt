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
package codes.swistak.batterymonitor.privileged

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PermissionInfo
import android.os.Build
import android.os.Handler
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PermissionInfoCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rikka.shizuku.ShizukuProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ShizukuReceiverInstrumentedTest {
    @Test
    fun legacyRegistrationRejectsOtherUidAndKeepsSameAppDelivery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val action = "${context.packageName}.SHIZUKU_RECEIVER_TEST"
        val received = AtomicInteger()
        val delivered = CountDownLatch(1)
        val unprotectedDelivery = CountDownLatch(1)
        val unprotected = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                unprotectedDelivery.countDown()
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                received.incrementAndGet()
                delivered.countDown()
            }
        }
        if (Build.VERSION.SDK_INT < 33) {
            context.registerReceiver(unprotected, IntentFilter(action))
        }
        ShizukuReceiverContext(context).registerReceiver(receiver, IntentFilter(action))
        try {
            val permission = "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
            val info = context.packageManager.getPermissionInfo(permission, 0)
            assertEquals(
                PermissionInfo.PROTECTION_SIGNATURE, PermissionInfoCompat.getProtection(info)
            )
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "am broadcast -a $action -p ${context.packageName} --receiver-foreground"
                )
            ).use { it.readBytes() }
            if (Build.VERSION.SDK_INT < 33) {
                assertTrue(
                    "Control must reproduce delivery through the old unprotected overload",
                    unprotectedDelivery.await(5, TimeUnit.SECONDS)
                )
            }
            instrumentation.waitForIdleSync()
            assertEquals("A different UID must not deliver this broadcast", 0, received.get())

            context.sendBroadcast(Intent(action).setPackage(context.packageName))
            assertTrue(
                "Same-app Binder sharing must remain possible", delivered.await(5, TimeUnit.SECONDS)
            )
            assertEquals(1, received.get())
        } finally {
            context.unregisterReceiver(receiver)
            if (Build.VERSION.SDK_INT < 33) context.unregisterReceiver(unprotected)
        }
    }

    @Test
    fun pinnedProviderUsesProtectedRegistration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var registered: BroadcastReceiver? = null
        var senderPermission: String? = null
        var receiverFlags = 0
        val recording = object : ContextWrapper(context) {
            @SuppressLint("UnspecifiedRegisterReceiverFlag")
            override fun registerReceiver(
                receiver: BroadcastReceiver?,
                filter: IntentFilter,
                broadcastPermission: String?,
                scheduler: Handler?
            ): Intent? {
                registered = receiver
                senderPermission = broadcastPermission
                return super.registerReceiver(receiver, filter, broadcastPermission, scheduler)
            }

            override fun registerReceiver(
                receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int
            ): Intent? {
                registered = receiver
                receiverFlags = flags
                return super.registerReceiver(receiver, filter, flags)
            }
        }
        try {
            ShizukuProvider.enableMultiProcessSupport(false)
            ShizukuProvider.requestBinderForNonProviderProcess(ShizukuReceiverContext(recording))
            assertTrue("Pinned provider must register its sharing receiver", registered != null)
            if (Build.VERSION.SDK_INT < 33) {
                assertEquals(
                    "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                    senderPermission
                )
            } else {
                assertTrue(receiverFlags and ContextCompat.RECEIVER_NOT_EXPORTED != 0)
            }
        } finally {
            registered?.let(context::unregisterReceiver)
        }
    }
}
