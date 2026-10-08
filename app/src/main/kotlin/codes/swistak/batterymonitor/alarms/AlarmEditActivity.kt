/*
    Copyright (c) 2009-2020 Darshan Computing, LLC
    Modified in 2026 by Tomasz Świstak <tomasz@swistak.codes> for the Battery Monitor fork.
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.alarms

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import codes.swistak.batterymonitor.app.BatteryInfoActivity
import codes.swistak.batterymonitor.ui.navigation.SectionOwner

class AlarmEditActivity : Activity() {
    companion object {
        const val EXTRA_ALARM_ID = "codes.swistak.batterymonitor.AlarmID"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, BatteryInfoActivity::class.java).apply {
            putExtra(BatteryInfoActivity.EXTRA_SECTION, SectionOwner.ALARMS.route)
            putExtra(BatteryInfoActivity.EXTRA_DETAIL, "alarm-edit")
            putExtra(EXTRA_ALARM_ID, intent.getIntExtra(EXTRA_ALARM_ID, -1))
        })
        finish()
    }
}
