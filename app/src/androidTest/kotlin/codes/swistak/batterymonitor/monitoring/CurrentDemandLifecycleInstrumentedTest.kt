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

    You should have received a copy of the GNU General Public License
    along with this program. If not, see <https://www.gnu.org/licenses/>.
*/
package codes.swistak.batterymonitor.monitoring

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.test.platform.app.InstrumentationRegistry
import codes.swistak.batterymonitor.app.PersistentFragment
import codes.swistak.batterymonitor.monitoring.presentation.CurrentDemand
import codes.swistak.batterymonitor.monitoring.presentation.MonitoringConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentDemandLifecycleInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun rapid_reconnect_restores_desired_demand_and_its_token_without_a_boolean_transition_observer() {
        val messages = mutableListOf<Pair<Int, Bundle>>()
        lateinit var fragment: PersistentFragment
        var token = 0L
        instrumentation.runOnMainSync {
            fragment = fragment()
            val service = service(messages)
            val firstGeneration = fragment.monitoring.onServiceConnected()
            handshake(fragment, service, firstGeneration, "first")
            fragment.setCurrentDemand(CurrentDemand(2_000, false))
            token = field(fragment, "currentToken") as Long
            disconnect(fragment)
            assertFalse(fragment.monitoring.current.value.connected)
            assertEquals(CurrentDemand(2_000, false), field(fragment, "currentDemand"))
            val nextGeneration = fragment.monitoring.onServiceConnected()
            handshake(fragment, service, nextGeneration, "second")
            assertTrue(fragment.monitoring.current.value.connected)
            val sample = CurrentSample(BatteryCurrentReading(-400_000, "fake"), null)
            assertTrue(
                fragment.monitoring.publishCurrent(
                    nextGeneration, "second", 0, token, sample
                )
            )
            assertFalse(
                fragment.monitoring.publishCurrent(
                    firstGeneration, "first", 1, token, sample
                )
            )
        }
        instrumentation.waitForIdleSync()
        val restored =
            messages.last { it.first == BatteryInfoService.RemoteConnection.SERVICE_SET_CURRENT_DEMAND }.second
        assertTrue(restored.getBoolean("current_active"))
        assertEquals(token, restored.getLong("current_token"))
        instrumentation.runOnMainSync {
            fragment.onStop()
            fragment.onDestroy()
        }
    }

    @Test
    fun lifecycle_stop_clears_desired_demand_so_reconnect_cannot_restore_it() {
        val messages = mutableListOf<Pair<Int, Bundle>>()
        instrumentation.runOnMainSync {
            val fragment = fragment()
            val service = service(messages)
            val firstGeneration = fragment.monitoring.onServiceConnected()
            handshake(fragment, service, firstGeneration, "first")
            fragment.setCurrentDemand(CurrentDemand(2_000, true))
            val oldToken = field(fragment, "currentToken") as Long
            fragment.onStop()
            assertNull(field(fragment, "currentDemand"))
            fragment.monitoring.start()
            val nextGeneration = fragment.monitoring.onServiceConnected()
            handshake(fragment, service, nextGeneration, "second")
            fragment.refreshCurrent()
            assertFalse(
                fragment.monitoring.publishCurrent(
                    nextGeneration,
                    "second",
                    0,
                    oldToken,
                    CurrentSample(BatteryCurrentReading(-400_000, "fake"), null)
                )
            )
            fragment.onStop()
            fragment.onDestroy()
        }
        instrumentation.waitForIdleSync()
        assertTrue(messages.none { it.first == BatteryInfoService.RemoteConnection.SERVICE_REFRESH_CURRENT })
        val demandMessages =
            messages.filter { it.first == BatteryInfoService.RemoteConnection.SERVICE_SET_CURRENT_DEMAND }
        assertFalse(demandMessages.last().second.getBoolean("current_active"))
    }

    private fun fragment() = PersistentFragment().apply {
        javaClass.getDeclaredField("serviceConnected").apply { isAccessible = true }.set(this, true)
        monitoring.start()
    }

    private fun service(messages: MutableList<Pair<Int, Bundle>>) =
        Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                messages += message.what to Bundle(message.data)
            }
        })

    private fun handshake(
        fragment: PersistentFragment, service: Messenger, generation: Int, process: String
    ) {
        val handler = field(fragment, "messageHandler") as Handler
        handler.handleMessage(Message.obtain().apply {
            what = BatteryInfoService.RemoteConnection.CLIENT_SERVICE_CONNECTED
            replyTo = service
            arg1 = generation
            data = Bundle().apply { putString(MonitoringConnection.FIELD_PROCESS_ID, process) }
        })
    }

    private fun disconnect(fragment: PersistentFragment) {
        fragment.javaClass.getDeclaredMethod("monitoringDisconnected").apply { isAccessible = true }
            .invoke(fragment)
    }

    private fun field(fragment: PersistentFragment, name: String): Any? =
        fragment.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(fragment)
}
