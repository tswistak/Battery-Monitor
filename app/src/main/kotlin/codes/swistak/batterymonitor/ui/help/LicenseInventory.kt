/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.ui.help

import android.content.res.AssetManager
import org.json.JSONArray
import java.io.IOException

internal data class LicenseComponent(
    val id: String,
    val name: String,
    val version: String,
    val group: String,
    val license: String,
    val attribution: String,
    val sources: List<String>,
    val files: List<String>
)

internal fun loadLicenseInventory(assets: AssetManager): List<LicenseComponent> =
    listOf("application-components.json", "runtime-components.json").flatMap { filename ->
        val inventory = assets.open("licenses/$filename").bufferedReader(Charsets.UTF_8).use {
            JSONArray(it.readText())
        }
        List(inventory.length()) { index ->
            val component = inventory.getJSONObject(index)
            LicenseComponent(
                id = component.getString("id"),
                name = component.getString("name"),
                version = component.getString("version"),
                group = component.getString("group"),
                license = component.getString("license"),
                attribution = component.getString("attribution"),
                sources = component.getJSONArray("sources").stringValues(),
                files = component.getJSONArray("files").stringValues()
            )
        }
    }

internal fun readLicenseText(assets: AssetManager, filename: String): String? {
    if (!filename.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) return null
    return try {
        assets.open("licenses/$filename").bufferedReader(Charsets.UTF_8).use {
            it.readText().takeIf(String::isNotBlank)
        }
    } catch (_: IOException) {
        null
    }
}

private fun JSONArray.stringValues(): List<String> = List(length()) { getString(it) }
