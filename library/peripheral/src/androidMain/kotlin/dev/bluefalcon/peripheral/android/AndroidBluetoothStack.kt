package dev.bluefalcon.peripheral.android

import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattDescriptorId
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.GattServiceConfig
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.PeripheralSessionId

internal interface AndroidSessionTarget {
    fun isCurrent(): Boolean
    fun sendResponse(response: AndroidGattResponse): Boolean
    fun notify(request: AndroidNotificationRequest): AndroidNotificationStartResult
    fun disconnect(): Boolean
    fun retire() { disconnect() }
}

internal interface AndroidBluetoothStack {
    val capabilities: AndroidStackCapabilities

    fun validateStart() = Unit

    suspend fun open(listener: AndroidBluetoothStackListener)

    suspend fun addService(service: GattServiceConfig)

    suspend fun startAdvertising(config: AdvertiseConfig)

    fun sendResponse(response: AndroidGattResponse): Boolean

    fun notify(request: AndroidNotificationRequest): AndroidNotificationStartResult

    fun disconnect(sessionId: PeripheralSessionId): Boolean

    fun stopAdvertising()

    fun clearServices() = Unit

    fun closeGattServer()
}

internal interface AndroidBluetoothStackListener {
    fun onEvent(event: AndroidGattEvent)

    fun onPlatformFailure(cause: Throwable)
    fun onResourceOverflow(cause: Throwable) = onPlatformFailure(cause)
}

internal data class AndroidStackCapabilities(
    val localGattServer: Boolean,
    val connectableAdvertising: Boolean,
)

internal sealed interface AndroidGattEvent {
    val sessionId: PeripheralSessionId
    val target: AndroidSessionTarget? get() = null

    data class Connected(
        override val sessionId: PeripheralSessionId,
        override val target: AndroidSessionTarget? = null,
    ) : AndroidGattEvent

    data class Disconnected(
        override val sessionId: PeripheralSessionId,
        val status: Int,
        override val target: AndroidSessionTarget? = null,
    ) : AndroidGattEvent

    data class MtuChanged(
        override val sessionId: PeripheralSessionId,
        val mtu: Int,
        override val target: AndroidSessionTarget? = null,
    ) : AndroidGattEvent

    data class CharacteristicRead(
        override val sessionId: PeripheralSessionId,
        val requestId: Int,
        val serviceId: GattServiceId,
        val characteristicId: GattCharacteristicId,
        val offset: Int,
        override val target: AndroidSessionTarget? = null,
    ) : AndroidGattEvent

    class CharacteristicWrite(
        override val sessionId: PeripheralSessionId,
        val requestId: Int,
        val serviceId: GattServiceId,
        val characteristicId: GattCharacteristicId,
        val offset: Int,
        val preparedWrite: Boolean,
        val responseNeeded: Boolean,
        value: ByteArray,
        override val target: AndroidSessionTarget? = null,
    ) : AndroidGattEvent {
        private val copiedValue = value.copyOf()
        val payloadBytes: Long get() = copiedValue.size.toLong()
        val value: ByteArray get() = copiedValue.copyOf()
    }

    data class DescriptorRead(
        override val sessionId: PeripheralSessionId,
        val requestId: Int,
        val serviceId: GattServiceId,
        val characteristicId: GattCharacteristicId,
        val descriptorId: GattDescriptorId,
        val offset: Int,
        override val target: AndroidSessionTarget? = null,
    ) : AndroidGattEvent

    class DescriptorWrite(
        override val sessionId: PeripheralSessionId,
        val requestId: Int,
        val serviceId: GattServiceId,
        val characteristicId: GattCharacteristicId,
        val descriptorId: GattDescriptorId,
        val offset: Int,
        val preparedWrite: Boolean,
        val responseNeeded: Boolean,
        value: ByteArray,
        override val target: AndroidSessionTarget? = null,
    ) : AndroidGattEvent {
        private val copiedValue = value.copyOf()
        val payloadBytes: Long get() = copiedValue.size.toLong()
        val value: ByteArray get() = copiedValue.copyOf()
    }

    data class ExecuteWrite(
        override val sessionId: PeripheralSessionId,
        val requestId: Int,
        val execute: Boolean,
        override val target: AndroidSessionTarget? = null,
    ) : AndroidGattEvent

    data class NotificationSent(
        override val sessionId: PeripheralSessionId,
        val status: Int,
        override val target: AndroidSessionTarget? = null,
    ) : AndroidGattEvent
}

internal class AndroidGattResponse(
    val sessionId: PeripheralSessionId,
    val requestId: Int,
    val status: GattResponseStatus,
    val offset: Int,
    value: ByteArray?,
) {
    private val copiedValue = value?.copyOf()
    val value: ByteArray? get() = copiedValue?.copyOf()
}

internal class AndroidNotificationRequest(
    val sessionId: PeripheralSessionId,
    val characteristicId: GattCharacteristicId,
    val mode: NotificationMode,
    value: ByteArray,
) {
    private val copiedValue = value.copyOf()
    val value: ByteArray get() = copiedValue.copyOf()
}

internal sealed interface AndroidNotificationStartResult {
    data object Accepted : AndroidNotificationStartResult

    data class Rejected(
        val cause: Throwable,
    ) : AndroidNotificationStartResult
}
