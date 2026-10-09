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
package codes.swistak.batterymonitor.ui.diagnostics

import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.advancedstats.AdvancedBatterySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DiagnosticsModelTest {
    @Test
    fun `raw search matches original keys without formatting values or exposing identifiers by default`() {
        val snapshot = AdvancedBatterySnapshot().apply {
            accessMethod = "Android"
            capturedAtMillis = 42
            currentNowUa = -900000
            sysfsLabels.add("battery/serial_number")
            sysfsValues.add("  private serial\nsecond line\n")
        }
        val groups = diagnosticGroups(snapshot, 1)
        val result = filterRawGroups(groups.drop(3), " SERIAL ").single().rows.single()
        assertEquals("battery/serial_number", result.rawKey)
        assertEquals("  private serial\nsecond line\n", result.value)
        assertEquals(result.rawValue, result.value)
        assertEquals(0, result.label)
        assertEquals("", result.unit)
        assertEquals(42L, result.observedAtMillis)
        assertTrue(filterRawGroups(groups.drop(3), "private").isEmpty())
        assertFalse(diagnosticReport(groups, { it.toString() }, false).contains("private serial"))
        assertTrue(
            diagnosticReport(
                groups, { it.toString() }, true
            ).contains("  private serial\nsecond line\n")
        )
    }

    @Test
    fun `current calibration affects battery current but not the Android charger limit`() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val snapshot = AdvancedBatterySnapshot().apply {
                currentNowUa = -900; maxChargingCurrentUa = 2000000
            }
            val groups = diagnosticGroups(snapshot, 1000)
            assertEquals(
                "-900 mA",
                groups[0].rows.single { it.label == R.string.advanced_field_current_now }.value
            )
            assertEquals(
                "2000 mA",
                groups[2].rows.single { it.label == R.string.advanced_field_max_charging_current }.value
            )
            assertEquals(
                "-900",
                groups[0].rows.single { it.label == R.string.advanced_field_current_now }.rawValue
            )
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `normalized groups use available readings once and preserve each reading source and time`() {
        val android = AdvancedBatterySnapshot().apply {
            accessMethod = "Android"
            capturedAtMillis = 200
            currentNowUa = -493000
            chargeCounterUah = 2651000
            reportedCapacityPercent = 73
            fieldSources["current_now"] = "BatteryManager / BATTERY_PROPERTY_CURRENT_NOW"
            fieldSources["capacity"] = "ACTION_BATTERY_CHANGED / level / scale"
        }
        val shizuku = AdvancedBatterySnapshot().apply {
            accessMethod = AdvancedBatterySnapshot.ACCESS_SHIZUKU
            capturedAtMillis = 100
            chargeCounterUah = 2655000
            fullChargeUah = 3000000
            maxChargingCurrentUa = 0
            fieldSources["charge_full"] = "/sys/class/power_supply/battery/charge_full"
        }
        val groups = mergedDiagnosticGroups(listOf(android, shizuku), 1)
        assertEquals(3, groups.size)
        val rows = groups.flatMap { it.rows }
        assertEquals(rows.size, rows.map { it.rawKey }.distinct().size)
        val current = rows.single { it.rawKey == "current_now" }
        assertEquals("-493000", current.rawValue)
        assertEquals("BatteryManager / BATTERY_PROPERTY_CURRENT_NOW", current.source)
        assertEquals("Android", current.accessMethod)
        assertEquals(200L, current.observedAtMillis)
        assertEquals("2651000", rows.single { it.rawKey == "charge_counter" }.rawValue)
        assertEquals(
            "ACTION_BATTERY_CHANGED / level / scale", rows.single { it.rawKey == "capacity" }.source
        )
        val full = rows.single { it.rawKey == "charge_full" }
        assertEquals("3000000", full.rawValue)
        assertEquals("Shizuku", full.accessMethod)
        assertEquals(100L, full.observedAtMillis)
        assertEquals("0", rows.single { it.rawKey == "max_charging_current" }.rawValue)
        assertNull(rows.single { it.rawKey == "estimated_health" }.value)
        assertTrue(diagnosticGroupSources(groups[1]).contains("/sys/class/power_supply (Shizuku)"))
        shizuku.currentNowUa = -600000
        shizuku.capturedAtMillis = 300
        assertEquals(
            "-600000", mergedDiagnosticGroups(
                listOf(android, shizuku), 1
            )[0].rows.single { it.rawKey == "current_now" }.rawValue
        )
        assertTrue(mergedDiagnosticGroups(emptyList(), 1).isEmpty())
    }

    @Test
    fun `estimated health requires positive capacities from the same power supply`() {
        val snapshot =
            AdvancedBatterySnapshot().apply { fullChargeUah = 3000000; designChargeUah = 4000000 }
        assertNull(capacityHealth(snapshot))
        snapshot.fieldSources["charge_full"] = "/sys/class/power_supply/battery/charge_full"
        snapshot.fieldSources["charge_full_design"] =
            "/sys/class/power_supply/bms/charge_full_design"
        assertNull(capacityHealth(snapshot))
        snapshot.fieldSources["charge_full_design"] =
            "/sys/class/power_supply/battery/charge_full_design"
        assertEquals(75.0, capacityHealth(snapshot)!!, 0.0)
        snapshot.designChargeUah = 0
        assertNull(capacityHealth(snapshot))
    }

    @Test
    fun `source labels deduplicate Android app access while preserving privileged names and raw identities`() {
        assertEquals("Android", diagnosticSourceName("App UID"))
        assertEquals("Android", diagnosticSourceName("Android · App UID"))
        assertEquals(
            "Root · Shizuku · Android",
            diagnosticSourceName("Root · Shizuku · App UID · Android · Root")
        )
        val rawValue = " 00012\n"
        val app = AdvancedBatterySnapshot().apply {
            accessMethod = "App UID"
            capturedAtMillis = 200
            chargeCounterUah = 12000
            fieldSources["charge_counter"] = "BatteryManager / BATTERY_PROPERTY_CHARGE_COUNTER"
            metadataLabels.add("original_key")
            metadataValues.add(rawValue)
        }
        val groups = diagnosticGroups(app, 1)
        val appReading = groups[0].rows.single { it.rawKey == "charge_counter" }
        val mixedGroup =
            groups[0].copy(rows = listOf(appReading, appReading.copy(accessMethod = "Android")))
        assertEquals("BatteryManager (Android)", diagnosticGroupSources(mixedGroup))
        val report = diagnosticReport(groups, { it.toString() }, true)
        assertTrue(report.contains("access=Android;"))
        assertFalse(report.contains("App UID"))
        assertTrue(report.contains(rawValue))
        assertEquals("App UID", app.accessMethod)
        assertEquals("App UID", appReading.accessMethod)
        val raw = groups.drop(3).flatMap { it.rows }.single()
        assertEquals("App UID", raw.accessMethod)
        assertEquals(rawValue, raw.rawValue)
        assertEquals(rawValue, app.metadataValues.single())
    }

    @Test
    fun `unsupported app charge counter suppresses broadcast and dumpsys default zeros without changing raw data`() {
        val broadcast = AdvancedBatterySnapshot().apply {
            accessMethod = "Android"
            capturedAtMillis = 100
            chargeCounterUah = 0
            fieldSources["charge_counter"] = "ACTION_BATTERY_CHANGED / charge_counter"
            serviceLabels.add("charge_counter")
            serviceValues.add("0")
        }
        val root = AdvancedBatterySnapshot().apply {
            accessMethod = AdvancedBatterySnapshot.ACCESS_ROOT
            capturedAtMillis = 200
            chargeCounterUah = 0
            fieldSources["charge_counter"] = "dumpsys battery / Charge counter"
            serviceLabels.add("Charge counter")
            serviceValues.add("0")
        }
        for (access in listOf("Android", "App UID")) {
            for (sentinel in listOf(Int.MIN_VALUE.toLong(), Long.MIN_VALUE)) {
                val api = AdvancedBatterySnapshot().apply {
                    accessMethod = access
                    capturedAtMillis = 300
                    metadataLabels.add("BATTERY_PROPERTY_CHARGE_COUNTER")
                    metadataValues.add(sentinel.toString())
                }
                val snapshots = listOf(root, broadcast, api)
                val merged = mergedDiagnosticGroups(snapshots, 1)
                val reading = merged[0].rows.single { it.rawKey == "charge_counter" }
                assertNull(reading.value)
                assertNull(reading.rawValue)
                assertEquals("", reading.source)
                assertFalse(
                    diagnosticReport(
                        merged, { it.toString() }, false
                    ).contains("charge_counter:")
                )
                val raw = snapshots.flatMap { diagnosticGroups(it, 1).drop(3) }.flatMap { it.rows }
                assertEquals("0", raw.single { it.rawKey == "Charge counter" }.rawValue)
                assertEquals("0", raw.single { it.rawKey == "charge_counter" }.rawValue)
                assertEquals(
                    sentinel.toString(),
                    raw.single { it.rawKey == "BATTERY_PROPERTY_CHARGE_COUNTER" }.rawValue
                )
                assertEquals(0L, broadcast.chargeCounterUah)
                assertEquals(0L, root.chargeCounterUah)
            }
        }
    }

    @Test
    fun `charge counter keeps verified zeros and positive fallbacks and uses the newest explicit app API result`() {
        val unsupported = AdvancedBatterySnapshot().apply {
            accessMethod = "App UID"
            capturedAtMillis = 300
            metadataLabels.add("BATTERY_PROPERTY_CHARGE_COUNTER")
            metadataValues.add(Int.MIN_VALUE.toString())
        }
        val defaultZero = AdvancedBatterySnapshot().apply {
            accessMethod = AdvancedBatterySnapshot.ACCESS_ROOT
            capturedAtMillis = 200
            chargeCounterUah = 0
            fieldSources["charge_counter"] = "dumpsys battery / Charge counter"
        }
        val sources = listOf(
            "BatteryManager / BATTERY_PROPERTY_CHARGE_COUNTER" to 0L,
            "cmd battery get charge_counter" to 0L,
            "/sys/class/power_supply/battery/charge_counter" to 0L,
            "ACTION_BATTERY_CHANGED / charge_counter" to 2651000L,
            "dumpsys battery / Charge counter" to 2651000L
        )
        sources.forEach { (source, value) ->
            val fallback = AdvancedBatterySnapshot().apply {
                accessMethod = "Android"
                capturedAtMillis = 100
                chargeCounterUah = value
                fieldSources["charge_counter"] = source
            }
            val reading = mergedDiagnosticGroups(
                listOf(defaultZero, unsupported, fallback), 1
            )[0].rows.single { it.rawKey == "charge_counter" }
            assertEquals(value.toString(), reading.rawValue)
            assertEquals(source, reading.source)
            assertEquals(100L, reading.observedAtMillis)
        }
        assertEquals(
            "0", mergedDiagnosticGroups(
                listOf(defaultZero), 1
            )[0].rows.single { it.rawKey == "charge_counter" }.rawValue
        )
        for (value in listOf(0L, 2651000L)) {
            val latestApi = AdvancedBatterySnapshot().apply {
                accessMethod = "Android"
                capturedAtMillis = 400
                chargeCounterUah = value
                fieldSources["charge_counter"] = "BatteryManager / BATTERY_PROPERTY_CHARGE_COUNTER"
                metadataLabels.add("BATTERY_PROPERTY_CHARGE_COUNTER")
                metadataValues.add(value.toString())
            }
            defaultZero.capturedAtMillis = 500
            val reading = mergedDiagnosticGroups(
                listOf(defaultZero, unsupported, latestApi), 1
            )[0].rows.single { it.rawKey == "charge_counter" }
            assertEquals("0", reading.rawValue)
            assertEquals("dumpsys battery / Charge counter", reading.source)
            assertEquals(500L, reading.observedAtMillis)
        }
    }

    @Test
    fun `battery voltage temperature and power use standard readings and the current calibration`() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val snapshot = AdvancedBatterySnapshot().apply {
                accessMethod = "Android"
                currentNowUa = -900
                fieldSources["current_now"] = "BatteryManager / BATTERY_PROPERTY_CURRENT_NOW"
                serviceLabels.addAll(listOf("voltage", "temperature"))
                serviceValues.addAll(listOf("5000", "250"))
            }
            val rows = diagnosticGroups(snapshot, 1000).first().rows
            assertEquals("5.000 V", rows.single { it.rawKey == "voltage" }.value)
            assertEquals("25.0°C", rows.single { it.rawKey == "temperature" }.value)
            val power = rows.single { it.rawKey == "battery_power" }
            assertEquals("-4.50 W", power.value)
            assertEquals(
                "ACTION_BATTERY_CHANGED / voltage × BatteryManager / BATTERY_PROPERTY_CURRENT_NOW",
                power.source
            )
            assertEquals(
                "77.0°F", diagnosticGroups(
                    snapshot, 1000, fahrenheit = true
                ).first().rows.single { it.rawKey == "temperature" }.value
            )
            snapshot.currentNowUa = null
            assertNull(
                diagnosticGroups(
                    snapshot, 1000
                ).first().rows.single { it.rawKey == "battery_power" }.value
            )
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `invalid service voltage falls back to battery readings with original units and never masks valid merged readings`() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            for ((raw, unit) in listOf(" 3900123\n" to "µV", " 3900\n" to "mV")) {
                val snapshot = AdvancedBatterySnapshot().apply {
                    accessMethod = AdvancedBatterySnapshot.ACCESS_SHIZUKU
                    capturedAtMillis = 300
                    currentNowUa = -500000
                    fieldSources["current_now"] = "/sys/class/power_supply/battery/current_now"
                    serviceLabels.add("voltage")
                    serviceValues.add("0")
                    sysfsLabels.addAll(
                        listOf(
                            "battery/type", "battery/voltage_now", "usb/type", "usb/voltage_now"
                        )
                    )
                    sysfsValues.addAll(listOf("Battery\n", raw, "USB\n", "5000000\n"))
                }
                val groups = diagnosticGroups(snapshot, 1)
                val voltage = groups.first().rows.single { it.rawKey == "voltage" }
                assertEquals("3.900 V", voltage.value)
                assertEquals(raw, voltage.rawValue)
                assertEquals(unit, voltage.unit)
                assertEquals("/sys/class/power_supply/battery/voltage_now", voltage.source)
                assertEquals("Shizuku", voltage.accessMethod)
                assertEquals(300L, voltage.observedAtMillis)
                assertEquals(
                    raw, groups[4].rows.single { it.rawKey == "battery/voltage_now" }.rawValue
                )
                assertEquals("0", groups[3].rows.single { it.rawKey == "voltage" }.rawValue)
                assertEquals(
                    "-1.95 W", groups.first().rows.single { it.rawKey == "battery_power" }.value
                )
                assertEquals(
                    "-1.95 W", mergedDiagnosticGroups(
                        listOf(snapshot), 1
                    ).first().rows.single { it.rawKey == "battery_power" }.value
                )
            }
            val android = AdvancedBatterySnapshot().apply {
                accessMethod = "Android"
                capturedAtMillis = 200
                currentNowUa = -500000
                serviceLabels.add("voltage")
                serviceValues.add("4000")
            }
            val invalid = AdvancedBatterySnapshot().apply {
                accessMethod = AdvancedBatterySnapshot.ACCESS_SHIZUKU
                capturedAtMillis = 300
                serviceLabels.add("voltage")
                serviceValues.add("0")
                sysfsLabels.addAll(listOf("usb/type", "usb/voltage_now"))
                sysfsValues.addAll(listOf("USB\n", "5000000\n"))
            }
            assertNull(
                diagnosticGroups(
                    invalid, 1
                ).first().rows.single { it.rawKey == "voltage" }.value
            )
            val rows = mergedDiagnosticGroups(listOf(android, invalid), 1).first().rows
            assertEquals("4.000 V", rows.single { it.rawKey == "voltage" }.value)
            assertEquals("Android", rows.single { it.rawKey == "voltage" }.accessMethod)
            assertEquals(200L, rows.single { it.rawKey == "voltage" }.observedAtMillis)
            assertEquals("-2.00 W", rows.single { it.rawKey == "battery_power" }.value)
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `device tabs use friendly labels and verified units while unknown OEM readings stay raw`() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val older = AdvancedBatterySnapshot().apply {
                accessMethod = AdvancedBatterySnapshot.ACCESS_SHIZUKU
                capturedAtMillis = 100
                maxChargingVoltageUv = 0
                serviceLabels.addAll(
                    listOf(
                        "Charger voltage",
                        "ChargeFastCharger",
                        "PhoneTemp",
                        "temperature",
                        "ChargerTechnology"
                    )
                )
                serviceValues.addAll(listOf("4310", "true", "310", "311", "1"))
                sysfsLabels.addAll(
                    listOf(
                        "battery/charge_now",
                        "bms/charge_now",
                        "bms/charge_now_raw",
                        "battery/charge_type",
                        "usb/current_max",
                        "usb/type",
                        "usb/health",
                        "battery/health",
                        "battery/technology",
                        "battery/voltage_max",
                        "battery/voltage_max_design",
                        "usb/voltage_now",
                        "usb/input_voltage_limit",
                        "usb/input_current_limit",
                        "usb/online",
                        "battery/charge_enabled",
                        "battery/input_current_max",
                        "battery/input_current_limit",
                        "battery/input_voltage_limit"
                    )
                )
                sysfsValues.addAll(
                    listOf(
                        "4974\n",
                        "0\n",
                        "2571662\n",
                        "Fast\n",
                        "500000\n",
                        "USB\n",
                        "Good\n",
                        "Good\n",
                        "Li-ion\n",
                        "4320\n",
                        "4320000\n",
                        "-19\n",
                        "5000000\n",
                        "1500000\n",
                        "2\n",
                        "1\n",
                        "700000\n",
                        "1500000\n",
                        "5000000\n"
                    )
                )
            }
            val newer = AdvancedBatterySnapshot().apply {
                accessMethod = AdvancedBatterySnapshot.ACCESS_ROOT
                capturedAtMillis = 200
                sysfsLabels.addAll(listOf("usb/current_max", "usb/type"))
                sysfsValues.addAll(listOf("1000000\n", "USB\n"))
            }
            val snapshots = listOf(older, newer)
            val charging = deviceDiagnosticGroups(snapshots, charging = true).flatMap { it.rows }
            assertTrue(charging.all { it.label != 0 })
            assertTrue(charging.none {
                it.rawKey in setOf(
                    "Charger voltage",
                    "ChargerTechnology",
                    "PhoneTemp",
                    "usb/voltage_now",
                    "battery/charge_enabled"
                )
            })
            val current = charging.single { it.rawKey == "usb/current_max" }
            assertEquals(R.string.advanced_field_max_charging_current, current.label)
            assertEquals("1000 mA", current.value)
            assertEquals("1000000\n", current.rawValue)
            assertEquals("Root", current.accessMethod)
            assertEquals(200L, current.observedAtMillis)
            assertEquals("/sys/class/power_supply / usb/current_max", current.source)
            assertEquals(
                R.string.yes, charging.single { it.rawKey == "ChargeFastCharger" }.valueLabel
            )
            assertEquals(
                R.string.diag_charge_fast,
                charging.single { it.rawKey == "battery/charge_type" }.valueLabel
            )
            assertEquals(
                R.string.health_good, charging.single { it.rawKey == "usb/health" }.valueLabel
            )
            assertEquals(
                R.string.diag_supply_programmable,
                charging.single { it.rawKey == "usb/online" }.valueLabel
            )
            assertEquals(
                "5.000 V", charging.single { it.rawKey == "usb/input_voltage_limit" }.value
            )
            assertEquals(
                "1500 mA", charging.single { it.rawKey == "usb/input_current_limit" }.value
            )
            assertEquals(
                R.string.diag_settled_input_current,
                charging.single { it.rawKey == "battery/input_current_max" }.label
            )
            assertEquals(
                "700 mA", charging.single { it.rawKey == "battery/input_current_max" }.value
            )
            assertEquals(
                "1500 mA", charging.single { it.rawKey == "battery/input_current_limit" }.value
            )
            assertEquals(
                "5.000 V", charging.single { it.rawKey == "battery/input_voltage_limit" }.value
            )
            assertTrue(charging.none { it.rawKey == "battery/health" })
            val battery = deviceDiagnosticGroups(snapshots, charging = false).flatMap { it.rows }
            assertTrue(battery.all { it.label != 0 })
            assertTrue(battery.none {
                it.rawKey in setOf(
                    "PhoneTemp",
                    "battery/charge_now",
                    "bms/charge_now",
                    "bms/charge_now_raw",
                    "battery/voltage_max",
                    "usb/health"
                )
            })
            assertEquals("Li-ion", battery.single { it.rawKey == "battery/technology" }.value)
            assertEquals(
                "4.320 V", battery.single { it.rawKey == "battery/voltage_max_design" }.value
            )
            assertEquals(
                R.string.health_good, battery.single { it.rawKey == "battery/health" }.valueLabel
            )
            assertNull(older.chargingPolicy)
            assertNull(older.chargeCounterUah)
            assertEquals(0L, older.maxChargingVoltageUv)
            val raw = diagnosticGroups(older, 1).drop(3).flatMap { it.rows }
            assertEquals("4310", raw.single { it.rawKey == "Charger voltage" }.value)
            assertEquals("2571662\n", raw.single { it.rawKey == "bms/charge_now_raw" }.value)
            assertEquals("-19\n", raw.single { it.rawKey == "usb/voltage_now" }.value)
            assertTrue(deviceDiagnosticGroups(emptyList(), charging = true).isEmpty())
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `charging enums are localized using their original source schema`() {
        val snapshot = AdvancedBatterySnapshot().apply {
            chargingState = "2"
            chargingPolicy = "2"
            capacityLevel = "3"
            fieldSources["charging_state"] = "dumpsys battery / Charging state"
            fieldSources["charging_policy"] = "dumpsys battery / Charging policy"
        }

        fun row(key: String) = diagnosticGroups(snapshot, 1)[2].rows.single { it.rawKey == key }
        assertEquals(R.string.diag_charging_too_cold, row("charging_state").valueLabel)
        assertEquals(R.string.diag_charge_long_life, row("charging_policy").valueLabel)
        snapshot.chargingPolicy = "4"
        assertEquals(R.string.diag_charge_long_life, row("charging_policy").valueLabel)
        assertEquals(R.string.normal, row("capacity_level").valueLabel)
        assertEquals(
            "Zbyt niska temperatura",
            formattedDiagnosticValue(row("charging_state")) { "Zbyt niska temperatura" })
        assertEquals("2", row("charging_state").rawValue)
        snapshot.fieldSources["charging_state"] =
            "ACTION_BATTERY_CHANGED / android.os.extra.CHARGING_STATUS"
        assertEquals(R.string.diag_charging_too_cold, row("charging_state").valueLabel)
        for (unsupported in listOf("-1", "6", "vendor_code_31")) {
            snapshot.chargingState = unsupported
            assertNull(formattedDiagnosticValue(row("charging_state")) { it.toString() })
            assertEquals(unsupported, row("charging_state").rawValue)
        }
        snapshot.chargingState = "2"
        snapshot.fieldSources["charging_state"] = "ACTION_BATTERY_CHANGED / status"
        assertEquals(R.string.status_charging, row("charging_state").valueLabel)
        snapshot.fieldSources["charging_state"] = "/sys/class/power_supply/battery/status"
        snapshot.chargingState = "Discharging\n"
        assertEquals(R.string.status_discharging, row("charging_state").valueLabel)
        snapshot.fieldSources["charging_policy"] =
            "BatteryManager / BATTERY_PROPERTY_CHARGING_POLICY"
        snapshot.chargingPolicy = "adaptive_aon"
        assertEquals(R.string.diag_policy_fixed_limit, row("charging_policy").valueLabel)
        snapshot.chargingState = "vendor_code_31"
        assertNull(formattedDiagnosticValue(row("charging_state")) { it.toString() })
        assertEquals("vendor_code_31", row("charging_state").rawValue)
        val newer = AdvancedBatterySnapshot().apply {
            capturedAtMillis = 100
            chargingState = "Full"
            fieldSources["charging_state"] = "/sys/class/power_supply/battery/status"
        }
        val merged = mergedDiagnosticGroups(listOf(snapshot, newer), 1)[2]
        assertEquals(
            R.string.status_fully_charged,
            merged.rows.single { it.rawKey == "charging_state" }.valueLabel
        )
        assertTrue(diagnosticGroupSources(merged).contains("/sys/class/power_supply"))
    }

    @Test
    fun `inconsistent learned capacity remains visible without a misleading health estimate`() {
        val snapshot = AdvancedBatterySnapshot().apply {
            fullChargeUah = 18772000
            designChargeUah = 3292000
            fieldSources["charge_full"] = "/sys/class/power_supply/bms/charge_full"
            fieldSources["charge_full_design"] = "/sys/class/power_supply/bms/charge_full_design"
        }
        assertTrue(inconsistentCapacities(diagnosticGroups(snapshot, 1)[1]))
        assertNull(capacityHealth(snapshot))
        val rows = diagnosticGroups(snapshot, 1)[1].rows
        assertEquals("18772000", rows.single { it.rawKey == "charge_full" }.rawValue)
        assertEquals("3292000", rows.single { it.rawKey == "charge_full_design" }.rawValue)
        snapshot.fullChargeUah = 3000000
        assertFalse(inconsistentCapacities(diagnosticGroups(snapshot, 1)[1]))
        assertEquals(3000000 * 100.0 / 3292000, capacityHealth(snapshot)!!, 0.0)
    }

    @Test
    fun `merged health uses coherent capacities and power follows displayed voltage and current`() {
        val older = AdvancedBatterySnapshot().apply {
            accessMethod = AdvancedBatterySnapshot.ACCESS_ROOT
            capturedAtMillis = 100
            fullChargeUah = 3000000
            designChargeUah = 4000000
            currentNowUa = 900000
            serviceLabels.add("voltage")
            serviceValues.add("5000")
            fieldSources["charge_full"] = "/sys/class/power_supply/bms/charge_full"
            fieldSources["charge_full_design"] = "/sys/class/power_supply/bms/charge_full_design"
        }
        val newer = AdvancedBatterySnapshot().apply {
            accessMethod = AdvancedBatterySnapshot.ACCESS_ROOT
            capturedAtMillis = 200
            fullChargeUah = 18772000
            designChargeUah = 3292000
            currentNowUa = 500000
            fieldSources.putAll(older.fieldSources)
        }
        val merged = mergedDiagnosticGroups(listOf(older, newer), 1)
        assertNull(merged[1].rows.single { it.rawKey == "estimated_health" }.value)
        assertTrue(inconsistentCapacities(merged[1]))
        assertEquals("500000", merged[0].rows.single { it.rawKey == "current_now" }.rawValue)
        val power = merged[0].rows.single { it.rawKey == "battery_power" }
        assertEquals("2.5", power.rawValue)
        assertEquals(200L, power.observedAtMillis)
        assertTrue(power.source.contains("voltage"))
        assertTrue(power.source.contains("current_now"))
        newer.fullChargeUah = 3000000
        newer.designChargeUah = 4000000
        val corrected = mergedDiagnosticGroups(listOf(older, newer), 1)
        assertFalse(inconsistentCapacities(corrected[1]))
        assertEquals("75.0", corrected[1].rows.single { it.rawKey == "estimated_health" }.rawValue)
        older.accessMethod = "Android"
        val mixedGroup = mergedDiagnosticGroups(listOf(older, newer), 1)[0]
        val mixed = mixedGroup.rows.single { it.rawKey == "battery_power" }
        assertEquals("2.5", mixed.rawValue)
        assertEquals("Android · Root", mixed.accessMethod)
        assertEquals(
            "dumpsys battery (Root) · ACTION_BATTERY_CHANGED (Android)",
            diagnosticGroupSources(mixedGroup)
        )
    }

    @Test
    fun `missing normalized readings explain their requirements while available and raw details stay unchanged`() {
        val row = DiagnosticRow(
            R.string.advanced_field_current_average,
            "current_average",
            null,
            null,
            "µA",
            "",
            42L,
            "Android"
        )
        val explanations = mapOf(
            "current_average" to R.string.diag_missing_average,
            "estimated_health" to R.string.diag_missing_estimated_health,
            "charge_time_remaining" to R.string.diag_missing_charge_time,
            "battery_power" to R.string.battery_power_explanation
        )
        explanations.forEach { (key, expected) ->
            val missing = row.copy(rawKey = key)
            assertEquals(expected, missingDiagnosticExplanation(missing, null))
            assertEquals(0, missingDiagnosticExplanation(missing.copy(value = "0"), "0"))
            assertEquals(0, missingDiagnosticExplanation(missing.copy(label = 0), null))
        }
        assertEquals(0, missingDiagnosticExplanation(row.copy(rawKey = "energy_counter"), null))
    }
}
