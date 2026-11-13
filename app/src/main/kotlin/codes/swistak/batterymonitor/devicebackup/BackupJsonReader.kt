/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor.devicebackup

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import android.util.MalformedJsonException
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

internal fun readBackupJson(context: Context, uri: Uri, maxBytes: Int): JSONObject {
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IOException("Could not open backup source")
    return readBackupJson(BackupImportInputStream(input, maxBytes))
}

internal fun writeBackupJson(context: Context, uri: Uri, json: JSONObject, maxBytes: Int) {
    val bytes = json.toString().toByteArray(Charsets.UTF_8)
    require(bytes.size <= maxBytes) { "Backup export is too large to restore" }
    readBackupJson(bytes.inputStream())
    val output = context.contentResolver.openOutputStream(uri, "wt")
        ?: throw IOException("Could not open backup destination")
    output.use { it.write(bytes) }
}

internal fun readBackupJson(input: InputStream): JSONObject = try {
    JsonReader(
        BoundedJsonTokens(
            InputStreamReader(
                input, Charsets.UTF_8.newDecoder()
            )
        )
    ).use { reader ->
        var valuesLeft = 250_000
        val value = reader.readValue {
            require(valuesLeft-- > 0) { "Too many values in backup JSON" }
        }
        require(value is JSONObject && reader.peek() == JsonToken.END_DOCUMENT) {
            "Expected a single JSON backup object"
        }
        value
    }
} catch (failure: Exception) {
    if (failure is MalformedJsonException || failure is EOFException || failure is CharacterCodingException) {
        throw IllegalArgumentException("Invalid backup JSON", failure)
    }
    throw failure
}

private fun JsonReader.readValue(count: () -> Unit): Any {
    count()
    return when (peek()) {
        JsonToken.BEGIN_OBJECT -> JSONObject().apply {
            beginObject()
            while (hasNext()) {
                val name = nextName()
                require(!has(name)) { "Duplicate backup JSON field" }
                put(name, readValue(count))
            }
            endObject()
        }

        JsonToken.BEGIN_ARRAY -> JSONArray().apply {
            beginArray()
            while (hasNext()) put(readValue(count))
            endArray()
        }

        JsonToken.NUMBER -> JSONTokener(nextString()).nextValue()
        JsonToken.STRING -> nextString()
        JsonToken.BOOLEAN -> nextBoolean()
        JsonToken.NULL -> nextNull().let { JSONObject.NULL }
        else -> throw IllegalArgumentException("Invalid backup JSON value")
    }
}

