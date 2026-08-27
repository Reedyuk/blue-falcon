package dev.bluefalcon.core

/**
 * Typed, per-peripheral connection state derived by [BlueFalcon] from the lower-level
 * [BlueFalconEngine.connectionStateUpdates] and [BlueFalconEngine.serviceDiscoveryUpdates]
 * flows, plus the outcome of [BlueFalcon.connect]/[BlueFalcon.disconnect] calls.
 *
 * Unlike polling [BlueFalcon.connectionState] or collecting the raw `SharedFlow`s directly,
 * [BlueFalcon.connectionStateFlow] exposes this as a [kotlinx.coroutines.flow.StateFlow], so a
 * collector that subscribes after a peripheral already connected immediately observes the
 * current state instead of waiting for the next transition.
 *
 * [Ready] reflects only that the GATT *service* table has been populated (i.e.
 * [ServiceDiscoveryPhase.ServicesDiscovered] was observed) — it does not imply that
 * characteristics for every service have been discovered. Consumers still choose which services
 * they care about and call [BlueFalcon.discoverCharacteristics] themselves, exactly as they do
 * today via [BlueFalcon.serviceDiscoveryUpdates].
 *
 * See ADR 0008 for the full rationale and derivation rules.
 */
sealed class PeripheralConnectionState {
    /**
     * Not connected. [reason] is `null` only for a peripheral that has never been connected to.
     */
    data class Disconnected(val reason: DisconnectReason? = null) : PeripheralConnectionState()

    /** [BlueFalcon.connect] was called and a platform response is pending. */
    object Connecting : PeripheralConnectionState()

    /** The link is up, but the GATT service table has not been populated yet. */
    object Connected : PeripheralConnectionState()

    /** The link is up and the GATT service table has been populated. */
    object Ready : PeripheralConnectionState()

    /** [BlueFalcon.disconnect] was called and a platform response is pending. */
    object Disconnecting : PeripheralConnectionState()
}

/**
 * Why a peripheral transitioned to [PeripheralConnectionState.Disconnected].
 */
sealed class DisconnectReason {
    /** [BlueFalcon.disconnect] was called by the application. */
    object UserInitiated : DisconnectReason()

    /** [BlueFalcon.connect] itself failed (e.g. the platform rejected the request). */
    data class ConnectFailed(val cause: Throwable) : DisconnectReason()

    /**
     * The peripheral disconnected while [PeripheralConnectionState.Connected] or
     * [PeripheralConnectionState.Ready], without the application having called
     * [BlueFalcon.disconnect]. The underlying platform cause is not currently surfaced by
     * [BlueFalconEngine] (see ADR 0008's Negative consequences).
     */
    object Unexpected : DisconnectReason()
}
