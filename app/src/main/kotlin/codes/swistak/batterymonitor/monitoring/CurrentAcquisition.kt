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

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/
package codes.swistak.batterymonitor.monitoring

import java.util.concurrent.Executor

internal data class CurrentSample(
    val now: BatteryCurrentReading?, val average: BatteryCurrentReading?
) {
    fun selected(preferAverage: Boolean): BatteryCurrentReading? =
        if (preferAverage) average ?: now else now
}

internal class CurrentAcquisition(
    private val readNow: () -> BatteryCurrentReading?,
    private val readAverage: () -> BatteryCurrentReading?,
    private val executor: Executor,
    private val deliver: (() -> Unit) -> Unit
) {
    private class Request {
        var includeAverage = false
        var averageRead = false
        var now: BatteryCurrentReading? = null
        var average: BatteryCurrentReading? = null
        val callbacks = mutableListOf<(CurrentSample) -> Unit>()
    }

    private val lock = Any()
    private var pending: Request? = null
    private var closed = false

    fun read(includeAverage: Boolean, onResult: (CurrentSample) -> Unit) {
        val request = synchronized(lock) {
            if (closed) return
            val active = pending
            if (active != null) {
                active.includeAverage = active.includeAverage || includeAverage
                active.callbacks += onResult
                return
            }
            Request().also {
                it.includeAverage = includeAverage
                it.callbacks += onResult
                pending = it
            }
        }
        acquire(request, readInstantaneous = true)
    }

    fun requestAverage(): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        val request = pending ?: return@synchronized false
        request.includeAverage = true
        true
    }

    fun close() {
        synchronized(lock) {
            closed = true
            pending?.callbacks?.clear()
            pending = null
        }
    }

    private fun acquire(request: Request, readInstantaneous: Boolean) {
        try {
            executor.execute {
                if (synchronized(lock) { closed || pending !== request }) return@execute
                if (readInstantaneous) {
                    val now = runCatching(readNow).getOrNull()
                    synchronized(lock) { request.now = now }
                }
                val includeAverage = synchronized(lock) {
                    !closed && pending === request && request.includeAverage && !request.averageRead
                }
                if (includeAverage) {
                    val average = runCatching(readAverage).getOrNull()
                    synchronized(lock) {
                        request.average = average
                        request.averageRead = true
                    }
                }
                publish(request)
            }
        } catch (_: RuntimeException) {
            synchronized(lock) { request.averageRead = true }
            publish(request)
        }
    }

    private fun publish(request: Request) {
        var handled = false
        val action = action@{
            val callbacks = synchronized(lock) {
                if (handled) return@action
                handled = true
                if (closed || pending !== request) return@synchronized null
                if (request.includeAverage && !request.averageRead) return@synchronized null
                pending = null
                request.callbacks.toList().also { request.callbacks.clear() }
            }
            if (callbacks == null) {
                if (synchronized(lock) { !closed && pending === request }) {
                    acquire(request, readInstantaneous = false)
                }
            } else {
                val sample = CurrentSample(request.now, request.average)
                for (callback in callbacks) {
                    if (synchronized(lock) { closed }) break
                    runCatching { callback(sample) }
                }
            }
        }
        try {
            deliver(action)
        } catch (_: RuntimeException) {
            action()
        }
    }
}
