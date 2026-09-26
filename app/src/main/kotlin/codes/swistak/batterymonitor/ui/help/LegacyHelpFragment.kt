/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.help

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import codes.swistak.batterymonitor.R

class LegacyHelpFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.help, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        for (id in intArrayOf(
            R.id.open_source, R.id.acknowledgments, R.id.frequently_asked_questions, R.id.contact
        )) {
            view.findViewById<TextView>(id).apply {
                movementMethod = LinkMovementMethod.getInstance()
                autoLinkMask = Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES
            }
        }
        val version = try {
            requireContext().packageManager.getPackageInfo(
                requireContext().packageName, 0
            ).versionName
        } catch (_: Exception) {
            "…"
        }
        view.findViewById<TextView>(R.id.version).text = getString(
            R.string.nav_help_version, getString(R.string.app_full_name), version
        )
    }
}
