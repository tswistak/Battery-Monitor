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
package codes.swistak.batterymonitor.ui.history

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import codes.swistak.batterymonitor.logs.LogDatabase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private const val HISTORY_CHANGE_DEBOUNCE_MS = 120L

@Composable
internal fun HistoryChangeEffect(onActivated: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activated = rememberUpdatedState(onActivated)
    val changed = rememberUpdatedState(onChanged)
    val scope = rememberCoroutineScope()
    DisposableEffect(context, lifecycle) {
        var registered = false
        var pending: Job? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != LogDatabase.ACTION_HISTORY_CHANGED || !registered || !lifecycle.currentState.isAtLeast(
                        Lifecycle.State.STARTED
                    )
                ) return
                pending?.cancel()
                pending = scope.launch {
                    delay(HISTORY_CHANGE_DEBOUNCE_MS.milliseconds)
                    if (registered && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) changed.value()
                }
            }
        }

        fun start() {
            if (registered) return
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(LogDatabase.ACTION_HISTORY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            registered = true
        }

        fun stop() {
            pending?.cancel()
            if (registered) context.unregisterReceiver(receiver)
            registered = false
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_RESUME -> {
                    start()
                    pending?.cancel()
                    activated.value()
                }

                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            stop()
        }
    }
}
