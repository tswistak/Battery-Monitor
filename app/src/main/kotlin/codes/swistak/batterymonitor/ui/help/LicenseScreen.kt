/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.help

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import codes.swistak.batterymonitor.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LicenseScreen(componentId: String? = null, onOpenComponent: (String) -> Unit) {
    val assets = LocalContext.current.assets
    val inventory by produceState<Result<List<LicenseComponent>>?>(null, assets) {
        value = withContext(Dispatchers.IO) { runCatching { loadLicenseInventory(assets) } }
    }
    val components = inventory?.getOrNull()
    if (components == null) {
        Column(Modifier.padding(20.dp)) {
            LicenseCard {
                LicenseBody(
                    stringResource(
                        if (inventory == null) R.string.help_licenses_loading
                        else R.string.help_licenses_unavailable
                    )
                )
            }
        }
        return
    }
    if (componentId != null) {
        val component = components.firstOrNull { it.id == componentId }
        LicenseDetail(component)
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        for ((group, entries) in components.groupBy { it.group }) {
            item(key = "group:$group") {
                HelpHeading(
                    stringResource(
                        when (group) {
                            "application" -> R.string.help_licenses_application
                            "artwork" -> R.string.help_licenses_artwork
                            else -> R.string.help_licenses_libraries
                        }
                    ), R.string.help_cat_open_source
                )
            }
            items(entries, key = { it.id }) { component ->
                OutlinedCard(
                    onClick = { onOpenComponent(component.id) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                component.name,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Icon(
                                painterResource(R.drawable.ui_forward),
                                null,
                                Modifier
                                    .padding(start = 12.dp)
                                    .size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        LicenseBody(
                            listOf(
                                component.version, component.license
                            ).filter { it.isNotEmpty() }.joinToString(" · ")
                        )
                        LicenseBody(component.attribution)
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenseDetail(component: LicenseComponent?) {
    val assets = LocalContext.current.assets
    val texts by produceState<Map<String, String?>?>(null, assets, component) {
        value = withContext(Dispatchers.IO) {
            component?.files?.associateWith { readLicenseText(assets, it) } ?: emptyMap()
        }
    }
    if (component != null && texts == null) {
        Column(Modifier.padding(20.dp)) {
            LicenseCard { LicenseBody(stringResource(R.string.help_licenses_loading)) }
        }
        return
    }
    SelectionContainer {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (component == null) {
                LicenseCard { LicenseBody(stringResource(R.string.help_licenses_unavailable)) }
            } else {
                LicenseCard {
                    HelpHeading(component.name, R.string.help_cat_open_source)
                    LicenseBody(
                        listOf(
                            component.version, component.license
                        ).filter { it.isNotEmpty() }.joinToString(" · ")
                    )
                    LicenseBody(component.attribution)
                    for (source in component.sources) {
                        Text(
                            helpText(source),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                for (filename in component.files) {
                    LicenseCard {
                        Text(
                            filename.removeSuffix(".txt"),
                            Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        LicenseBody(
                            texts?.get(filename)
                                ?: stringResource(R.string.help_licenses_unavailable)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenseCard(content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun LicenseBody(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
