package dev.bluefalcon.peripheral

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface PeripheralSession {
    val id: PeripheralSessionId
    val state: StateFlow<SessionState>
    val subscriptions: StateFlow<Set<GattCharacteristicId>>
    val maximumUpdateValueLength: StateFlow<Int?>
    val notificationReady: Flow<Unit>

    suspend fun notify(
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode = NotificationMode.Notification,
    ): NotificationResult

    suspend fun disconnect(): DisconnectResult
}
