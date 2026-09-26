/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import codes.swistak.batterymonitor.ui.theme.BatterySpacing
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.NumberFormat
import java.text.ParsePosition
import java.util.Locale

enum class NumericInputError { Empty, Invalid, OutOfRange, WrongStep }

sealed interface NumericInputResult {
    data class Valid(val value: Int) : NumericInputResult
    data class Error(val reason: NumericInputError) : NumericInputResult
}

fun parseLocalizedInt(
    input: String, locale: Locale, min: Int, max: Int, step: Int
): NumericInputResult {
    require(min <= max && step > 0)
    val text = input.trim()
    if (text.isEmpty()) return NumericInputResult.Error(NumericInputError.Empty)
    val parser = NumberFormat.getNumberInstance(locale) as DecimalFormat
    parser.isParseBigDecimal = true
    parser.isGroupingUsed = false
    if (text.contains(parser.decimalFormatSymbols.decimalSeparator)) {
        return NumericInputResult.Error(NumericInputError.Invalid)
    }
    val position = ParsePosition(0)
    val number = parser.parse(text, position) as? BigDecimal ?: return NumericInputResult.Error(
        NumericInputError.Invalid
    )
    if (position.index != text.length) return NumericInputResult.Error(NumericInputError.Invalid)
    val value = try {
        number.intValueExact()
    } catch (_: ArithmeticException) {
        return NumericInputResult.Error(NumericInputError.Invalid)
    }
    if (value !in min..max) return NumericInputResult.Error(NumericInputError.OutOfRange)
    if ((value.toLong() - min) % step != 0L) {
        return NumericInputResult.Error(NumericInputError.WrongStep)
    }
    return NumericInputResult.Valid(value)
}

@Composable
fun NumericValueEditor(
    label: String,
    unit: String,
    initialValue: Int,
    min: Int,
    max: Int,
    step: Int,
    saveLabel: String,
    cancelLabel: String,
    errorMessage: (NumericInputError) -> String,
    onSave: (Int) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = false }
    var draft by rememberSaveable(initialValue, locale.toLanguageTag()) {
        mutableStateOf(formatter.format(initialValue))
    }
    val result = parseLocalizedInt(draft, locale, min, max, step)
    val valid = (result as? NumericInputResult.Valid)?.value

    Column(modifier, verticalArrangement = Arrangement.spacedBy(BatterySpacing.sm)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(label) },
            suffix = { Text(unit) },
            supportingText = {
                if (result is NumericInputResult.Error) Text(errorMessage(result.reason))
            },
            isError = result is NumericInputResult.Error,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { valid?.let(onSave) }),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(BatterySpacing.sm)) {
            OutlinedButton(
                onClick = {
                    val base = valid ?: initialValue
                    draft =
                        formatter.format(if (base.toLong() - step >= min) base.toLong() - step else base)
                }, modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("−") }
            OutlinedButton(
                onClick = {
                    val base = valid ?: initialValue
                    draft =
                        formatter.format(if (base.toLong() + step <= max) base.toLong() + step else base)
                }, modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("+") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(BatterySpacing.sm)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(cancelLabel)
            }
            Button(
                onClick = { valid?.let(onSave) },
                enabled = valid != null,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(saveLabel)
            }
        }
    }
}
