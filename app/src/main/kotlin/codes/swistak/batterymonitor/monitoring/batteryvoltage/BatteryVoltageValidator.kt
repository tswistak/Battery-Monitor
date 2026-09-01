package codes.swistak.batterymonitor.monitoring.batteryvoltage

internal object BatteryVoltageValidator {
    const val MIN_PLAUSIBLE_MILLIVOLTS = 500
    const val MAX_PLAUSIBLE_MILLIVOLTS = 20_000
    private const val MICROVOLTS_PER_MILLIVOLT = 1000L

    fun isValidBroadcastMillivolts(millivolts: Int): Boolean {
        return millivolts in MIN_PLAUSIBLE_MILLIVOLTS..MAX_PLAUSIBLE_MILLIVOLTS
    }

    fun normalizeSysfsVoltage(raw: String?): Int? {
        val value = raw?.trim()?.toLongOrNull() ?: return null
        if (value <= 0 || value > MAX_PLAUSIBLE_MILLIVOLTS * MICROVOLTS_PER_MILLIVOLT) return null

        if (value <= MAX_PLAUSIBLE_MILLIVOLTS) {
            return value.toInt().takeIf { it >= MIN_PLAUSIBLE_MILLIVOLTS }
        }
        return (value / MICROVOLTS_PER_MILLIVOLT).toInt()
            .takeIf { it in MIN_PLAUSIBLE_MILLIVOLTS..MAX_PLAUSIBLE_MILLIVOLTS }
    }

    fun parseDumpsysVoltageMillivolts(dump: String?): Int? {
        if (dump == null) return null

        for (line in dump.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("voltage:", ignoreCase = true)) continue

            val millivolts = trimmed.substringAfter(':').trim().toLongOrNull() ?: return null
            if (millivolts < Int.MIN_VALUE || millivolts > Int.MAX_VALUE) return null
            return millivolts.toInt().takeIf(::isValidBroadcastMillivolts)
        }
        return null
    }
}