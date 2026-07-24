package dev.bluefalcon.peripheral

sealed interface PeripheralManagerState {
    data object Stopped : PeripheralManagerState
    data object Starting : PeripheralManagerState
    data object Running : PeripheralManagerState
    data object Stopping : PeripheralManagerState
    data class Failed(val cause: Throwable) : PeripheralManagerState
    data object Closed : PeripheralManagerState
}

sealed interface SessionState {
    data object Active : SessionState
    data object Closing : SessionState
    data object Closed : SessionState
}
