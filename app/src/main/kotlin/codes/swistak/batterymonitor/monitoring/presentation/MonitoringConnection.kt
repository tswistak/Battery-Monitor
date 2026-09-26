package codes.swistak.batterymonitor.monitoring.presentation

import android.os.Bundle
import kotlinx.coroutines.flow.StateFlow

/** Owns one UI subscription to the existing Messenger client, never a service or Activity. */
internal class MonitoringConnection {
    companion object {
        const val FIELD_PROCESS_ID = "monitoring_process_id"
        const val FIELD_SEQUENCE = "monitoring_sequence"
        const val FIELD_OBSERVED_AT = "monitoring_observed_at"
    }

    private val repository = MonitoringRepository()
    val state: StateFlow<MonitoringUiState> = repository.state

    private var started = false
    private var connected = false
    private var expectedProcessId: String? = null
    var generation: Int = 0
        private set

    fun start() {
        if (started) return
        started = true
        generation++
        repository.markInactive()
    }

    fun stop() {
        if (!started) return
        started = false
        generation++
        markDisconnected()
    }

    fun onServiceConnected(): Int {
        connected = true
        expectedProcessId = null
        return generation
    }

    fun onServiceDisconnected() {
        generation++
        markDisconnected()
    }

    fun accepts(generation: Int): Boolean = started && connected && generation == this.generation

    fun onHandshake(generation: Int, bundle: Bundle): Boolean {
        return onHandshake(generation, bundle.getString(FIELD_PROCESS_ID))
    }

    internal fun onHandshake(generation: Int, processId: String?): Boolean {
        if (!accepts(generation) || processId == null) return false
        expectedProcessId = processId
        return true
    }

    internal fun acceptsSnapshot(generation: Int, processId: String?, sequence: Long): Boolean {
        if (!accepts(generation) || processId != expectedProcessId || sequence < 0L) return false
        val old = state.value
        return old.processId != processId || sequence >= old.sequence
    }

    internal fun acceptVersion(generation: Int, processId: String?, sequence: Long): Boolean {
        if (!acceptsSnapshot(generation, processId, sequence)) return false
        repository.recordVersion(processId, sequence)
        return true
    }

    fun onSnapshot(generation: Int, bundle: Bundle): Boolean {
        if (!accepts(generation)) return false
        if (bundle.getLong(FIELD_OBSERVED_AT) <= 0L) return false
        val processId = bundle.getString(FIELD_PROCESS_ID) ?: return false
        val sequence = bundle.getLong(FIELD_SEQUENCE, -1L)
        if (!acceptsSnapshot(generation, processId, sequence)) return false
        return publishSnapshot(
            generation, processId, sequence, MonitoringSnapshot.fromBundle(bundle)
        )
    }

    internal fun publishSnapshot(
        generation: Int, processId: String, sequence: Long, snapshot: MonitoringSnapshot
    ): Boolean {
        if (!acceptVersion(generation, processId, sequence)) return false
        repository.publish(snapshot, processId, sequence)
        return true
    }

    private fun markDisconnected() {
        connected = false
        expectedProcessId = null
        repository.markInactive()
    }
}
