/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.devicebackup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class DeviceDataRestoreTest {
    @Test
    fun `predictor failure reports committed logs separately`() {
        val restored = mutableListOf<String>()
        val cause = IOException("Predictor storage failed")

        val failure = assertThrows(GeneralBackupRestoreException::class.java) {
            DeviceDataBackup.restoreData(restoreLogs = {
                restored += "logs"
            }, restorePredictor = { throw cause })
        }

        assertEquals(listOf("logs"), restored)
        assertEquals(setOf(GeneralBackupDataType.LOGS), failure.completedData)
        assertEquals(setOf(GeneralBackupDataType.PREDICTOR_DATA), failure.failedData)
        assertSame(cause, failure.cause)
    }

    @Test
    fun `log metadata failure reports partial logs and does not attempt predictor`() {
        var logRecordsRestored = false
        var predictorAttempted = false

        val failure = assertThrows(GeneralBackupRestoreException::class.java) {
            DeviceDataBackup.restoreData(restoreLogs = {
                logRecordsRestored = true
                error("Could not restore the last log export time")
            }, restorePredictor = { predictorAttempted = true })
        }

        assertEquals(true, logRecordsRestored)
        assertFalse(predictorAttempted)
        assertEquals(emptySet<GeneralBackupDataType>(), failure.completedData)
        assertEquals(setOf(GeneralBackupDataType.LOGS), failure.failedData)
    }

    @Test
    fun `selected stages retain their restore order`() {
        val restored = mutableListOf<String>()

        DeviceDataBackup.restoreData(restoreLogs = {
            restored += "logs"
        }, restorePredictor = { restored += "predictor" })
        assertEquals(listOf("logs", "predictor"), restored)

        restored.clear()
        DeviceDataBackup.restoreData(
            restoreLogs = null, restorePredictor = { restored += "predictor" })
        assertEquals(listOf("predictor"), restored)
    }
}
