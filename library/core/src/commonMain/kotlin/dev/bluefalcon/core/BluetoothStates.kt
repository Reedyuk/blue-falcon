package dev.bluefalcon.core

/**
 * Reactive connection state change event emitted by [BlueFalconEngine.connectionStateUpdates].
 *
 * Subscribe to [BlueFalconEngine.connectionStateUpdates] (or [BlueFalcon.connectionStateUpdates])
 * instead of polling [BlueFalcon.connectionState] to observe transitions such as Connected →
 * Disconnected without timing races.
 */
data class ConnectionStateUpdate(
    val peripheral: BluetoothPeripheral,
    val state: BluetoothPeripheralState
)

/**
 * Connection state of a Bluetooth peripheral
 */
enum class BluetoothPeripheralState {
    Connecting,
    Connected,
    Disconnected,
    Disconnecting,
    Unknown
}

/**
 * Bluetooth manager state
 */
enum class BluetoothManagerState {
    Ready,
    NotReady
}

/**
 * Connection priority levels (primarily for Android)
 */
enum class ConnectionPriority {
    /** Balanced connection parameters */
    Balanced,
    /** High priority, low latency connection */
    High,
    /** Low priority, power saving connection */
    Low
}

/**
 * Bond state for peripheral pairing
 */
enum class BlueFalconBondState {
    None,
    Bonding,
    Bonded
}
