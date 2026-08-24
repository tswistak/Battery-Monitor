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
package codes.swistak.batterymonitor.settings

import android.content.res.Resources
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.common.EdgeToEdgeHelper

class SettingsHelpActivity : AppCompatActivity() {
    private var res: Resources? = null
    private var hasLinks = intArrayOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefScreen = intent.getStringExtra(SettingsContract.EXTRA_SCREEN)
        res = resources

        val ab = supportActionBar
        if (ab != null) {
            ab.setHomeButtonEnabled(true)
            ab.setDisplayHomeAsUpEnabled(true)
            ab.elevation = 0f
        }

        when (prefScreen) {
            null -> {
                setContentView(R.layout.main_settings_help)
                setWindowSubtitle(res!!.getString(R.string.settings_activity_subtitle))
            }

            SettingsContract.KEY_NOTIFICATION_SETTINGS -> {
                setContentView(R.layout.notification_settings_help)
                setWindowSubtitle(res!!.getString(R.string.notification_settings))
            }

            SettingsContract.KEY_CURRENT_STATE_SETTINGS -> {
                setContentView(R.layout.current_state_settings_help)
                setWindowSubtitle(res!!.getString(R.string.tab_current_info))
            }

            SettingsContract.KEY_OTHER_SETTINGS -> {
                setContentView(R.layout.other_settings_help)
                setWindowSubtitle(res!!.getString(R.string.other_settings))

                hasLinks = intArrayOf()
            }

            SettingsContract.KEY_TIME_ESTIMATES_SETTINGS -> {
                setContentView(R.layout.time_estimates_settings_help)
                setWindowSubtitle(res!!.getString(R.string.time_estimates_settings))
            }

            SettingsContract.KEY_ADVANCED_SETTINGS -> {
                setContentView(R.layout.advanced_settings_help)
                setWindowSubtitle(res!!.getString(R.string.advanced_settings))
            }

            SettingsContract.KEY_BACKUP_RESTORE_SETTINGS -> {
                setContentView(R.layout.backup_restore_settings_help)
                setWindowSubtitle(res!!.getString(R.string.pref_backup_restore))
            }

            SettingsContract.KEY_DIAGNOSTICS_SETTINGS -> {
                setContentView(R.layout.diagnostics_settings_help)
                setWindowSubtitle(res!!.getString(R.string.diagnostics))
                hasLinks = intArrayOf(R.id.charging_diagnostics_help_contact)
            }

            SettingsContract.KEY_ADVANCED_INFO_HELP -> {
                setContentView(R.layout.advanced_info_help)
                setWindowSubtitle(res!!.getString(R.string.tab_advanced))
            }

            SettingsContract.KEY_ALARMS_SETTINGS -> {
                setContentView(R.layout.alarm_settings_help)
                setWindowSubtitle(res!!.getString(R.string.alarm_settings))
            }

            SettingsContract.KEY_ALARM_EDIT_SETTINGS -> {
                setContentView(R.layout.alarm_edit_help)
                setWindowSubtitle(res!!.getString(R.string.alarm_settings_subtitle))
            }

            else -> {
                setContentView(R.layout.main_settings_help)
            }
        }

        EdgeToEdgeHelper.applyIfNeeded(this)

        var tv: TextView
        val linkMovement = LinkMovementMethod.getInstance()

        for (i in hasLinks.indices) {
            tv = findViewById<View?>(hasLinks[i]) as TextView
            tv.movementMethod = linkMovement
            tv.autoLinkMask = Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES
        }
    }

    private fun setWindowSubtitle(subtitle: String?) {
        if (res!!.getBoolean(R.bool.long_activity_names)) setTitle(res!!.getString(R.string.app_full_name) + " - " + subtitle)
        else setTitle(subtitle)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }

            else -> return super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
    }
}
