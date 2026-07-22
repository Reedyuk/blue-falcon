package dev.bluefalcon.peripheral

enum class NotificationMode {
    Notification,
    Indication,
}

sealed interface NotificationResult {
    data object Sent : NotificationResult
    data object Busy : NotificationResult
    data object Disconnected : NotificationResult
    data object Unsupported : NotificationResult
    data class Failed(val cause: Throwable) : NotificationResult
}

sealed interface DisconnectResult {
    data object Disconnected : DisconnectResult
    data object AlreadyDisconnected : DisconnectResult
    data object Unsupported : DisconnectResult
    data class Failed(val cause: Throwable) : DisconnectResult
}

sealed interface GattResponseResult {
    data object Responded : GattResponseResult
    data object AlreadyResponded : GattResponseResult
    data object Expired : GattResponseResult
}

enum class GattResponseStatus {
    Success,
    InvalidHandle,
    ReadNotPermitted,
    WriteNotPermitted,
    InvalidOffset,
    InvalidAttributeValueLength,
    InsufficientAuthentication,
    InsufficientAuthorization,
    InsufficientEncryption,
    RequestNotSupported,
    PrepareQueueFull,
    UnlikelyError,
}

class PeripheralUnsupportedException(operation: String) :
    UnsupportedOperationException("Peripheral operation is unsupported: $operation")

class PeripheralLifecycleException(message: String) : IllegalStateException(message)
