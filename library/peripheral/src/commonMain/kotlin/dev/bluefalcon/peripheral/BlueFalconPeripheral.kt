package dev.bluefalcon.peripheral

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface BlueFalconPeripheral {
    val state: StateFlow<PeripheralManagerState>
    val capabilities: PeripheralCapabilities
    val sessions: StateFlow<Set<PeripheralSession>>
    val requests: Flow<GattServerRequest>
    val events: Flow<PeripheralEvent>
    val notificationReadiness: Flow<NotificationReadiness>

    suspend fun start(config: PeripheralConfig)
    suspend fun stop()
    suspend fun close()
}
