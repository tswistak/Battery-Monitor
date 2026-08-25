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
package codes.swistak.batterymonitor.monitoring

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class PredictorStateMigrationTest {
    @Test
    fun `migration removes only cached discharge average and records version`() {
        val preferences = FakeSharedPreferences(
            Predictor.KEY_AVERAGE.zip(listOf(10f, 20f, 30f, 40f)).toMap()
        )

        assertTrue(Predictor.migratePredictorState(preferences.instance))

        assertFalse(preferences.values.containsKey(Predictor.KEY_AVERAGE[PredictorCore.DISCHARGE]))
        assertEquals(20f, preferences.values[Predictor.KEY_AVERAGE[PredictorCore.RECHARGE_AC]])
        assertEquals(30f, preferences.values[Predictor.KEY_AVERAGE[PredictorCore.RECHARGE_WL]])
        assertEquals(40f, preferences.values[Predictor.KEY_AVERAGE[PredictorCore.RECHARGE_USB]])
        assertEquals(Predictor.STATE_VERSION, preferences.values[Predictor.KEY_STATE_VERSION])
        assertEquals(1, preferences.commitCount)
    }

    @Test
    fun `migration runs once and preserves a newly learned discharge average`() {
        val preferences = FakeSharedPreferences(
            mapOf(Predictor.KEY_AVERAGE[PredictorCore.DISCHARGE] to 10f)
        )

        assertTrue(Predictor.migratePredictorState(preferences.instance))
        preferences.values[Predictor.KEY_AVERAGE[PredictorCore.DISCHARGE]] = 55f

        assertFalse(Predictor.migratePredictorState(preferences.instance))
        assertEquals(55f, preferences.values[Predictor.KEY_AVERAGE[PredictorCore.DISCHARGE]])
        assertEquals(1, preferences.commitCount)
    }

    private class FakeSharedPreferences(
        initialValues: Map<String, Any> = emptyMap()
    ) : InvocationHandler {
        val values = initialValues.toMutableMap()
        var commitCount = 0

        val instance: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java), this
        ) as SharedPreferences

        override fun invoke(proxy: Any, method: Method, arguments: Array<out Any?>?): Any? {
            val args = arguments.orEmpty()
            return when (method.name) {
                "getInt" -> values[args[0] as String] as? Int ?: args[1]
                "edit" -> createEditor()
                "toString" -> "FakeSharedPreferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args[0]
                else -> error("Unsupported SharedPreferences method: ${method.name}")
            }
        }

        private fun createEditor(): SharedPreferences.Editor {
            val updates = mutableMapOf<String, Any?>()
            val handler = InvocationHandler { proxy, method, arguments ->
                val args = arguments.orEmpty()
                when (method.name) {
                    "putInt" -> {
                        updates[args[0] as String] = args[1]
                        proxy
                    }

                    "remove" -> {
                        updates[args[0] as String] = Removed
                        proxy
                    }

                    "commit" -> {
                        commitCount++
                        applyUpdates(updates)
                        true
                    }

                    "toString" -> "FakeSharedPreferences.Editor"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args[0]
                    else -> error("Unsupported SharedPreferences.Editor method: ${method.name}")
                }
            }
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
                handler
            ) as SharedPreferences.Editor
        }

        private fun applyUpdates(updates: Map<String, Any?>) {
            updates.forEach { (key, value) ->
                if (value === Removed) values.remove(key) else values[key] = requireNotNull(value)
            }
        }

        private data object Removed
    }
}
