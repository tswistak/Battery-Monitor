/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import codes.swistak.batterymonitor.settings.SettingsContract

internal enum class StartupSystemAction { NOTIFICATIONS, LIVE_UPDATE_SETTINGS, BATTERY_SETTINGS }

internal class StartupDialogViewModel(
    application: Application, private val savedState: SavedStateHandle
) : AndroidViewModel(application) {
    companion object {
        const val KEY_ACKNOWLEDGED_VERSION = "last_acknowledged_version_code"
    }

    private val preferences = application.getSharedPreferences(
        SettingsContract.SP_MAIN_FILE, Context.MODE_PRIVATE
    )

    private val installed = application.packageManager.getPackageInfo(application.packageName, 0)

    val versionCode = PackageInfoCompat.getLongVersionCode(installed)
    val versionName: String = installed.versionName.orEmpty()

    var presentation by mutableStateOf(
        StartupPresentation.valueOf(
            savedState["startup_presentation"] ?: StartupPresentation.NONE.name
        )
    )
        private set
    var systemAction by mutableStateOf(
        savedState.get<String>("startup_action")?.let(StartupSystemAction::valueOf)
    )
        private set
    private var waitingForSystemState by mutableStateOf(savedState["startup_waiting"] ?: false)
    private var waitingForSystem: Boolean
        get() = waitingForSystemState
        set(value) {
            waitingForSystemState = value
            savedState["startup_waiting"] = value
        }
    private var systemLostFocus: Boolean
        get() = savedState["startup_system_lost_focus"] ?: false
        set(value) {
            savedState["startup_system_lost_focus"] = value
        }
    private var notificationAttempted: Boolean
        get() = savedState["startup_notification_attempted"] ?: false
        set(value) {
            savedState["startup_notification_attempted"] = value
        }
    private var batteryPromptShown: Boolean
        get() = savedState["startup_battery_prompt_shown"] ?: false
        set(value) {
            savedState["startup_battery_prompt_shown"] = value
        }

    val blocksContentDialogs: Boolean
        get() = presentation != StartupPresentation.NONE || systemAction != null || waitingForSystem

    init {
        // Capture migration state before onboarding or monitoring initialization changes preferences.
        val decision = changelogVersionDecision(
            versionCode,
            acknowledgedVersion(),
            preferences.getBoolean(SettingsContract.KEY_FIRST_RUN, true),
            installed.firstInstallTime,
            installed.lastUpdateTime
        )
        decision.baselineVersion?.let {
            preferences.edit(commit = true) { putLong(KEY_ACKNOWLEDGED_VERSION, it) }
        }
        if ((!decision.pending && presentation == StartupPresentation.CHANGELOG) || (decision.pending && presentation != StartupPresentation.CHANGELOG)) {
            show(StartupPresentation.NONE)
        }
    }

    fun onWindowFocusChanged(hasFocus: Boolean, environment: StartupEnvironment) {
        if (!hasFocus) {
            if (waitingForSystem && systemAction == null) systemLostFocus = true
            return
        }
        if (waitingForSystem && systemLostFocus && systemAction == null) systemReturned()
        advance(environment)
    }

    fun onLeavingActivity() {
        if (waitingForSystem && systemAction == null) systemLostFocus = true
    }

    fun advance(environment: StartupEnvironment) {
        if (blocksContentDialogs) return
        if (!environment.notificationPermissionRequired || environment.notificationsGranted) {
            finishFirstRun()
        }
        val next =
            nextStartupPresentation(
                StartupProgress(
                    firstRun = preferences.getBoolean(SettingsContract.KEY_FIRST_RUN, true),
                    changelogPending = acknowledgedVersion()?.let { versionCode > it } ?: true,
                    notificationAttempted = notificationAttempted,
                    batteryPromptShown = batteryPromptShown), environment)
        when (next) {
            StartupPresentation.REQUEST_NOTIFICATIONS -> requestNotifications()
            StartupPresentation.BATTERY_OPTIMIZATION -> {
                batteryPromptShown = true
                show(next)
            }

            else -> show(next)
        }
    }

    fun acknowledgeChangelog(): Boolean {
        val saved = preferences.edit().putLong(
            KEY_ACKNOWLEDGED_VERSION, acknowledgeVersion(versionCode, acknowledgedVersion())
        ).commit()
        if (saved) show(StartupPresentation.NONE)
        return saved
    }

    fun requestNotifications() {
        notificationAttempted = true
        finishFirstRun()
        launch(StartupSystemAction.NOTIFICATIONS)
    }

    fun openLiveUpdateSettings() {
        finishFirstRun()
        launch(StartupSystemAction.LIVE_UPDATE_SETTINGS)
    }

    fun cancelOnboarding() {
        notificationAttempted = true
        finishFirstRun()
        show(StartupPresentation.NONE)
    }

    fun dismissBatteryPrompt() = show(StartupPresentation.NONE)

    fun openBatterySettings() = launch(StartupSystemAction.BATTERY_SETTINGS)

    fun takeSystemAction(): StartupSystemAction? {
        val action = systemAction ?: return null
        systemAction = null
        savedState["startup_action"] = null
        return action
    }

    fun systemReturned() {
        waitingForSystem = false
        systemLostFocus = false
    }

    private fun launch(action: StartupSystemAction) {
        show(StartupPresentation.NONE)
        waitingForSystem = true
        systemLostFocus = false
        systemAction = action
        savedState["startup_action"] = action.name
    }

    private fun finishFirstRun() {
        if (preferences.getBoolean(SettingsContract.KEY_FIRST_RUN, true)) {
            preferences.edit { putBoolean(SettingsContract.KEY_FIRST_RUN, false) }
        }
    }

    private fun acknowledgedVersion(): Long? = if (preferences.contains(KEY_ACKNOWLEDGED_VERSION)) {
        preferences.getLong(KEY_ACKNOWLEDGED_VERSION, 0)
    } else null

    private fun show(value: StartupPresentation) {
        presentation = value
        savedState["startup_presentation"] = value.name
    }
}
