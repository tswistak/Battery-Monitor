package codes.swistak.batterymonitor.monitoring.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal enum class MonitoringAvailability { WAITING, LIVE, STALE }

internal data class MonitoringUiState(
    val availability: MonitoringAvailability = MonitoringAvailability.WAITING,
    val snapshot: MonitoringSnapshot? = null,
    val processId: String? = null,
    val sequence: Long = -1L
)

/** Converts each accepted IPC bundle into one immutable state emission. */
internal class MonitoringRepository {
    private val mutableState = MutableStateFlow(MonitoringUiState())
    val state: StateFlow<MonitoringUiState> = mutableState

    internal fun publish(snapshot: MonitoringSnapshot, processId: String, sequence: Long) {
        mutableState.value = MonitoringUiState(
            availability = MonitoringAvailability.LIVE,
            snapshot = snapshot,
            processId = processId,
            sequence = sequence
        )
    }

    internal fun recordVersion(processId: String?, sequence: Long) {
        mutableState.value = mutableState.value.copy(processId = processId, sequence = sequence)
    }

    fun markInactive() {
        mutableState.value = mutableState.value.copy(
            availability = if (mutableState.value.snapshot == null) {
                MonitoringAvailability.WAITING
            } else {
                MonitoringAvailability.STALE
            }
        )
    }
}
