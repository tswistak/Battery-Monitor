/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.alarms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.alarms.AlarmConfiguration
import codes.swistak.batterymonitor.alarms.AlarmPlayback
import codes.swistak.batterymonitor.alarms.AlarmSoundSettings
import codes.swistak.batterymonitor.alarms.AlarmVolumeCategory
import codes.swistak.batterymonitor.ui.components.ActionLabel
import codes.swistak.batterymonitor.ui.components.applicationDialogsAllowed

@Composable
internal fun AlarmSettingsDialog(
    subject: String,
    initialSound: AlarmSoundSettings,
    stored: AlarmConfiguration?,
    creating: Boolean,
    enabled: Boolean,
    busy: Boolean,
    readError: String?,
    error: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onChannelSettings: () -> Unit,
    onSave: (AlarmConfiguration?, AlarmSoundSettings, Boolean) -> Unit
) {
    val originalPlayback = rememberSaveable { mutableStateOf(stored?.sound?.playback) }.value
    val originalVolume = rememberSaveable { mutableStateOf(stored?.sound?.volumeCategory) }.value
    val originalChannel = rememberSaveable { mutableStateOf(stored?.channelId) }.value
    val expected = if (originalPlayback != null && originalVolume != null) {
        AlarmConfiguration(AlarmSoundSettings(originalPlayback, originalVolume), originalChannel)
    } else null
    var playback by rememberSaveable { mutableStateOf(initialSound.playback) }
    var volume by rememberSaveable { mutableStateOf(initialSound.volumeCategory) }
    var confirmReplacement by rememberSaveable { mutableStateOf(false) }
    if (!applicationDialogsAllowed()) return

    val stale = expected != stored
    val canSave = enabled && !stale
    val problem = if (stale) stringResource(R.string.alarms_conflict) else readError ?: error
    if (confirmReplacement) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmReplacement = false },
            title = { Text(stringResource(R.string.alarms_replace_channel_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.alarms_replace_channel_message))
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onSave(expected, AlarmSoundSettings(playback, volume), true) },
                    enabled = canSave
                ) { Text(stringResource(R.string.charging_diagnostics_save)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReplacement = false }, enabled = !busy) {
                    Text(stringResource(R.string.cancel))
                }
            })
    } else AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.alarm_settings_subtitle)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(subject, style = MaterialTheme.typography.titleSmall)
                AlarmSettingsChoices(
                    label = R.string.alarms_playback, options = listOf(
                        AlarmPlayback.ONCE to R.string.alarms_playback_once,
                        AlarmPlayback.REPEAT to R.string.alarms_playback_repeat
                    ), selected = playback, enabled = canSave, onSelect = { playback = it })
                if (playback == AlarmPlayback.REPEAT) Text(
                    stringResource(R.string.alarms_playback_help),
                    style = MaterialTheme.typography.bodySmall
                )
                AlarmSettingsChoices(
                    label = R.string.alarms_volume_category, options = listOf(
                        AlarmVolumeCategory.NOTIFICATIONS to R.string.alarms_volume_notifications,
                        AlarmVolumeCategory.ALARMS to R.string.alarms_volume_alarms,
                        AlarmVolumeCategory.MEDIA to R.string.alarms_volume_media
                    ), selected = volume, enabled = canSave, onSelect = { volume = it })
                Text(
                    stringResource(R.string.alarms_volume_help),
                    style = MaterialTheme.typography.bodySmall
                )
                if (!creating) TextButton(onClick = onChannelSettings, enabled = !busy) {
                    ActionLabel(
                        stringResource(R.string.alarms_android_channel_settings),
                        R.drawable.ui_external
                    )
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (readError != null) TextButton(onClick = onRetry, enabled = !busy) {
                    Text(stringResource(R.string.diag_action_refresh))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val sound = AlarmSoundSettings(playback, volume)
                    val currentVolume = originalVolume ?: AlarmVolumeCategory.NOTIFICATIONS
                    val replacesChannel = !creating && sound.volumeCategory != currentVolume
                    if (replacesChannel) confirmReplacement = true
                    else onSave(expected, sound, false)
                }, enabled = canSave
            ) { Text(stringResource(R.string.charging_diagnostics_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.cancel))
            }
        })
}

@Composable
private fun <T> AlarmSettingsChoices(
    label: Int, options: List<Pair<T, Int>>, selected: T, enabled: Boolean, onSelect: (T) -> Unit
) {
    Column(Modifier.selectableGroup()) {
        Text(
            stringResource(label),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall
        )
        options.forEach { (value, labelResource) ->
            val checked = value == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = checked,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(value) }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RadioButton(checked, onClick = null, enabled = enabled)
                Text(stringResource(labelResource))
            }
        }
    }
}
