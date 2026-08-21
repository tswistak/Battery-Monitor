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
package codes.swistak.batterymonitor.settings.backup

import android.content.SharedPreferences
import codes.swistak.batterymonitor.settings.SettingsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingsBackupSchemaTest {
    @Test
    fun `version three includes explicit chip content settings`() {
        assertEquals(
            String::class.java,
            Version3SettingsImporter.schema[SettingsContract.KEY_CHIP_SWITCHING_INTERVAL]
        )
        assertFalse(Version3SettingsImporter.schema.containsKey(SettingsContract.KEY_CHIP_CONTENT))
        assertEquals(
            setOf(Boolean::class.java),
            SettingsBackupCodec.chipContentByBackupKey.keys.mapTo(linkedSetOf()) {
                Version3SettingsImporter.schema[it]
            })
        assertEquals(5, SettingsBackupCodec.chipContentByBackupKey.size)
        assertEquals(
            setOf(Int::class.java),
            SettingsBackupCodec.chipContentOrderByBackupKey.keys.mapTo(linkedSetOf()) {
                Version3SettingsImporter.schema[it]
            })
        assertEquals(5, SettingsBackupCodec.chipContentOrderByBackupKey.size)
    }

    @Test
    fun `version three restores explicit chip content and order`() {
        val expectedOrder = listOf(
            SettingsContract.CHIP_CONTENT_CHARGE,
            SettingsContract.CHIP_CONTENT_CURRENT,
            SettingsContract.CHIP_CONTENT_PERCENTAGE,
            SettingsContract.CHIP_CONTENT_VOLTAGE,
            SettingsContract.CHIP_CONTENT_TEMPERATURE
        )
        val settings = buildMap<String, Any> {
            for (key in SettingsBackupCodec.chipContentByBackupKey.keys) {
                put(
                    key,
                    key == SettingsBackupCodec.KEY_CHIP_CONTENT_CURRENT || key == SettingsBackupCodec.KEY_CHIP_CONTENT_CHARGE
                )
            }
            for ((key, contentValue) in SettingsBackupCodec.chipContentOrderByBackupKey) {
                put(key, expectedOrder.indexOf(contentValue))
            }
        }

        assertEquals(
            setOf(
                SettingsContract.CHIP_CONTENT_CURRENT, SettingsContract.CHIP_CONTENT_CHARGE
            ), SettingsBackupCodec.chipContentFromBackup(settings)
        )
        assertEquals(expectedOrder, SettingsBackupCodec.chipContentOrderFromBackup(settings))
    }

    @Test
    fun `missing chip content fields use application defaults`() {
        assertEquals(
            SettingsContract.DEFAULT_CHIP_CONTENT,
            SettingsBackupCodec.chipContentFromBackup(emptyMap())
        )
    }

    @Test
    fun `version three rejects duplicate chip content positions`() {
        val settings = SettingsBackupCodec.chipContentOrderByBackupKey.keys.associateWith { 0 }

        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackupCodec.chipContentOrderFromBackup(settings)
        }
    }

    @Test
    fun `version three includes remaining charge and explicit vital signs settings`() {
        assertEquals(
            Boolean::class.java,
            Version3SettingsImporter.schema[SettingsContract.KEY_SHOW_REMAINING_CHARGE]
        )
        assertFalse(
            Version3SettingsImporter.schema.containsKey(SettingsContract.KEY_VITAL_SIGNS_CONTENT)
        )
        assertEquals(
            setOf(Boolean::class.java),
            SettingsBackupCodec.vitalSignsContentByBackupKey.keys.mapTo(linkedSetOf()) {
                Version3SettingsImporter.schema[it]
            })
        assertEquals(6, SettingsBackupCodec.vitalSignsContentByBackupKey.size)
        assertEquals(
            setOf(Int::class.java),
            SettingsBackupCodec.vitalSignsOrderByBackupKey.keys.mapTo(linkedSetOf()) {
                Version3SettingsImporter.schema[it]
            })
        assertEquals(6, SettingsBackupCodec.vitalSignsOrderByBackupKey.size)
    }

    @Test
    fun `version three restores vital signs from explicit boolean fields`() {
        val settings = SettingsBackupCodec.vitalSignsContentByBackupKey.keys.associateWith {
            it == SettingsBackupCodec.KEY_VITAL_SIGN_CURRENT || it == SettingsBackupCodec.KEY_VITAL_SIGN_CHARGE
        }

        assertEquals(
            setOf(
                SettingsContract.VITAL_SIGN_CURRENT, SettingsContract.VITAL_SIGN_CHARGE
            ), SettingsBackupCodec.vitalSignsContentFromBackup(settings)
        )
    }

    @Test
    fun `missing vital signs fields use application defaults`() {
        assertEquals(
            SettingsContract.DEFAULT_VITAL_SIGNS_CONTENT,
            SettingsBackupCodec.vitalSignsContentFromBackup(emptyMap())
        )
    }

    @Test
    fun `version three restores explicit vital signs order`() {
        val expected = listOf(
            SettingsContract.VITAL_SIGN_CHARGE,
            SettingsContract.VITAL_SIGN_CURRENT,
            SettingsContract.VITAL_SIGN_HEALTH,
            SettingsContract.VITAL_SIGN_STATUS_DURATION,
            SettingsContract.VITAL_SIGN_VOLTAGE,
            SettingsContract.VITAL_SIGN_TEMPERATURE
        )
        val settings =
            SettingsBackupCodec.vitalSignsOrderByBackupKey.mapValues { (_, contentValue) ->
                expected.indexOf(contentValue)
            }

        assertEquals(expected, SettingsBackupCodec.vitalSignsOrderFromBackup(settings))
    }

    @Test
    fun `version three rejects duplicate vital signs positions`() {
        val settings = SettingsBackupCodec.vitalSignsOrderByBackupKey.keys.associateWith { 0 }

        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackupCodec.vitalSignsOrderFromBackup(settings)
        }
    }

    @Test
    fun `all supported backup versions retain their importer`() {
        assertSame(Version1SettingsImporter, settingsImporterForVersion(1))
        assertSame(Version2SettingsImporter, settingsImporterForVersion(2))
        assertSame(Version3SettingsImporter, settingsImporterForVersion(3))
        assertSame(Version4SettingsImporter, settingsImporterForVersion(4))
    }

    @Test
    fun `version four includes long duration format`() {
        assertEquals(4, SettingsBackup.SCHEMA_VERSION)
        assertEquals(
            String::class.java,
            Version4SettingsImporter.schema[SettingsContract.KEY_LONG_DURATION_FORMAT]
        )
        assertEquals(
            String::class.java,
            Version4SettingsImporter.schema[SettingsContract.KEY_TEMPERATURE_UNIT]
        )
        assertFalse(
            Version4SettingsImporter.schema.containsKey(SettingsContract.LEGACY_KEY_CONVERT_F)
        )
    }

    @Test
    fun `version four includes time estimate targets`() {
        assertEquals(4, SettingsBackup.SCHEMA_VERSION)
        assertEquals(
            String::class.java,
            Version4SettingsImporter.schema[SettingsContract.KEY_CHARGING_TARGET_MODE]
        )
        assertEquals(
            Int::class.java,
            Version4SettingsImporter.schema[SettingsContract.KEY_CUSTOM_CHARGING_TARGET]
        )
        assertEquals(
            Int::class.java,
            Version4SettingsImporter.schema[SettingsContract.KEY_DISCHARGING_TARGET]
        )
        assertEquals(
            Boolean::class.java,
            Version4SettingsImporter.schema[SettingsContract.KEY_USE_PRIVILEGED_ACCESS]
        )
        assertFalse(
            Version4SettingsImporter.schema.containsKey(
                SettingsContract.LEGACY_KEY_USE_PRIVILEGED_BATTERY_CURRENT
            )
        )
    }

    @Test
    fun `version four restores its settings through the shared codec`() {
        val restored = mutableMapOf<String, Any?>()
        val editor = java.lang.reflect.Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, arguments ->
            val args = arguments.orEmpty()
            when (method.name) {
                "putBoolean", "putInt", "putString", "putStringSet" -> {
                    restored[args[0] as String] = args[1]
                    proxy
                }

                "remove" -> proxy
                else -> error("Unexpected editor method: ${method.name}")
            }
        } as SharedPreferences.Editor
        val settings = mapOf<String, Any>(
            SettingsContract.KEY_CHARGING_TARGET_MODE to "custom",
            SettingsContract.KEY_CUSTOM_CHARGING_TARGET to 85,
            SettingsContract.KEY_DISCHARGING_TARGET to 15,
            SettingsContract.KEY_USE_PRIVILEGED_ACCESS to true
        )

        Version4SettingsImporter.restore(editor, settings)

        assertEquals("custom", restored[SettingsContract.KEY_CHARGING_TARGET_MODE])
        assertEquals(85, restored[SettingsContract.KEY_CUSTOM_CHARGING_TARGET])
        assertEquals(15, restored[SettingsContract.KEY_DISCHARGING_TARGET])
        assertEquals(true, restored[SettingsContract.KEY_USE_PRIVILEGED_ACCESS])
    }
}
