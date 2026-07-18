package dev.bluefalcon.peripheral.fake

import dev.bluefalcon.peripheral.DisconnectResult
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.NotificationResult
import dev.bluefalcon.peripheral.PeripheralCapabilities
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.internal.PeripheralBackend
import dev.bluefalcon.peripheral.internal.PeripheralBackendEventSink

internal class FakePeripheralBackend(
    override val capabilities: PeripheralCapabilities = SupportedCapabilities,
) : PeripheralBackend {

    var startFailure: Throwable? = null
    var startCalls = 0
        private set
    var stopCalls = 0
        private set
    var closeCalls = 0
        private set
    val startConfigs = mutableListOf<PeripheralConfig>()

    lateinit var eventSink: PeripheralBackendEventSink
        private set

    override suspend fun start(
        config: PeripheralConfig,
        eventSink: PeripheralBackendEventSink,
    ) {
        startCalls++
        startConfigs += config
        this.eventSink = eventSink
        startFailure?.let { throw it }
    }

    override suspend fun stop() {
        stopCalls++
    }

    override suspend fun close() {
        closeCalls++
    }

    override suspend fun notify(
        sessionId: PeripheralSessionId,
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode,
    ): NotificationResult = NotificationResult.Sent

    override suspend fun disconnect(
        sessionId: PeripheralSessionId,
    ): DisconnectResult = DisconnectResult.Disconnected

    private companion object {
        val SupportedCapabilities = PeripheralCapabilities(
            localGattServer = true,
            connectableAdvertising = true,
            multiCentral = true,
            targetedNotifications = true,
            notificationReadiness = true,
            maximumUpdateValueLength = true,
            forcedDisconnect = true,
            connectionLifecycleVisibility = true,
            preparedWrites = true,
            stateRestoration = true,
        )
    }
}
