package dev.bluefalcon.peripheral

interface GattResponseHandle {
    suspend fun respond(
        status: GattResponseStatus,
        value: ByteArray? = null,
    ): GattResponseResult
}

sealed interface GattServerRequest {
    val session: PeripheralSession
    val response: GattResponseHandle?

    val sessionId: PeripheralSessionId
        get() = session.id
}

sealed interface GattAttributeRequest : GattServerRequest {
    val serviceId: GattServiceId
    val characteristicId: GattCharacteristicId
    val descriptorId: GattDescriptorId?
    val offset: Int
}

class GattCharacteristicReadRequest(
    override val session: PeripheralSession,
    override val serviceId: GattServiceId,
    override val characteristicId: GattCharacteristicId,
    override val offset: Int,
    override val response: GattResponseHandle?,
) : GattAttributeRequest {
    override val descriptorId: GattDescriptorId? = null
}

class GattCharacteristicWriteRequest(
    override val session: PeripheralSession,
    override val serviceId: GattServiceId,
    override val characteristicId: GattCharacteristicId,
    override val offset: Int,
    value: ByteArray,
    val preparedWrite: Boolean,
    override val response: GattResponseHandle?,
) : GattAttributeRequest {
    private val copiedValue = value.copyOf()

    override val descriptorId: GattDescriptorId? = null
    val value: ByteArray
        get() = copiedValue.copyOf()
}

class GattDescriptorReadRequest(
    override val session: PeripheralSession,
    override val serviceId: GattServiceId,
    override val characteristicId: GattCharacteristicId,
    override val descriptorId: GattDescriptorId,
    override val offset: Int,
    override val response: GattResponseHandle?,
) : GattAttributeRequest

class GattDescriptorWriteRequest(
    override val session: PeripheralSession,
    override val serviceId: GattServiceId,
    override val characteristicId: GattCharacteristicId,
    override val descriptorId: GattDescriptorId,
    override val offset: Int,
    value: ByteArray,
    val preparedWrite: Boolean,
    override val response: GattResponseHandle?,
) : GattAttributeRequest {
    private val copiedValue = value.copyOf()

    val value: ByteArray
        get() = copiedValue.copyOf()
}

class GattExecuteWriteRequest(
    override val session: PeripheralSession,
    val execute: Boolean,
    override val response: GattResponseHandle?,
) : GattServerRequest
