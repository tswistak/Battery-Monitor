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

import codes.swistak.batterymonitor.settings.SettingsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingsBackupSchemaTest {
    @Test
    fun `version three includes remaining charge and explicit vital signs settings`() {
        assertEquals(3, SettingsBackup.SCHEMA_VERSION)
        assertEquals(
            Boolean::class.java,
            Version3SettingsImporter.schema[SettingsContract.KEY_SHOW_REMAINING_CHARGE]
        )
        assertFalse(
            Version3SettingsImporter.schema.containsKey(SettingsContract.KEY_VITAL_SIGNS_CONTENT)
        )
        assertEquals(
            setOf(Boolean::class.java),
            Version3SettingsImporter.vitalSignsContentByBackupKey.keys.mapTo(linkedSetOf()) {
                Version3SettingsImporter.schema[it]
            })
        assertEquals(6, Version3SettingsImporter.vitalSignsContentByBackupKey.size)
        assertEquals(
            setOf(Int::class.java),
            Version3SettingsImporter.vitalSignsOrderByBackupKey.keys.mapTo(linkedSetOf()) {
                Version3SettingsImporter.schema[it]
            })
        assertEquals(6, Version3SettingsImporter.vitalSignsOrderByBackupKey.size)
    }

    @Test
    fun `version three restores vital signs from explicit boolean fields`() {
        val settings = Version3SettingsImporter.vitalSignsContentByBackupKey.keys.associateWith {
            it == Version3SettingsImporter.KEY_VITAL_SIGN_CURRENT || it == Version3SettingsImporter.KEY_VITAL_SIGN_CHARGE
        }

        assertEquals(
            setOf(
                SettingsContract.VITAL_SIGN_CURRENT, SettingsContract.VITAL_SIGN_CHARGE
            ), Version3SettingsImporter.vitalSignsContentFromBackup(settings)
        )
    }

    @Test
    fun `missing vital signs fields use application defaults`() {
        assertEquals(
            SettingsContract.DEFAULT_VITAL_SIGNS_CONTENT,
            Version3SettingsImporter.vitalSignsContentFromBackup(emptyMap())
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
            Version3SettingsImporter.vitalSignsOrderByBackupKey.mapValues { (_, contentValue) ->
                expected.indexOf(contentValue)
            }

        assertEquals(expected, Version3SettingsImporter.vitalSignsOrderFromBackup(settings))
    }

    @Test
    fun `version three rejects duplicate vital signs positions`() {
        val settings = Version3SettingsImporter.vitalSignsOrderByBackupKey.keys.associateWith { 0 }

        assertThrows(IllegalArgumentException::class.java) {
            Version3SettingsImporter.vitalSignsOrderFromBackup(settings)
        }
    }

    @Test
    fun `all supported backup versions retain their importer`() {
        assertSame(Version1SettingsImporter, settingsImporterForVersion(1))
        assertSame(Version2SettingsImporter, settingsImporterForVersion(2))
        assertSame(Version3SettingsImporter, settingsImporterForVersion(3))
    }
}
