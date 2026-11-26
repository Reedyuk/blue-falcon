package dev.bluefalcon.peripheral

/**
 * @property l2capServer Whether [BlueFalconPeripheral.publishL2capChannel] is supported:
 *   Android 10 (API 29)+ with a Bluetooth adapter, iOS and macOS.
 */
data class PeripheralCapabilities(
    val localGattServer: Boolean,
    val connectableAdvertising: Boolean,
    val multiCentral: Boolean,
    val targetedNotifications: Boolean,
    val notificationReadiness: Boolean,
    val maximumUpdateValueLength: Boolean,
    val forcedDisconnect: Boolean,
    val connectionLifecycleVisibility: Boolean,
    val preparedWrites: Boolean,
    val stateRestoration: Boolean,
    val l2capServer: Boolean = false,
) {
    /** Keeps the previous JVM constructor signature for callers compiled against older releases. */
    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    constructor(
        localGattServer: Boolean,
        connectableAdvertising: Boolean,
        multiCentral: Boolean,
        targetedNotifications: Boolean,
        notificationReadiness: Boolean,
        maximumUpdateValueLength: Boolean,
        forcedDisconnect: Boolean,
        connectionLifecycleVisibility: Boolean,
        preparedWrites: Boolean,
        stateRestoration: Boolean,
    ) : this(
        localGattServer = localGattServer,
        connectableAdvertising = connectableAdvertising,
        multiCentral = multiCentral,
        targetedNotifications = targetedNotifications,
        notificationReadiness = notificationReadiness,
        maximumUpdateValueLength = maximumUpdateValueLength,
        forcedDisconnect = forcedDisconnect,
        connectionLifecycleVisibility = connectionLifecycleVisibility,
        preparedWrites = preparedWrites,
        stateRestoration = stateRestoration,
        l2capServer = false,
    )

    companion object {
        val Unsupported = PeripheralCapabilities(
            localGattServer = false,
            connectableAdvertising = false,
            multiCentral = false,
            targetedNotifications = false,
            notificationReadiness = false,
            maximumUpdateValueLength = false,
            forcedDisconnect = false,
            connectionLifecycleVisibility = false,
            preparedWrites = false,
            stateRestoration = false,
            l2capServer = false,
        )
    }
}
