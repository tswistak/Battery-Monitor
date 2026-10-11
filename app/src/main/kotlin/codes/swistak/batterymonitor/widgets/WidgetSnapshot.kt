/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.widgets

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

internal data class WidgetSnapshot(
    val levelPercent: Int,
    val status: Int,
    val temperatureTenthsCelsius: Int,
    val voltageMillivolts: Int?,
    val currentMicroAmps: Long?,
    val remainingChargeMicroampHours: Long?,
    val predictionMinutes: Int?,
    val predictionTarget: Int?,
    val targetReached: Boolean,
    val observedAtMillis: Long,
    val monitoringEnabled: Boolean = true
) {
    fun isStale(now: Long): Boolean =
        observedAtMillis > now || now - observedAtMillis > 15 * 60_000L
}

internal data class WidgetTrendPoint(val time: Long, val value: Double?, val segment: Long)
internal data class WidgetTrend(
    val rangeStart: Long,
    val rangeEnd: Long,
    val points: List<WidgetTrendPoint>,
    val min: Double?,
    val max: Double?,
    val version: Long
)

internal object WidgetSnapshotCodec {
    private const val VERSION = 1

    fun encode(snapshot: WidgetSnapshot): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION)
            with(snapshot) {
                out.writeInt(levelPercent)
                out.writeInt(status)
                out.writeInt(temperatureTenthsCelsius)
                out.writeBoolean(voltageMillivolts != null)
                voltageMillivolts?.let(out::writeInt)
                out.writeBoolean(currentMicroAmps != null)
                currentMicroAmps?.let(out::writeLong)
                out.writeBoolean(remainingChargeMicroampHours != null)
                remainingChargeMicroampHours?.let(out::writeLong)
                out.writeBoolean(predictionMinutes != null)
                predictionMinutes?.let(out::writeInt)
                out.writeBoolean(predictionTarget != null)
                predictionTarget?.let(out::writeInt)
                out.writeBoolean(targetReached)
                out.writeLong(observedAtMillis)
                out.writeBoolean(monitoringEnabled)
            }
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): WidgetSnapshot? = try {
        if (bytes.size > 128) null else DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != VERSION) return null
            WidgetSnapshot(
                input.readInt(),
                input.readInt(),
                input.readInt(),
                if (input.readBoolean()) input.readInt() else null,
                if (input.readBoolean()) input.readLong() else null,
                if (input.readBoolean()) input.readLong() else null,
                if (input.readBoolean()) input.readInt() else null,
                if (input.readBoolean()) input.readInt() else null,
                input.readBoolean(),
                input.readLong(),
                input.readBoolean()
            ).takeIf {
                input.available() == 0 && it.levelPercent in 0..100 && it.observedAtMillis > 0 && (it.predictionMinutes == null || it.predictionMinutes >= 0) && (it.predictionTarget == null || it.predictionTarget in 0..100)
            }
        }
    } catch (_: Exception) {
        null
    }
}

internal object WidgetSnapshotStore {
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "widget_snapshot.bin"))

    @Synchronized
    fun read(context: Context): WidgetSnapshot? = try {
        file(context).openRead().use { WidgetSnapshotCodec.decode(it.readBytes()) }
    } catch (_: Exception) {
        null
    }

    @Synchronized
    fun write(context: Context, snapshot: WidgetSnapshot) {
        val file = file(context)
        val stream = try {
            file.startWrite()
        } catch (error: Exception) {
            Log.w("WidgetSnapshot", "Unable to open snapshot", error)
            return
        }
        try {
            stream.write(WidgetSnapshotCodec.encode(snapshot))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            Log.w("WidgetSnapshot", "Unable to save snapshot", error)
        }
    }
}
