/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/
package codes.swistak.batterymonitor.monitoring.charginglimit

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import codes.swistak.batterymonitor.privileged.PrivilegedAccess
import codes.swistak.batterymonitor.settings.SettingsContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

internal class ChargingLimitContentProvider : ContentProvider() {
    private val preferences by lazy {
        requireNotNull(context).getSharedPreferences(
            SettingsContract.SETTINGS_FILE, Context.MODE_PRIVATE
        )
    }
    private val reader by lazy {
        DeviceChargingLimitProvider(requireNotNull(context), privilegedAccessEnabled = {
            preferences.getBoolean(
                SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false
            ) && PrivilegedAccess.isEnabled()
        })
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var ready = false
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == SettingsContract.KEY_USE_PRIVILEGED_ACCESS) {
            syncAccess()
            notifyAccessChanged()
        }
    }

    override fun onCreate(): Boolean {
        Handler(Looper.getMainLooper()).post {
            PrivilegedAccess.initialize(requireNotNull(context))
            preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
            syncAccess()
            ready = true
            scope.launch {
                PrivilegedAccess.accessChanges.drop(1).collect { notifyAccessChanged() }
            }
            notifyAccessChanged()
        }
        return true
    }

    private fun syncAccess() = PrivilegedAccess.setEnabled(
        preferences.getBoolean(SettingsContract.KEY_USE_PRIVILEGED_ACCESS, false)
    )

    private fun notifyAccessChanged() {
        val app = requireNotNull(context)
        app.contentResolver.notifyChange(uri(app), null)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(method == READ) { "Unknown charging-limit operation" }
        check(Looper.myLooper() != Looper.getMainLooper()) { "Charging-limit reads require a worker" }
        return (if (ready) reader.readState() else unavailable()).toBundle()
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor = throw UnsupportedOperationException()

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException()

    companion object {
        private const val READ = "read"
        fun uri(context: Context): Uri = "content://${context.packageName}.charging-limit".toUri()

        fun read(context: Context): ChargingLimitState {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Charging-limit reads require a worker" }
            return try {
                context.contentResolver.acquireUnstableContentProviderClient(uri(context))
                    .use { client ->
                        fromBundle(client?.call(READ, null, null))
                    }
            } catch (_: Exception) {
                unavailable()
            }
        }

        private fun unavailable() =
            ChargingLimitState.Unavailable(ChargingLimitUnavailableReason.READ_FAILED)

        private fun ChargingLimitState.toBundle() = Bundle().apply {
            val evidence = when (val state = this@toBundle) {
                is ChargingLimitState.Fixed -> {
                    putInt("configured", state.configuredPercent)
                    putInt("effective", state.effectivePercent)
                    state.evidence
                }

                is ChargingLimitState.NoFixedLimit -> {
                    putString("noFixed", state.kind.name)
                    state.evidence
                }

                is ChargingLimitState.Unavailable -> {
                    putString("unavailable", state.reason.name)
                    putString("adapter", state.adapterId)
                    putString("detail", state.detail)
                    null
                }
            }
            evidence?.let {
                putString("evidence", it.kind.name)
                putStringArray("sources", it.sources.map(ReadSource::name).toTypedArray())
            }
        }

        private fun fromBundle(bundle: Bundle?): ChargingLimitState {
            if (bundle == null) return unavailable()
            bundle.getString("unavailable")?.let {
                return ChargingLimitState.Unavailable(
                    ChargingLimitUnavailableReason.valueOf(it),
                    bundle.getString("adapter"),
                    bundle.getString("detail")
                )
            }
            val evidence = ChargingLimitEvidence(
                ChargingLimitEvidenceKind.valueOf(requireNotNull(bundle.getString("evidence"))),
                requireNotNull(bundle.getStringArray("sources")).map(ReadSource::valueOf).toSet()
            )
            bundle.getString("noFixed")?.let {
                return ChargingLimitState.NoFixedLimit(NoFixedLimitKind.valueOf(it), evidence)
            }
            return ChargingLimitState.Fixed(
                bundle.getInt("configured"), bundle.getInt("effective"), evidence
            ).takeIf { it.isValid } ?: unavailable()
        }
    }
}
