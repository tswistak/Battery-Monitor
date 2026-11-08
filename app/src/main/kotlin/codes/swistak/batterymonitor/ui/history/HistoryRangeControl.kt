/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.logs.HistoryRangeState
import codes.swistak.batterymonitor.ui.components.applicationDialogsAllowed
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal fun historyDatePickerMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun historyDatePickerDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

internal fun historyDraftRange(
    mode: String, first: String, last: String, month: String, year: String, zone: ZoneId
): HistoryRangeState? = runCatching {
    when (mode) {
        "month" -> {
            val selected =
                YearMonth.of(year.toInt().also { require(it in 1970..9999) }, month.toInt())
            HistoryRangeState.days(selected.atDay(1), selected.atEndOfMonth(), zone)
        }

        "year" -> {
            val selected = year.toInt().also { require(it in 1970..9999) }
            HistoryRangeState.days(
                LocalDate.of(selected, 1, 1), LocalDate.of(selected, 12, 31), zone
            )
        }

        else -> HistoryRangeState.days(LocalDate.parse(first), LocalDate.parse(last), zone)
    }
}.getOrNull()

@Composable
internal fun HistoryRangeControl(
    state: HistoryUiState,
    onRange: (HistoryRangeState, String) -> Unit,
    zone: ZoneId,
    compact: Boolean = false
) {
    val locale = LocalConfiguration.current.locales[0]
    val windowHeight = LocalWindowInfo.current.containerDpSize.height
    val dialogsAllowed = applicationDialogsAllowed()
    val format = remember(locale, zone) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(zone)
    }
    val dateFormat = remember(locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    }
    var selection by rememberSaveable { mutableStateOf(state.rangeLabel) }
    var mode by rememberSaveable { mutableStateOf<String?>(null) }
    var first by rememberSaveable {
        mutableStateOf(
            Instant.ofEpochMilli(state.range.start).atZone(zone).toLocalDate().toString()
        )
    }
    var last by rememberSaveable {
        mutableStateOf(
            Instant.ofEpochMilli(state.range.end - 1).atZone(zone).toLocalDate().toString()
        )
    }
    var month by rememberSaveable { mutableStateOf(LocalDate.now(zone).monthValue.toString()) }
    var year by rememberSaveable { mutableStateOf(LocalDate.now(zone).year.toString()) }
    val title = when (state.rangeLabel) {
        "1h" -> stringResource(R.string.history_chart_1h)
        "6h" -> stringResource(R.string.history_chart_6h)
        "24h" -> stringResource(R.string.history_last_24h)
        "72h" -> stringResource(R.string.history_last_3d)
        "7d" -> stringResource(R.string.history_last_7d)
        "all" -> stringResource(R.string.history_all_history)
        else -> stringResource(R.string.history_range)
    }
    Surface(
        onClick = {
            selection = state.rangeLabel
            val start = Instant.ofEpochMilli(state.range.start).atZone(zone).toLocalDate()
            first = start.toString()
            last = Instant.ofEpochMilli(state.range.end - 1).atZone(zone).toLocalDate().toString()
            month = start.monthValue.toString()
            year = start.year.toString()
            mode = "choose"
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .padding(
                    horizontal = if (compact) 8.dp else 15.dp,
                    vertical = if (compact) 4.dp else 12.dp
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painterResource(R.drawable.ui_calendar),
                null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    maxLines = if (compact) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis
                )
                if (!compact && state.rangeLabel !in setOf("24h", "72h", "7d")) Text(
                    "${format.format(Instant.ofEpochMilli(state.range.start))} – ${
                        format.format(
                            Instant.ofEpochMilli(state.range.end - 1)
                        )
                    }",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!compact) Text(
                stringResource(R.string.history_change_range),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                painterResource(R.drawable.ui_chev),
                null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
    if (mode == "choose" && dialogsAllowed) {
        var handled by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = {
            if (!handled && mode == "choose") {
                handled = true
                mode = null
            }
        }, title = { Text(stringResource(R.string.history_range)) }, text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .selectableGroup()
            ) {
                val options = buildList {
                    if (compact) {
                        add("1h" to R.string.history_chart_1h)
                        add("6h" to R.string.history_chart_6h)
                    }
                    add("24h" to R.string.history_last_24h)
                    add("7d" to R.string.history_last_7d)
                    add("all" to R.string.history_all_history)
                    add("custom" to R.string.history_custom_range)
                    add("month" to R.string.history_month)
                    add("year" to R.string.history_year)
                }
                options.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selection == value, role = Role.RadioButton) {
                                selection = value
                            }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selection == value, onClick = null)
                        Text(stringResource(label), Modifier.weight(1f))
                    }
                }
            }
        }, confirmButton = {
            TextButton(onClick = {
                if (!handled && mode == "choose") {
                    handled = true
                    when (selection) {
                        "custom", "month", "year" -> mode = selection
                        else -> {
                            val range = when (selection) {
                                "1h" -> HistoryRangeState.lastHours(1)
                                "6h" -> HistoryRangeState.lastHours(6)
                                "24h" -> HistoryRangeState.lastHours(24)
                                "7d" -> HistoryRangeState.lastHours(168)
                                else -> state.range
                            }
                            mode = null
                            onRange(range, selection)
                        }
                    }
                }
            }) { Text(stringResource(R.string.okay)) }
        }, dismissButton = {
            TextButton(onClick = {
                if (!handled && mode == "choose") {
                    handled = true
                    mode = null
                }
            }) { Text(stringResource(R.string.cancel)) }
        })
    }
    if (mode == "startDate" || mode == "endDate") {
        val pickerMode = mode
        var handled by remember(pickerMode) { mutableStateOf(false) }
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = historyDatePickerMillis(
                LocalDate.parse(if (pickerMode == "startDate") first else last).coerceIn(
                    LocalDate.of(1900, 1, 1), LocalDate.of(9999, 12, 31)
                )
            ), yearRange = 1900..9999, initialDisplayMode = if (windowHeight < 480.dp) {
                DisplayMode.Input
            } else {
                DisplayMode.Picker
            }
        )
        if (dialogsAllowed) DatePickerDialog(onDismissRequest = {
            if (!handled && mode == pickerMode) {
                handled = true
                mode = "custom"
            }
        }, modifier = Modifier.imePadding(), confirmButton = {
            TextButton(
                onClick = {
                    if (!handled && mode == pickerMode) pickerState.selectedDateMillis?.let {
                        handled = true
                        val selected = historyDatePickerDate(it).toString()
                        if (pickerMode == "startDate") first = selected else last = selected
                        mode = "custom"
                    }
                }, enabled = pickerState.selectedDateMillis != null
            ) { Text(stringResource(R.string.okay)) }
        }, dismissButton = {
            TextButton(onClick = {
                if (!handled && mode == pickerMode) {
                    handled = true
                    mode = "custom"
                }
            }) {
                Text(stringResource(R.string.cancel))
            }
        }) {
            DatePicker(pickerState, modifier = Modifier.verticalScroll(rememberScrollState()))
        }
    }
    if (mode in setOf("custom", "month", "year")) {
        val editorMode = mode!!
        var handled by remember(editorMode) { mutableStateOf(false) }
        val range = historyDraftRange(editorMode, first, last, month, year, zone)
        if (dialogsAllowed) AlertDialog(
            onDismissRequest = {
                if (!handled && mode == editorMode) {
                    handled = true
                    mode = null
                }
            },
            modifier = Modifier.imePadding(),
            title = { Text(stringResource(R.string.history_range)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (editorMode == "custom") {
                        OutlinedButton(onClick = {
                            if (!handled && mode == editorMode) {
                                handled = true
                                mode = "startDate"
                            }
                        }) {
                            Text(
                                stringResource(
                                    R.string.history_from, dateFormat.format(LocalDate.parse(first))
                                )
                            )
                        }
                        OutlinedButton(onClick = {
                            if (!handled && mode == editorMode) {
                                handled = true
                                mode = "endDate"
                            }
                        }) {
                            Text(
                                stringResource(
                                    R.string.history_through,
                                    dateFormat.format(LocalDate.parse(last))
                                )
                            )
                        }
                        Text(
                            stringResource(R.string.history_calendar_days),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        OutlinedTextField(
                            year,
                            { year = it },
                            label = { Text(stringResource(R.string.history_year)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        if (editorMode == "month") OutlinedTextField(
                            month,
                            { month = it },
                            label = { Text(stringResource(R.string.history_month)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }
                    if (range == null) Text(
                        stringResource(R.string.history_invalid_range),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!handled && mode == editorMode && range != null) {
                        handled = true
                        mode = null
                        onRange(range, editorMode)
                    }
                }, enabled = range != null) { Text(stringResource(R.string.okay)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (!handled && mode == editorMode) {
                        handled = true
                        mode = null
                    }
                }) { Text(stringResource(R.string.cancel)) }
            })
    }
}
