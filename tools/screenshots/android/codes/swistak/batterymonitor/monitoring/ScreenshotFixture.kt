package codes.swistak.batterymonitor.monitoring

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import codes.swistak.batterymonitor.alarms.AlarmDatabase
import codes.swistak.batterymonitor.alarms.backup.AlarmBackup
import codes.swistak.batterymonitor.app.StartupDialogViewModel
import codes.swistak.batterymonitor.devicebackup.DeviceDataBackup
import codes.swistak.batterymonitor.devicebackup.DeviceDataType
import codes.swistak.batterymonitor.devicebackup.LogImportMode
import codes.swistak.batterymonitor.logs.LogDatabase
import codes.swistak.batterymonitor.logs.LogRecord
import codes.swistak.batterymonitor.settings.SettingsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal object ScreenshotInput {
    private var file: File? = null
    fun setContext(context: Context) {
        file = File(context.filesDir, "screenshot-input.json")
    }

    private fun read(): JSONObject? = file?.takeIf(File::isFile)?.let { JSONObject(it.readText()) }
    fun current(): BatteryCurrentReading? = read()?.let {
        BatteryCurrentReading(
            it.getLong("currentMicroAmps"),
            "Synthetic screenshot input",
            BatteryMeasurementTime.now()
        )
    }

    fun remainingCharge(): Long? = read()?.getLong("remainingChargeMicroampHours")
    fun batteryIntent(): Intent? = read()?.let { row ->
        val decoded = LogDatabase.decodeStatus(row.getInt("status"))
        Intent(Intent.ACTION_BATTERY_CHANGED).putExtra("level", row.getInt("charge"))
            .putExtra("scale", 100).putExtra("status", decoded[0]).putExtra("plugged", decoded[1])
            .putExtra("health", BatteryManager.BATTERY_HEALTH_GOOD)
            .putExtra("temperature", row.getInt("temperature"))
            .putExtra("voltage", row.getInt("voltage"))
    }
}

class ScreenshotFixtureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        require(context.packageName == "codes.swistak.batterymonitor.debug")
        require(Build.PRODUCT.contains("sdk") && intent.getBooleanExtra("disposable", false)) {
            "Screenshot fixture requires an explicitly disposable emulator"
        }
        val pending = goAsync()
        Thread {
            try {
                val result = when (intent.action) {
                    "codes.swistak.batterymonitor.debug.SCREENSHOT_SEED" -> seed(context, intent)
                    else -> error("Unsupported screenshot action")
                }
                pending.resultCode = Activity.RESULT_OK
                pending.setResultExtras(result)
            } catch (error: Exception) {
                Log.e("ScreenshotFixture", "Preparation failed", error)
                pending.resultCode = Activity.RESULT_CANCELED
                pending.setResultExtras(Bundle().apply { putString("error", error.toString()) })
            } finally {
                pending.finish()
            }
        }.apply { name = "screenshot-fixture"; start() }
    }

    private fun seed(context: Context, intent: Intent): Bundle {
        val anchor = intent.getLongExtra("anchorMillis", 0)
        require(anchor in 1_577_836_800_000L..System.currentTimeMillis())
        require(System.currentTimeMillis() - anchor < 300_000L) { "Use the emulator's current clock" }
        val state = intent.getStringExtra("state") ?: "charging"
        require(state in setOf("charging", "discharging"))
        val charging = state == "charging"
        context.stopService(Intent(context, BatteryInfoService::class.java))
        val rows = demonstrationHistory(anchor, charging)
        val final = rows.last()
        check(final.charge == 68 && final.temperature == (if (charging) 330 else 290) && final.voltage == 4000)
        check(final.currentMicroAmps == (if (charging) 1_650_000L else -140_000L) && final.remainingChargeMicroampHours == 3_060_000L)

        val settings = context.getSharedPreferences(SettingsContract.SETTINGS_FILE, 0)
        check(
            settings.edit().clear().putBoolean(SettingsContract.KEY_ENABLE_LOGGING, true)
                .putBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false)
                .putString(SettingsContract.KEY_BATTERY_CURRENT_REFRESH_INTERVAL, "2")
                .putString(SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER, "1").putBoolean(
                    SettingsContract.KEY_BATTERY_CURRENT_MULTIPLIER_DETECTION_PENDING, false
                ).putBoolean(SettingsContract.KEY_PREFER_AVERAGE_BATTERY_CURRENT, false)
                .putBoolean(SettingsContract.KEY_DISPLAY_CURRENT_IN_NOTIFICATION, true)
                .putBoolean(SettingsContract.KEY_SHOW_REMAINING_CHARGE, true)
                .putString(SettingsContract.KEY_TEMPERATURE_UNIT, "celsius")
                .putString(SettingsContract.KEY_COLOR_SOURCE, "BatteryBlue")
                .putString(SettingsContract.KEY_BRIGHTNESS, "Light")
                .putString(SettingsContract.KEY_LIVE_UPDATE_DISPLAY, "never")
                .putString(SettingsContract.KEY_AUTOSTART, "auto").putString(
                    SettingsContract.KEY_CHARGING_TARGET_MODE,
                    SettingsContract.CHARGING_TARGET_MODE_CUSTOM
                ).putInt(SettingsContract.KEY_CUSTOM_CHARGING_TARGET, 80)
                .putInt(SettingsContract.KEY_DISCHARGING_TARGET, 0).putStringSet(
                    SettingsContract.KEY_VITAL_SIGNS_CONTENT,
                    SettingsContract.ALL_VITAL_SIGNS_CONTENT.toSet()
                ).putBoolean(SettingsContract.KEY_EXPANDED_NOTIFICATION_DETAILS, true).commit()
        )
        val version = PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(
                context.packageName, 0
            )
        )
        check(
            context.getSharedPreferences(SettingsContract.SP_MAIN_FILE, 0).edit().clear()
                .putBoolean(SettingsContract.KEY_FIRST_RUN, false)
                .putLong(StartupDialogViewModel.KEY_ACKNOWLEDGED_VERSION, version).commit()
        )
        val finalStatus = LogDatabase.decodeStatus(final.status)
        val sessionStart =
            rows.last { LogDatabase.decodeStatus(it.status)[2] == LogDatabase.STATUS_NEW }
        check(
            context.getSharedPreferences(SettingsContract.SP_SERVICE_FILE, 0).edit().clear().putInt(
                BatteryInfo.KEY_LAST_STATUS,
                if (charging) BatteryInfo.STATUS_CHARGING else BatteryInfo.STATUS_UNPLUGGED
            ).putInt(BatteryInfo.KEY_LAST_PLUGGED, finalStatus[1])
                .putInt(BatteryInfo.KEY_LAST_PERCENT, checkNotNull(sessionStart.charge))
                .putLong(BatteryInfo.KEY_LAST_STATUS_CTM, sessionStart.time)
                .putInt(BatteryInfoService.KEY_PREVIOUS_CHARGE, 68)
                .putInt(BatteryInfoService.KEY_PREVIOUS_TEMP, checkNotNull(final.temperature))
                .putInt(BatteryInfoService.KEY_PREVIOUS_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD)
                .putLong("history_sample_elapsed", SystemClock.elapsedRealtime()).putInt(
                    "history_sample_boot",
                    Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
                ).commit()
        )

        // Import history through the production JSON reader; predictor learning is a separate real replay.
        val reboot =
            LogRecord(LogDatabase.STATUS_BOOT_COMPLETED, null, anchor - 5 * 60 * MINUTE, null, null)
        val history = (rows + reboot).sortedBy(LogRecord::time)
        val json = JSONObject().put("version", 2).put("logs", JSONArray(history.map(::jsonRow)))
        val stagedLogs = File.createTempFile("screenshot-history-", ".logs", context.cacheDir)
        try {
            val prepared =
                json.toString().byteInputStream().use { DeviceDataBackup.read(it, stagedLogs) }
            DeviceDataBackup.restore(
                context, prepared, setOf(DeviceDataType.LOGS), LogImportMode.REPLACE
            )
        } finally {
            stagedLogs.delete()
        }
        val core = PredictorCore(-1f, -1f, -1f, -1f).apply { setTargets(80, 0) }
        val averages = mutableMapOf<String, Float>()
        val elapsed = SystemClock.elapsedRealtime()
        val info = BatteryInfo()
        rows.forEach { row ->
            val status = LogDatabase.decodeStatus(row.status)
            info.percent = checkNotNull(row.charge)
            info.status = if (status[1] == 0) BatteryInfo.STATUS_UNPLUGGED else status[0]
            info.plugged = status[1]
            core.update(info, elapsed + row.time - anchor)
            averages[Predictor.KEY_AVERAGE[core.curChargingStatus]] = core.longTermAverage.toFloat()
        }
        val editor = context.getSharedPreferences(Predictor.STORE_NAME, 0).edit().clear()
        averages.forEach { (key, value) -> editor.putFloat(key, value) }
        check(editor.putInt(Predictor.KEY_STATE_VERSION, Predictor.STATE_VERSION).commit())
        val eta = info.prediction.whenHappened - elapsed
        check(eta in (if (charging) 600_000L..2_700_000L else 3_600_000L..172_800_000L)) { "Implausible $state estimate: $eta" }
        check(info.prediction.targetPercent == if (charging) 80 else 0)
        if (charging) check(info.fullRangePrediction.whenHappened > info.prediction.whenHappened)
        val restartedPredictor = Predictor(context)
        restartedPredictor.update(info, chargingLimit = null)
        val restartedEta = info.prediction.whenHappened - SystemClock.elapsedRealtime()
        check(restartedEta in (if (charging) 600_000L..2_700_000L else 3_600_000L..172_800_000L)) {
            "Implausible persisted $state estimate: $restartedEta"
        }

        val alarms = JSONObject().put("version", 2).put("alarms", JSONArray().apply {
            fun alarm(type: String, threshold: String, enabled: Boolean) {
                put(
                    JSONObject().put("type", type).put("threshold", threshold)
                        .put("enabled", enabled)
                )
            }
            alarm("charge_rises", "80", true)
            alarm("charge_rises", "90", false)
            alarm("charge_drops", "25", true)
            alarm("temp_rises", "400", true)
            alarm("fully_charged", "", true)
        })
        AlarmDatabase(context).let { database ->
            try {
                AlarmBackup.importFromJson(database, alarms.toString())
            } finally {
                database.close()
            }
        }
        check(MonitoringDesiredState.write(context, true))
        File(context.filesDir, "screenshot-input.json").writeText(jsonRow(final).toString())
        File(context.filesDir, "screenshot-history.json").writeText(json.toString())
        val events = rows.count { LogDatabase.decodeStatus(it.status)[2] == LogDatabase.STATUS_NEW }
        return Bundle().apply {
            putInt("rows", history.size)
            putInt("transitions", events)
            putLong("anchorMillis", anchor); putLong("replayEstimateMillis", eta)
            putLong("persistedEstimateMillis", restartedEta)
            putString("scenario", "seven-varied-days-4500mAh-${state}68-manual80")
        }
    }
}

private const val MINUTE = 60_000L
private const val DAY = 86_400_000L

private data class Point(val time: Long, val level: Double)

private fun demonstrationHistory(anchor: Long, charging: Boolean): List<LogRecord> {
    val start = anchor - 7 * DAY
    val zone = ZoneId.systemDefault()
    val firstDate = Instant.ofEpochMilli(start).atZone(zone).toLocalDate().minusDays(1)
    val points = mutableListOf<Point>()
    // Seven distinct days join at midnight; two include a short top-up and two need no charge.
    val profiles = listOf(
        listOf( // Overnight charge, then a full day away from power.
            0.0 to 35.0,
            1.0 to 33.0,
            1.6 to 58.0,
            2.1 to 77.0,
            2.7 to 91.0,
            3.15 to 96.0,
            7.0 to 92.0,
            9.0 to 84.0,
            12.0 to 67.0,
            14.0 to 54.0,
            18.0 to 40.0,
            21.0 to 32.0
        ), listOf( // Late morning charge after a low overnight level.
            0.0 to 29.0,
            7.0 to 23.0,
            9.0 to 17.0,
            9.6 to 42.0,
            10.15 to 63.0,
            10.7 to 79.0,
            11.1 to 86.0,
            13.0 to 74.0,
            17.0 to 52.0,
            19.0 to 47.0,
            21.0 to 40.0
        ), listOf( // A short morning top-up and a partial evening charge.
            0.0 to 36.0,
            7.0 to 29.0,
            10.0 to 20.0,
            10.35 to 33.0,
            10.6 to 40.0,
            13.0 to 30.0,
            17.0 to 21.0,
            17.45 to 40.0,
            17.85 to 54.0,
            18.2 to 63.0,
            21.0 to 51.0
        ), listOf( // Heavier use ends with one longer evening charge.
            0.0 to 47.0,
            7.0 to 40.0,
            10.0 to 30.0,
            12.0 to 24.0,
            16.0 to 16.0,
            18.0 to 12.0,
            18.6 to 37.0,
            19.2 to 60.0,
            19.8 to 78.0,
            20.3 to 88.0,
            20.8 to 94.0
        ), listOf( // Lighter use, with no charger connection.
            0.0 to 89.0,
            7.0 to 83.0,
            9.0 to 76.0,
            12.0 to 65.0,
            15.0 to 60.0,
            18.0 to 51.0,
            21.0 to 43.0
        ), listOf( // A brief top-up before going out, then an evening charge.
            0.0 to 39.0,
            7.0 to 33.0,
            8.4 to 24.0,
            8.75 to 38.0,
            9.0 to 47.0,
            12.0 to 34.0,
            15.0 to 20.0,
            18.0 to 12.0,
            18.65 to 39.0,
            19.25 to 62.0,
            19.8 to 77.0,
            20.3 to 86.0,
            20.7 to 90.0
        ), listOf( // Another day on the previous evening's charge.
            0.0 to 84.0,
            7.0 to 78.0,
            10.0 to 64.0,
            12.0 to 52.0,
            15.0 to 48.0,
            18.0 to 42.0,
            21.0 to 39.0
        )
    )
    for (day in 0..9) {
        val date = firstDate.plusDays(day.toLong())
        profiles[day % profiles.size].forEach { (hour, level) ->
            points += Point(
                date.atStartOfDay(zone).toInstant().toEpochMilli() + (hour * 60 * MINUTE).toLong(),
                level
            )
        }
    }
    val finalStart = anchor - 60 * MINUTE
    points.removeAll { it.time >= finalStart - 90 * MINUTE }
    val last = points.maxBy { it.time }
    points += Point(
        finalStart - 90 * MINUTE,
        last.level - (finalStart - 90 * MINUTE - last.time) / MINUTE * 0.06
    )
    points += Point(finalStart, if (charging) 31.0 else 71.11111111111111)
    points += Point(anchor - 30 * MINUTE, if (charging) 49.66666666666667 else 69.55555555555556)
    points += Point(anchor, 68.0)
    points.sortBy { it.time }
    val times = sortedSetOf<Long>().apply {
        var t = start
        while (t <= anchor) {
            add(t); t += 2 * MINUTE
        }
        addAll(points.filter { it.time in start..anchor }.map { it.time })
        add(anchor)
    }
    var segment = 0
    var priorPlugged: Int? = null
    var priorLevel: Int? = null
    var temperature = 270.0
    var finalTemperatureStart: Double? = null
    val result = times.map { time ->
        while (segment + 1 < points.lastIndex && points[segment + 1].time <= time) segment++
        val a = points[segment];
        val b = points[segment + 1]
        val fraction = (time - a.time).toDouble() / (b.time - a.time)
        val level = a.level + (b.level - a.level) * fraction
        val current =
            ((b.level - a.level) / 100 * 4_500_000 / ((b.time - a.time) / 3_600_000.0)).roundToLong()
        val plugged = if (current > 0) BatteryManager.BATTERY_PLUGGED_AC else 0
        val targetTemp =
            if (plugged != 0) 325.0 + abs(current) / 150_000 else 255.0 + abs(current) / 35_000
        temperature += (targetTemp - temperature) * (1 - exp(-2.0 / 15))
        val finalFraction =
            ((time - finalStart).toDouble() / (anchor - finalStart)).coerceIn(0.0, 1.0)
        if (time >= finalStart) {
            if (finalTemperatureStart == null) finalTemperatureStart = temperature
            val finalTemperature = if (charging) 330 else 290
            temperature = checkNotNull(finalTemperatureStart) + (finalTemperature - checkNotNull(
                finalTemperatureStart
            )) * finalFraction
        }
        val roundedLevel = level.roundToInt()
        val statusAge = when {
            priorPlugged == null -> LogDatabase.STATUS_SAMPLE
            priorPlugged != plugged -> LogDatabase.STATUS_NEW
            priorLevel != roundedLevel -> LogDatabase.STATUS_OLD
            else -> LogDatabase.STATUS_SAMPLE
        }
        priorPlugged = plugged
        priorLevel = roundedLevel
        LogRecord(
            LogDatabase.encodeStatus(
                if (plugged != 0) BatteryInfo.STATUS_CHARGING else BatteryInfo.STATUS_UNPLUGGED,
                plugged,
                statusAge
            ),
            roundedLevel,
            time,
            temperature.roundToInt(),
            (3320 + level * 10).roundToInt(),
            current,
            (level / 100 * 4_500_000).roundToLong()
        )
    }
    check(result.first().time == start && result.last().time == anchor)
    check(result.zipWithNext().all { (a, b) -> a.time < b.time && b.time - a.time <= 2 * MINUTE })
    check(result.all { it.charge in 1..100 && it.temperature in 200..400 && it.voltage in 3300..4350 })
    check(
        result.zipWithNext()
            .all { (a, b) -> abs(checkNotNull(b.temperature) - checkNotNull(a.temperature)) <= 10 })
    val transitions = result.zipWithNext()
        .count { (a, b) -> LogDatabase.decodeStatus(a.status)[1] != LogDatabase.decodeStatus(b.status)[1] }
    check(result.count { LogDatabase.decodeStatus(it.status)[2] == LogDatabase.STATUS_NEW } == transitions)
    return result
}

private fun jsonRow(row: LogRecord): JSONObject =
    JSONObject().put("status", row.status).put("charge", row.charge).put("time", row.time)
        .put("temperature", row.temperature).put("voltage", row.voltage)
        .put("currentMicroAmps", row.currentMicroAmps ?: JSONObject.NULL)
        .put("remainingChargeMicroampHours", row.remainingChargeMicroampHours ?: JSONObject.NULL)
