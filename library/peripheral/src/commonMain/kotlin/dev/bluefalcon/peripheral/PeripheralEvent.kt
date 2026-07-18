package dev.bluefalcon.peripheral

sealed interface NotificationReadiness {
    data object Manager : NotificationReadiness
    data class Session(val sessionId: PeripheralSessionId) : NotificationReadiness
}

enum class GattRequestType {
    CharacteristicRead,
    CharacteristicWrite,
    DescriptorRead,
    DescriptorWrite,
    ExecuteWrite,
}

sealed interface PeripheralEvent {
    data class RequestDropped(
        val sessionId: PeripheralSessionId,
        val requestType: GattRequestType,
    ) : PeripheralEvent

    data class ResponseTimedOut(
        val sessionId: PeripheralSessionId,
        val requestType: GattRequestType,
    ) : PeripheralEvent

    data class SessionClosed(
        val sessionId: PeripheralSessionId,
        val cause: Throwable? = null,
    ) : PeripheralEvent

    data class PlatformFailure(val cause: Throwable) : PeripheralEvent
}
