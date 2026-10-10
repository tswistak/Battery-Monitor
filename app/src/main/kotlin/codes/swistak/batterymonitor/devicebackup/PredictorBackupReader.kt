/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.devicebackup

import codes.swistak.batterymonitor.monitoring.Predictor
import codes.swistak.batterymonitor.monitoring.PredictorStoredState
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

internal object PredictorBackupReader {
    fun state(
        snapshot: PredictorStoredState?, persisted: () -> PredictorStoredState
    ): PredictorStoredState =
        snapshot?.let { it.copy(averages = it.averages.toMap()) } ?: persisted()

    fun read(dataDirectory: File): PredictorStoredState {
        val file = File(dataDirectory, "shared_prefs/${Predictor.STORE_NAME}.xml")
        val backup = File(file.path + ".bak")
        var failure: Exception? = null
        repeat(3) {
            // SharedPreferences keeps the previous committed file while its new file is written.
            val source = if (backup.exists()) backup else file
            if (!source.exists()) {
                if (!file.exists() && !backup.exists()) return PredictorStoredState(
                    emptyMap(), null
                )
            } else {
                try {
                    require(source.length() <= 64 * 1024) { "Predictor store is too large" }
                    return source.reader(Charsets.UTF_8).use(::parse)
                } catch (error: Exception) {
                    failure = error
                }
            }
        }
        throw IOException("Could not read the saved predictor state", failure)
    }

    internal fun parse(reader: Reader): PredictorStoredState {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        val root = builder.parse(InputSource(reader)).documentElement
        require(root.tagName == "map") { "Invalid predictor store" }
        val values = buildMap<String, Any> {
            for (index in 0 until root.childNodes.length) {
                val entry = root.childNodes.item(index) as? Element ?: continue
                val key = entry.getAttribute("name")
                if (key in Predictor.KEY_AVERAGE) {
                    require(entry.tagName == "float") { "Invalid predictor value" }
                    val value = entry.getAttribute("value").toFloat()
                    require(value.isFinite()) { "Invalid predictor value" }
                    put(key, value)
                } else if (key == Predictor.KEY_STATE_VERSION) {
                    require(entry.tagName == "int") { "Invalid predictor version" }
                    put(key, entry.getAttribute("value").toInt())
                }
            }
        }
        return Predictor.readStoredState(values)
    }
}
