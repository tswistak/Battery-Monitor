/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.help

import android.content.res.Resources
import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.text.style.URLSpan
import android.text.util.Linkify
import androidx.annotation.LayoutRes
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
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
import org.xmlpull.v1.XmlPullParser

@LayoutRes
internal fun helpLayout(topic: String?): Int = when (topic) {
    null -> R.layout.help
    SettingsContract.KEY_NOTIFICATION_SETTINGS -> R.layout.notification_settings_help
    SettingsContract.KEY_CURRENT_STATE_SETTINGS -> R.layout.current_state_settings_help
    SettingsContract.KEY_OTHER_SETTINGS -> R.layout.other_settings_help
    SettingsContract.KEY_TIME_ESTIMATES_SETTINGS -> R.layout.time_estimates_settings_help
    SettingsContract.KEY_ADVANCED_SETTINGS -> R.layout.advanced_settings_help
    SettingsContract.KEY_BACKUP_RESTORE_SETTINGS -> R.layout.backup_restore_settings_help
    SettingsContract.KEY_DIAGNOSTICS_SETTINGS -> R.layout.diagnostics_settings_help
    SettingsContract.KEY_ADVANCED_INFO_HELP -> R.layout.advanced_info_help
    SettingsContract.KEY_ALARMS_SETTINGS -> R.layout.alarm_settings_help
    SettingsContract.KEY_ALARM_EDIT_SETTINGS -> R.layout.alarm_edit_help
    else -> R.layout.main_settings_help
}

private data class HelpParagraph(val text: CharSequence, val subheading: Boolean)
private data class HelpSection(
    val title: CharSequence?, val titleResource: Int, val paragraphs: List<HelpParagraph>
)

private fun readHelpSections(
    resources: Resources, @LayoutRes layout: Int, version: CharSequence
): List<HelpSection> {
    val sections = mutableListOf<HelpSection>()
    var paragraphs = mutableListOf<HelpParagraph>()
    resources.getLayout(layout).use { parser ->
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG || parser.name != "TextView") continue
            val namespace = "http://schemas.android.com/apk/res/android"
            val textResource = parser.getAttributeResourceValue(namespace, "text", 0)
            val text = when {
                textResource != 0 -> resources.getText(textResource)
                parser.getAttributeResourceValue(namespace, "id", 0) == R.id.version -> version
                else -> continue
            }
            when (parser.styleAttribute) {
                R.style.help_cat -> {
                    paragraphs = mutableListOf()
                    sections.add(HelpSection(text, textResource, paragraphs))
                }

                else -> {
                    if (sections.isEmpty()) sections.add(HelpSection(null, 0, paragraphs))
                    paragraphs.add(HelpParagraph(text, parser.styleAttribute == R.style.help_pref))
                }
            }
        }
    }
    return sections
}

@Composable
fun HelpScreen(topic: String? = null) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val sections = remember(topic, resources, configuration) {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "…"
        readHelpSections(
            resources, helpLayout(topic), resources.getString(
                R.string.nav_help_version, resources.getString(R.string.app_full_name), version
            )
        )
    }
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
                    if (section.title != null) Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painterResource(
                                if (section.titleResource == R.string.help_cat_privacy) R.drawable.ui_shield else R.drawable.ui_help
                            ), null, Modifier.size(23.dp), tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            helpText(section.title),
                            Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            for ((text, subheading) in section.paragraphs) Text(
                                helpText(text),
                                Modifier.then(if (subheading) Modifier.semantics { heading() }
                                else Modifier),
                                style = if (subheading) MaterialTheme.typography.titleSmall
                                else MaterialTheme.typography.bodyMedium,
                                color = if (subheading) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
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
