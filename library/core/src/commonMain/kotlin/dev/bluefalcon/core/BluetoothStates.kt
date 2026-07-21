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

/**
 * Phases of GATT service/characteristic discovery emitted by
 * [BlueFalconEngine.serviceDiscoveryUpdates].
 */
enum class ServiceDiscoveryPhase {
    /** The peripheral's service list has been populated. */
    ServicesDiscovered,
    /**
     * Characteristics for [ServiceDiscoveryUpdate.service] have been populated.
     * Emitted once per service as its characteristics become available.
     */
    CharacteristicsDiscovered,
}

/**
 * Reactive GATT discovery event emitted by [BlueFalconEngine.serviceDiscoveryUpdates].
 *
 * Subscribe to [BlueFalconEngine.serviceDiscoveryUpdates] (or [BlueFalcon.serviceDiscoveryUpdates])
 * to be notified when services and characteristics are ready without polling or using arbitrary
 * delays.
 *
 * Typical usage:
 * ```
 * blueFalcon.serviceDiscoveryUpdates
 *     .filter { it.peripheral.uuid == myPeripheral.uuid }
 *     .collect { update ->
 *         when (update.phase) {
 *             ServiceDiscoveryPhase.ServicesDiscovered ->
 *                 update.peripheral.services.forEach { blueFalcon.discoverCharacteristics(update.peripheral, it) }
 *             ServiceDiscoveryPhase.CharacteristicsDiscovered ->
 *                 println("Ready: ${update.service?.uuid}")
 *         }
 *     }
 * ```
 *
 * @property peripheral The peripheral whose discovery state changed.
 * @property phase      The current phase of discovery.
 * @property service    The service whose characteristics were discovered; non-null only when
 *                      [phase] is [ServiceDiscoveryPhase.CharacteristicsDiscovered].
 */
data class ServiceDiscoveryUpdate(
    val peripheral: BluetoothPeripheral,
    val phase: ServiceDiscoveryPhase,
    val service: BluetoothService? = null,
)
