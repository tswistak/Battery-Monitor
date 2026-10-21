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
package codes.swistak.batterymonitor.profiling

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import codes.swistak.batterymonitor.app.StartupDialogViewModel
import codes.swistak.batterymonitor.logs.LogDatabase
import codes.swistak.batterymonitor.logs.LogRecord
import codes.swistak.batterymonitor.monitoring.BatteryInfoService
import codes.swistak.batterymonitor.monitoring.MonitoringDesiredState
import codes.swistak.batterymonitor.monitoring.Predictor
import codes.swistak.batterymonitor.settings.SettingsContract

class ProfileFixtureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        require(context.packageName == PROFILE_PACKAGE)
        val pending = goAsync()
        Thread {
            try {
                val result = when (intent.action) {
                    ACTION_SEED -> seed(context, intent)
                    ACTION_APPEND -> append(context)
                    ACTION_HISTORY_WATCH -> ProfileHistoryObserver.watch(context)
                    ACTION_HISTORY_STATE -> ProfileHistoryObserver.snapshot(context, intent)
                    ACTION_HISTORY_IO -> ProfileHistoryIoVerification.run(context, intent)
                    ACTION_HISTORY_RESTORE -> ProfileHistoryIoVerification.restore(context, intent)
                    ACTION_VERIFY_JOURNAL -> ProfileJournalVerification.run(context, intent)
                    ACTION_SHIZUKU_STATUS -> ShizukuProfilingProbe.status(
                        context, intent.getStringExtra("phase") ?: "status"
                    )

                    ACTION_MEASURE_SHIZUKU -> {
                        check(intent.getBooleanExtra("privileged", false)) {
                            "Shizuku acquisition profiling requires explicit privileged=true"
                        }
                        ShizukuProfilingProbe.measure(
                            context,
                            intent.getStringExtra("phase") ?: "measurement",
                            intent.getBooleanExtra("enable", false),
                            intent.getBooleanExtra("readerChecks", false)
                        )
                    }

                    ACTION_MEASURE_PRIVILEGED -> {
                        check(
                            intent.getBooleanExtra(
                                "privileged", false
                            ) && context.getSharedPreferences(
                                SettingsContract.SETTINGS_FILE, 0
                            ).getBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false)
                        ) {
                            "Privileged profiling requires an explicitly enabled privileged fixture"
                        }
                        PrivilegedProfilingProbe.measureRootMonitoring(context)
                    }

                    else -> error("Unsupported profiling fixture action")
                }
                pending.resultCode = Activity.RESULT_OK
                pending.setResultExtras(result)
            } catch (error: Exception) {
                Log.e("ProfileFixture", "Fixture failed", error)
                pending.resultCode = Activity.RESULT_CANCELED
                pending.setResultExtras(Bundle().apply {
                    putString("error", error.javaClass.simpleName + ": " + error.message)
                })
            } finally {
                pending.finish()
            }
        }.apply {
            name = "profile-fixture"
            start()
        }
    }

    private fun seed(context: Context, intent: Intent): Bundle {
        val anchor = intent.getLongExtra("anchorMillis", 0)
        require(anchor in MIN_ANCHOR_MILLIS..System.currentTimeMillis())
        context.stopService(Intent(context, BatteryInfoService::class.java))
        val privileged = intent.getBooleanExtra("privileged", false)
        val monitoring = intent.getBooleanExtra("monitoring", true)
        val settings = context.getSharedPreferences(SettingsContract.SETTINGS_FILE, 0)
        check(
            settings.edit().clear().putBoolean(SettingsContract.KEY_ENABLE_LOGGING, true)
                .putBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, privileged)
                .putString(SettingsContract.KEY_BATTERY_CURRENT_REFRESH_INTERVAL, "2")
                .putString(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER, "1")
                .putBoolean(SettingsContract.KEY_PREFER_AVERAGE_BATTERY_CURRENT, false)
                .putString(SettingsContract.KEY_TEMPERATURE_UNIT, "celsius")
                .putString(SettingsContract.KEY_AUTOSTART, "auto").commit()
        )
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val version = PackageInfoCompat.getLongVersionCode(installed)
        check(
            context.getSharedPreferences(SettingsContract.SP_MAIN_FILE, 0).edit().clear()
                .putBoolean(SettingsContract.KEY_FIRST_RUN, false)
                .putLong(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, version).commit()
        )
        check(
            context.getSharedPreferences(SettingsContract.SP_SERVICE_FILE, 0).edit().clear()
                .commit()
        )
        check(context.getSharedPreferences(Predictor.STORE_NAME, 0).edit().clear().commit())
        check(MonitoringDesiredState.write(context, monitoring))
        context.deleteDatabase("alarms.db")
        // The next process needs durable fixture state; KTX edit discards commit failures.
        @SuppressLint("UseKtx") val fixtureCommitted =
            context.getSharedPreferences("profile_fixture", 0).edit().clear()
                .putLong("anchorMillis", anchor).putInt("appended", 0).commit()
        check(fixtureCommitted)
        val records = (0 until SAMPLE_COUNT).mapNotNull { index ->
            // A bounded missing-data interval and reboot exercise chart segmentation.
            if (index in 10_000..10_029) return@mapNotNull null
            val charging = index % CYCLE_SAMPLES >= CYCLE_SAMPLES / 2
            val phase = index % (CYCLE_SAMPLES / 2)
            val level = if (charging) 20 + phase / 6 else 80 - phase / 6
            LogRecord(
                status = if (index == 10_030) LogDatabase.STATUS_BOOT_COMPLETED else LogDatabase.encodeStatus(
                    if (charging) BatteryManager.BATTERY_STATUS_CHARGING else BatteryManager.BATTERY_STATUS_DISCHARGING,
                    if (charging) BatteryManager.BATTERY_PLUGGED_USB else 0,
                    if (phase == 0) LogDatabase.STATUS_NEW else LogDatabase.STATUS_SAMPLE
                ),
                charge = level,
                time = anchor - (SAMPLE_COUNT - index) * SAMPLE_INTERVAL_MILLIS,
                temperature = 250 + index % 80,
                voltage = 3500 + level * 7,
                currentMicroAmps = if (charging) 600_000L + index % 70 * 1_000 else -250_000L - index % 50 * 1_000,
                remainingChargeMicroampHours = level * 30_000L
            )
        }
        val database = LogDatabase(context)
        try {
            database.replaceAllLogs(records)
            check(database.getAllLogRecords().size == records.size)
        } finally {
            database.close()
        }
        return Bundle().apply {
            putLong("anchorMillis", anchor)
            putInt("rows", records.size)
            putBoolean("privileged", privileged)
            putBoolean("monitoring", monitoring)
            putString("fixture", "synthetic-30d-2m-v1")
        }
    }

    private fun append(context: Context): Bundle {
        val fixture = context.getSharedPreferences("profile_fixture", 0)
        val anchor = fixture.getLong("anchorMillis", 0)
        check(anchor >= MIN_ANCHOR_MILLIS)
        val sequence = fixture.getInt("appended", 0) + 1
        val database = LogDatabase(context)
        try {
            database.addLogs(
                listOf(
                    LogRecord(
                        status = LogDatabase.encodeStatus(
                            BatteryManager.BATTERY_STATUS_CHARGING,
                            BatteryManager.BATTERY_PLUGGED_USB,
                            LogDatabase.STATUS_SAMPLE
                        ),
                        charge = 79,
                        time = anchor + sequence * 1_000L,
                        temperature = 310 + sequence,
                        voltage = 4053,
                        currentMicroAmps = 650_000L + sequence * 1_000L,
                        remainingChargeMicroampHours = 2_370_000L
                    )
                )
            )
        } finally {
            database.close()
        }
        // Verify durable sequencing before reporting success; KTX edit discards commit failures.
        @SuppressLint("UseKtx") val fixtureCommitted =
            fixture.edit().putInt("appended", sequence).commit()
        check(fixtureCommitted)
        return Bundle().apply { putInt("appended", sequence) }
    }

    companion object {
        private const val PROFILE_PACKAGE = "codes.swistak.batterymonitor.profile"
        private const val ACTION_SEED = "$PROFILE_PACKAGE.SEED"
        private const val ACTION_APPEND = "$PROFILE_PACKAGE.APPEND"
        private const val ACTION_HISTORY_WATCH = "$PROFILE_PACKAGE.HISTORY_WATCH"
        private const val ACTION_HISTORY_STATE = "$PROFILE_PACKAGE.HISTORY_STATE"
        private const val ACTION_HISTORY_IO = "$PROFILE_PACKAGE.HISTORY_IO"
        private const val ACTION_HISTORY_RESTORE = "$PROFILE_PACKAGE.HISTORY_RESTORE"
        private const val ACTION_VERIFY_JOURNAL = "$PROFILE_PACKAGE.VERIFY_JOURNAL"
        private const val ACTION_MEASURE_PRIVILEGED = "$PROFILE_PACKAGE.MEASURE_PRIVILEGED"
        private const val ACTION_SHIZUKU_STATUS = "$PROFILE_PACKAGE.SHIZUKU_STATUS"
        private const val ACTION_MEASURE_SHIZUKU = "$PROFILE_PACKAGE.MEASURE_SHIZUKU"
        private const val MIN_ANCHOR_MILLIS = 1_577_836_800_000L
        private const val SAMPLE_COUNT = 21_600
        private const val SAMPLE_INTERVAL_MILLIS = 120_000L
        private const val CYCLE_SAMPLES = 720
    }
}
