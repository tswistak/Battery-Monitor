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
package codes.swistak.batterymonitor.common

import java.io.File

internal interface SysfsAccessor {
    fun list(path: String): List<String>
    fun read(path: String): String?
}

internal class DirectSysfsAccessor : SysfsAccessor {
    override fun list(path: String): List<String> {
        return try {
            File(path).list()?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun read(path: String): String? {
        return try {
            val file = File(path)
            if (!file.isFile || !file.canRead()) return null
            file.bufferedReader().use { it.readLine()?.trim()?.takeIf(String::isNotEmpty) }
        } catch (_: Exception) {
            null
        }
    }
}

internal class CommandSysfsAccessor(private val executor: CommandExecutor) : SysfsAccessor {
    override fun list(path: String): List<String> {
        return executor.run("ls $path 2>/dev/null")?.lineSequence()?.map(String::trim)
            ?.filter(String::isNotEmpty)?.toList().orEmpty()
    }

    override fun read(path: String): String? {
        return executor.run("cat $path 2>/dev/null")?.trim()?.takeIf(String::isNotEmpty)
    }
}
