package dev.bluefalcon.peripheral

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Peripheral manager with bounded request and backend-event handoffs.
 *
 * The default backend-event queue holds 256 events plus one being processed. If
 * native callbacks outpace it, the backend is stopped, sessions are closed, and
 * [state] becomes [PeripheralManagerState.Failed]. Observe state for this failure;
 * the bounded [events] stream may already be full. Call [stop] before restarting.
 */
interface BlueFalconPeripheral {
    val state: StateFlow<PeripheralManagerState>
    val capabilities: PeripheralCapabilities
    val plugins: PeripheralPluginRegistry
    val sessions: StateFlow<Set<PeripheralSession>>
    val requests: Flow<GattServerRequest>
    val events: Flow<PeripheralEvent>
    val notificationReadiness: Flow<NotificationReadiness>
    val notificationReadinessState: StateFlow<NotificationReadinessState>

    suspend fun start(config: PeripheralConfig)
    suspend fun stop()
    suspend fun close()
}
