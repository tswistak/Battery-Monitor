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
package codes.swistak.batterymonitor.devicebackup

import codes.swistak.batterymonitor.monitoring.Predictor
import codes.swistak.batterymonitor.monitoring.PredictorStoredState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.nio.file.Files

class PredictorBackupReaderTest {
    @Test
    fun `service snapshot takes priority over stale persisted data and is copied for export`() {
        val values = mutableMapOf(Predictor.KEY_AVERAGE[0] to 42f)
        val result = PredictorBackupReader.state(PredictorStoredState(values, 2)) {
            error("A live snapshot must not read the UI preferences cache or disk")
        }
        values[Predictor.KEY_AVERAGE[0]] = 99f

        assertEquals(mapOf(Predictor.KEY_AVERAGE[0] to 42f), result.averages)
        assertEquals(2, result.version)
    }

    @Test
    fun `saved predictor XML preserves the four float readings and integer version only`() {
        val xml = """<map>
            <float name="key_ave_discharge" value="-1.0" />
            <float name="key_ave_recharge_ac" value="1200.5" />
            <float name="key_ave_recharge_wl" value="2400.0" />
            <float name="key_ave_recharge_usb" value="3600.0" />
            <int name="key_predictor_state_version" value="2" />
            <string name="unrelated">ignored</string>
        </map>"""
        val state = PredictorBackupReader.parse(StringReader(xml))

        assertEquals(
            Predictor.KEY_AVERAGE.zip(listOf(-1f, 1200.5f, 2400f, 3600f)).toMap(), state.averages
        )
        assertEquals(2, state.version)
    }

    @Test
    fun `disk reads prefer the committed backup then observe subsequent complete writes`() {
        val directory = Files.createTempDirectory("predictor-backup-test").toFile()
        try {
            val file = File(directory, "shared_prefs/${Predictor.STORE_NAME}.xml")
            file.parentFile!!.mkdirs()
            val backup = File(file.path + ".bak")
            file.writeText("<map><float")
            backup.writeText("<map><float name=\"key_ave_discharge\" value=\"12.0\" /></map>")
            assertEquals(
                12f, PredictorBackupReader.read(directory).averages[Predictor.KEY_AVERAGE[0]]
            )

            file.writeText("<map><float name=\"key_ave_discharge\" value=\"75.0\" /></map>")
            backup.delete()
            assertEquals(
                75f, PredictorBackupReader.read(directory).averages[Predictor.KEY_AVERAGE[0]]
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `missing predictor storage is empty but invalid stored readings fail visibly`() {
        val directory = Files.createTempDirectory("predictor-backup-test").toFile()
        try {
            assertEquals(
                PredictorStoredState(emptyMap(), null), PredictorBackupReader.read(directory)
            )
            val file = File(directory, "shared_prefs/${Predictor.STORE_NAME}.xml")
            file.parentFile!!.mkdirs()
            file.writeText("<map><float name=\"key_ave_discharge\" value=\"NaN\" /></map>")
            assertThrows(IOException::class.java) { PredictorBackupReader.read(directory) }
        } finally {
            directory.deleteRecursively()
        }
    }
}
