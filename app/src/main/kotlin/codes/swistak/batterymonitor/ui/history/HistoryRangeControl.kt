/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.history

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.logs.HistoryRangeState
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun HistoryRangeControl(
    state: HistoryUiState, onRange: (HistoryRangeState, String) -> Unit
) {
    val locale = LocalConfiguration.current.locales[0]
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val format = remember(locale, zone) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(zone)
    }
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
    var month by rememberSaveable { mutableStateOf(LocalDate.now().monthValue.toString()) }
    var year by rememberSaveable { mutableStateOf(LocalDate.now().year.toString()) }
    val title = when (state.rangeLabel) {
        "24h" -> stringResource(R.string.history_last_24h)
        "7d" -> stringResource(R.string.history_last_7d)
        else -> stringResource(R.string.history_range)
    }
    OutlinedButton(onClick = { mode = "choose" }, modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(title)
            Text(
                "${format.format(Instant.ofEpochMilli(state.range.start))} – ${
                    format.format(
                        Instant.ofEpochMilli(
                            state.range.end - 1
                        )
                    )
                }", style = MaterialTheme.typography.labelSmall
            )
        }
    }
    if (mode == "choose") AlertDialog(
        onDismissRequest = { mode = null },
        title = { Text(stringResource(R.string.history_range)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton(onClick = {
                    onRange(HistoryRangeState.lastHours(24), "24h"); mode = null
                }) { Text(stringResource(R.string.history_last_24h)) }
                TextButton(onClick = {
                    onRange(HistoryRangeState.lastHours(168), "7d"); mode = null
                }) { Text(stringResource(R.string.history_last_7d)) }
                TextButton(onClick = {
                    first = Instant.ofEpochMilli(state.range.start).atZone(zone).toLocalDate()
                        .toString(); last =
                    Instant.ofEpochMilli(state.range.end - 1).atZone(zone).toLocalDate()
                        .toString(); mode = "custom"
                }) { Text(stringResource(R.string.history_custom_range)) }
                TextButton(onClick = {
                    mode = "month"
                }) { Text(stringResource(R.string.history_month)) }
                TextButton(onClick = {
                    mode = "year"
                }) { Text(stringResource(R.string.history_year)) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = {
                mode = null
            }) { Text(stringResource(R.string.cancel)) }
        })
    if (mode != null && mode != "choose") {
        val range = runCatching {
            when (mode) {
                "month" -> {
                    val ym =
                        YearMonth.of(year.toInt().also { require(it in 1970..9999) }, month.toInt())
                    HistoryRangeState.days(ym.atDay(1), ym.atEndOfMonth(), zone)
                }

                "year" -> {
                    val selectedYear = year.toInt().also { require(it in 1970..9999) }
                    HistoryRangeState.days(
                        LocalDate.of(selectedYear, 1, 1), LocalDate.of(selectedYear, 12, 31), zone
                    )
                }

                else -> HistoryRangeState.days(LocalDate.parse(first), LocalDate.parse(last), zone)
            }
        }.getOrNull()

        fun pick(date: String, selected: (String) -> Unit) {
            val initial = LocalDate.parse(date)
            DatePickerDialog(
                context,
                { _, y, m, d -> selected(LocalDate.of(y, m + 1, d).toString()) },
                initial.year,
                initial.monthValue - 1,
                initial.dayOfMonth
            ).show()
        }
        AlertDialog(
            onDismissRequest = { mode = null },
            title = { Text(stringResource(R.string.history_range)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (mode == "custom") {
                        OutlinedButton(onClick = { pick(first) { first = it } }) {
                            Text(
                                stringResource(R.string.history_from, first)
                            )
                        }
                        OutlinedButton(onClick = {
                            pick(last) {
                                last = it
                            }
                        }) { Text(stringResource(R.string.history_through, last)) }
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
                        if (mode == "month") OutlinedTextField(
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
                    range?.let {
                        onRange(it, mode!!); mode = null
                    }
                }, enabled = range != null) { Text(stringResource(R.string.okay)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    mode = null
                }) { Text(stringResource(R.string.cancel)) }
            })
    }
}
