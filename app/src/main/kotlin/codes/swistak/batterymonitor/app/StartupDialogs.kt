/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.common.showToast
import codes.swistak.batterymonitor.monitoring.BatteryInfoService

@Composable
internal fun StartupDialogs(controller: StartupDialogViewModel, onContinue: () -> Unit) {
    val context = LocalContext.current
    when (controller.presentation) {
        StartupPresentation.CHANGELOG -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false, dismissOnClickOutside = false
            ),
            title = {
                Text(
                    stringResource(R.string.changelog_title), Modifier.semantics { heading() })
            },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        stringResource(R.string.changelog_version, controller.versionName),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(stringResource(R.string.latest_release_changelog))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (controller.acknowledgeChangelog()) onContinue()
                    else context.showToast(R.string.monitoring_state_save_failed)
                }) { Text(stringResource(R.string.changelog_got_it)) }
            })

        StartupPresentation.ONBOARDING -> {
            val onboarding = notificationOnboarding(
                BatteryInfoService.supportsLiveUpdates(),
                BatteryInfoService.isLiveUpdateEnabledInSystem(context)
            )
            AlertDialog(
                modifier = Modifier.imePadding(),
                onDismissRequest = {},
                properties = DialogProperties(
                    dismissOnBackPress = false, dismissOnClickOutside = false
                ),
                title = {
                    Text(
                        stringResource(
                            if (onboarding == NotificationOnboarding.LEGACY) R.string.app_full_name
                            else R.string.live_updates_onboarding_title
                        ), Modifier.semantics { heading() })
                },
                text = {
                    Text(
                        stringResource(
                            when (onboarding) {
                                NotificationOnboarding.LEGACY -> R.string.notifications_onboarding_message
                                NotificationOnboarding.LIVE_UPDATES_ON -> R.string.live_updates_onboarding_message_on
                                NotificationOnboarding.LIVE_UPDATES_OFF -> R.string.live_updates_onboarding_message_off
                            }
                        ), Modifier.verticalScroll(rememberScrollState())
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (onboarding == NotificationOnboarding.LIVE_UPDATES_OFF) controller.openLiveUpdateSettings()
                        else controller.requestNotifications()
                    }) {
                        Text(
                            stringResource(
                                when (onboarding) {
                                    NotificationOnboarding.LEGACY -> android.R.string.ok
                                    NotificationOnboarding.LIVE_UPDATES_ON -> R.string.live_updates_onboarding_positive_on
                                    NotificationOnboarding.LIVE_UPDATES_OFF -> R.string.live_updates_onboarding_positive_off
                                }
                            )
                        )
                    }
                },
                dismissButton = {
                    if (onboarding != NotificationOnboarding.LIVE_UPDATES_ON) TextButton(onClick = {
                        if (onboarding == NotificationOnboarding.LIVE_UPDATES_OFF) controller.requestNotifications()
                        else {
                            controller.cancelOnboarding()
                            onContinue()
                        }
                    }) {
                        Text(
                            stringResource(
                                if (onboarding == NotificationOnboarding.LEGACY) R.string.cancel
                                else R.string.live_updates_onboarding_negative_off
                            )
                        )
                    }
                })
        }

        StartupPresentation.BATTERY_OPTIMIZATION -> AlertDialog(onDismissRequest = {
            controller.dismissBatteryPrompt()
            onContinue()
        }, title = {
            Text(
                stringResource(R.string.battery_optimized), Modifier.semantics { heading() })
        }, text = {
            Text(
                stringResource(R.string.battery_optimized_message),
                Modifier.verticalScroll(rememberScrollState())
            )
        }, confirmButton = {
            TextButton(onClick = controller::openBatterySettings) { Text(stringResource(android.R.string.ok)) }
        }, dismissButton = {
            TextButton(onClick = {
                controller.dismissBatteryPrompt()
                onContinue()
            }) { Text(stringResource(R.string.cancel)) }
        })

        else -> Unit
    }
}
