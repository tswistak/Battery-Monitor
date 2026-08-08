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
package codes.swistak.batterymonitor.help

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.common.EdgeToEdgeHelper

class HelpActivity : AppCompatActivity() {
    companion object {
        private val HAS_LINKS = intArrayOf(
            R.id.open_source,
            R.id.acknowledgments,
            R.id.frequently_asked_questions,
            R.id.contact
        )
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ab = supportActionBar
        if (ab != null) {
            ab.setHomeButtonEnabled(true)
            ab.setDisplayHomeAsUpEnabled(true)
            ab.elevation = 0f
        }

        setContentView(R.layout.help)
        EdgeToEdgeHelper.applyIfNeeded(this)

        setTitle(getResources().getString(R.string.help_activity_subtitle))

        var tv: TextView
        val linkMovement = LinkMovementMethod.getInstance()

        for (i in HAS_LINKS.indices) {
            tv = findViewById<View?>(HAS_LINKS[i]) as TextView
            tv.movementMethod = linkMovement
            tv.autoLinkMask = Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES
        }

        tv = findViewById<View?>(R.id.version) as TextView
        try {
            tv.text = getResources().getString(R.string.app_full_name) + " " +
                    packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            tv.text = "..."
        }
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
