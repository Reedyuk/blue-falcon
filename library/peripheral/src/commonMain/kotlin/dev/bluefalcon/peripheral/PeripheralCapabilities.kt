package dev.bluefalcon.peripheral

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
) {
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
        )
    }
}
