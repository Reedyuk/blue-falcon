package dev.bluefalcon.core

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
