/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.help

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.text.style.URLSpan
import android.text.util.Linkify
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import codes.swistak.batterymonitor.R
import codes.swistak.batterymonitor.settings.SettingsContract
import codes.swistak.batterymonitor.ui.settings.SettingsCategory
import codes.swistak.batterymonitor.ui.settings.helpHeadingIcon

@Composable
fun HelpScreen(topic: String? = null) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val settings = remember(context) {
        context.getSharedPreferences(SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE)
    }
    var chargingTargetMode by remember(settings) {
        mutableStateOf(settings.getString(SettingsContract.KEY_CHARGING_TARGET_MODE, null))
    }
    DisposableEffect(settings) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
            if (key == null || key == SettingsContract.KEY_CHARGING_TARGET_MODE) {
                chargingTargetMode =
                    preferences.getString(SettingsContract.KEY_CHARGING_TARGET_MODE, null)
            }
        }
        settings.registerOnSharedPreferenceChangeListener(listener)
        onDispose { settings.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val sections = remember(topic, chargingTargetMode) {
        helpSections(topic, Build.VERSION.SDK_INT, chargingTargetMode)
    }
    val categoryHelp = SettingsCategory.fromLegacy(topic) != null
    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "…"
    }
    val versionText = stringResource(
        R.string.nav_help_version, stringResource(R.string.app_full_name), version
    )
    LazyColumn(
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(sections) { section ->
            OutlinedCard(
                Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    section.titleResource?.let { title ->
                        HelpHeading(resources.getText(title), title, showIcon = !categoryHelp)
                    }
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (paragraph in section.paragraphs) {
                                val text =
                                    if (paragraph.textResource == R.string.nav_help_version) {
                                        versionText
                                    } else resources.getText(paragraph.textResource)
                                if (paragraph.subheading) {
                                    HelpHeading(text, paragraph.textResource, small = true)
                                } else Text(
                                    helpText(text),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HelpHeading(
    text: CharSequence, textResource: Int, small: Boolean = false, showIcon: Boolean = true
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showIcon) Icon(
            painterResource(helpHeadingIcon(textResource) ?: R.drawable.ui_info),
            contentDescription = null,
            Modifier.size(if (small) 20.dp else 23.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            helpText(text),
            Modifier
                .weight(1f)
                .semantics { heading() },
            style = if (small) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun helpText(text: CharSequence): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(text, linkColor) {
        val linked = SpannableString(text)
        val autoLinked = SpannableString(text.toString())
        Linkify.addLinks(autoLinked, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES)
        for (link in autoLinked.getSpans(0, autoLinked.length, URLSpan::class.java)) {
            val start = autoLinked.getSpanStart(link)
            val end = autoLinked.getSpanEnd(link)
            if (linked.getSpans(start, end, URLSpan::class.java).isEmpty()) {
                linked.setSpan(link, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        AnnotatedString.fromHtml(
            Html.toHtml(linked, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE),
            linkStyles = TextLinkStyles(
                SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
            )
        ).let { value ->
            val end = value.text.indexOfLast { !it.isWhitespace() } + 1
            value.subSequence(0, end)
        }
    }
}
