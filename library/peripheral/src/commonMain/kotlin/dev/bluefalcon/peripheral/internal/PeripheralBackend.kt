package dev.bluefalcon.peripheral.internal

import dev.bluefalcon.peripheral.DisconnectResult
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattDescriptorId
import dev.bluefalcon.peripheral.GattRequestType
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.NotificationReadiness
import dev.bluefalcon.peripheral.NotificationResult
import dev.bluefalcon.peripheral.PeripheralCapabilities
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralSessionId

internal interface PeripheralBackend {
    val capabilities: PeripheralCapabilities

    suspend fun start(
        config: PeripheralConfig,
        eventSink: PeripheralBackendEventSink,
    )

    suspend fun stop()

    suspend fun close()

    suspend fun notify(
        sessionId: PeripheralSessionId,
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode,
    ): NotificationResult

    suspend fun disconnect(sessionId: PeripheralSessionId): DisconnectResult
}

internal interface PeripheralBackendEventSink {
    fun onSessionOpened(
        sessionId: PeripheralSessionId,
        maximumUpdateValueLength: Int? = null,
    )

    fun onSessionClosed(
        sessionId: PeripheralSessionId,
        cause: Throwable? = null,
    )

    fun onSubscriptionsChanged(
        sessionId: PeripheralSessionId,
        subscriptions: Set<GattCharacteristicId>,
    )

    fun onMaximumUpdateValueLengthChanged(
        sessionId: PeripheralSessionId,
        maximumUpdateValueLength: Int?,
    )

    fun onNotificationReady(readiness: NotificationReadiness)

    fun onRequest(request: BackendGattServerRequest)

    fun onPlatformFailure(cause: Throwable)
}

internal fun interface BackendGattResponder {
    fun respond(status: GattResponseStatus, value: ByteArray?)
}

internal sealed interface BackendGattServerRequest {
    val sessionId: PeripheralSessionId
    val requestType: GattRequestType
    val responder: BackendGattResponder?
}

internal sealed interface BackendGattAttributeRequest : BackendGattServerRequest {
    val serviceId: GattServiceId
    val characteristicId: GattCharacteristicId
    val descriptorId: GattDescriptorId?
    val offset: Int
}

internal class BackendCharacteristicReadRequest(
    override val sessionId: PeripheralSessionId,
    override val serviceId: GattServiceId,
    override val characteristicId: GattCharacteristicId,
    override val offset: Int,
    override val responder: BackendGattResponder,
) : BackendGattAttributeRequest {
    override val requestType = GattRequestType.CharacteristicRead
    override val descriptorId: GattDescriptorId? = null
}

internal class BackendCharacteristicWriteRequest(
    override val sessionId: PeripheralSessionId,
    override val serviceId: GattServiceId,
    override val characteristicId: GattCharacteristicId,
    override val offset: Int,
    value: ByteArray,
    val preparedWrite: Boolean,
    override val responder: BackendGattResponder?,
) : BackendGattAttributeRequest {
    private val copiedValue = value.copyOf()

    override val requestType = GattRequestType.CharacteristicWrite
    override val descriptorId: GattDescriptorId? = null
    val value: ByteArray
        get() = copiedValue.copyOf()
}

internal class BackendDescriptorReadRequest(
    override val sessionId: PeripheralSessionId,
    override val serviceId: GattServiceId,
    override val characteristicId: GattCharacteristicId,
    override val descriptorId: GattDescriptorId,
    override val offset: Int,
    override val responder: BackendGattResponder,
) : BackendGattAttributeRequest {
    override val requestType = GattRequestType.DescriptorRead
}

internal class BackendDescriptorWriteRequest(
    override val sessionId: PeripheralSessionId,
    override val serviceId: GattServiceId,
    override val characteristicId: GattCharacteristicId,
    override val descriptorId: GattDescriptorId,
    override val offset: Int,
    value: ByteArray,
    val preparedWrite: Boolean,
    override val responder: BackendGattResponder?,
) : BackendGattAttributeRequest {
    private val copiedValue = value.copyOf()

    override val requestType = GattRequestType.DescriptorWrite
    val value: ByteArray
        get() = copiedValue.copyOf()
}

internal class BackendExecuteWriteRequest(
    override val sessionId: PeripheralSessionId,
    val execute: Boolean,
    override val responder: BackendGattResponder,
) : BackendGattServerRequest {
    override val requestType = GattRequestType.ExecuteWrite
}
