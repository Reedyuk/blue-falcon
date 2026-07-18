package dev.bluefalcon.peripheral.fake

import dev.bluefalcon.peripheral.DisconnectResult
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.NotificationResult
import dev.bluefalcon.peripheral.PeripheralCapabilities
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.NotificationReadiness
import dev.bluefalcon.peripheral.internal.PeripheralBackend
import dev.bluefalcon.peripheral.internal.PeripheralBackendEventSink
import dev.bluefalcon.peripheral.internal.BackendCharacteristicReadRequest
import dev.bluefalcon.peripheral.internal.BackendCharacteristicWriteRequest
import dev.bluefalcon.peripheral.internal.BackendGattResponder

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
    var notificationResult: NotificationResult = NotificationResult.Sent
    var disconnectResult: DisconnectResult = DisconnectResult.Disconnected
    var mutateNotificationInput = false
    var lastNotificationSessionId: PeripheralSessionId? = null
        private set
    var lastNotificationCharacteristic: GattCharacteristicId? = null
        private set
    var lastNotificationValue: ByteArray? = null
        private set
    var lastNotificationMode: NotificationMode? = null
        private set
    val disconnectSessionIds = mutableListOf<PeripheralSessionId>()

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
    ): NotificationResult {
        lastNotificationSessionId = sessionId
        lastNotificationCharacteristic = characteristic
        lastNotificationValue = value.copyOf()
        lastNotificationMode = mode
        if (mutateNotificationInput && value.isNotEmpty()) value[0] = 99
        return notificationResult
    }

    override suspend fun disconnect(
        sessionId: PeripheralSessionId,
    ): DisconnectResult {
        disconnectSessionIds += sessionId
        return disconnectResult
    }

    fun openSession(
        sessionId: PeripheralSessionId,
        maximumUpdateValueLength: Int? = null,
    ) {
        eventSink.onSessionOpened(sessionId, maximumUpdateValueLength)
    }

    fun closeSession(
        sessionId: PeripheralSessionId,
        cause: Throwable? = null,
    ) {
        eventSink.onSessionClosed(sessionId, cause)
    }

    fun updateSubscriptions(
        sessionId: PeripheralSessionId,
        subscriptions: Set<GattCharacteristicId>,
    ) {
        eventSink.onSubscriptionsChanged(sessionId, subscriptions)
    }

    fun updateMaximumValueLength(
        sessionId: PeripheralSessionId,
        maximumUpdateValueLength: Int?,
    ) {
        eventSink.onMaximumUpdateValueLengthChanged(
            sessionId,
            maximumUpdateValueLength,
        )
    }

    fun signalNotificationReady(readiness: NotificationReadiness) {
        eventSink.onNotificationReady(readiness)
    }

    fun emitCharacteristicRead(
        sessionId: PeripheralSessionId,
        serviceId: GattServiceId,
        characteristicId: GattCharacteristicId,
        offset: Int = 0,
    ): RecordedGattResponder {
        val responder = RecordedGattResponder()
        eventSink.onRequest(
            BackendCharacteristicReadRequest(
                sessionId = sessionId,
                serviceId = serviceId,
                characteristicId = characteristicId,
                offset = offset,
                responder = responder,
            ),
        )
        return responder
    }

    fun emitCharacteristicWrite(
        sessionId: PeripheralSessionId,
        serviceId: GattServiceId,
        characteristicId: GattCharacteristicId,
        value: ByteArray,
        responseRequired: Boolean,
        offset: Int = 0,
        preparedWrite: Boolean = false,
    ): RecordedGattResponder? {
        val responder = if (responseRequired) RecordedGattResponder() else null
        eventSink.onRequest(
            BackendCharacteristicWriteRequest(
                sessionId = sessionId,
                serviceId = serviceId,
                characteristicId = characteristicId,
                offset = offset,
                value = value,
                preparedWrite = preparedWrite,
                responder = responder,
            ),
        )
        return responder
    }

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

internal class RecordedGattResponder : BackendGattResponder {
    val responses = mutableListOf<RecordedGattResponse>()

    override fun respond(status: GattResponseStatus, value: ByteArray?) {
        responses += RecordedGattResponse(status, value)
    }
}

internal class RecordedGattResponse(
    val status: GattResponseStatus,
    value: ByteArray?,
) {
    private val copiedValue = value?.copyOf()
    val value: ByteArray?
        get() = copiedValue?.copyOf()
}
