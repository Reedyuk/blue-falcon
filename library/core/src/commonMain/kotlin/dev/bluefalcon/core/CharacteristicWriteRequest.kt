package dev.bluefalcon.core

/**
 * Emitted by [BluetoothAdvertiser.characteristicWriteRequests] when a remote central
 * writes to one of the hosted GATT characteristics.
 *
 * @property serviceUuid        UUID of the service containing the characteristic.
 * @property characteristicUuid UUID of the written characteristic.
 * @property value              Bytes written by the central.
 * @property requestId          Platform-specific request identifier (used to send a response).
 *                              -1 if the write type is WRITE_NO_RESPONSE.
 */
data class CharacteristicWriteRequest(
    val serviceUuid: String,
    val characteristicUuid: String,
    val value: ByteArray,
    val requestId: Int = -1
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CharacteristicWriteRequest) return false
        return serviceUuid == other.serviceUuid &&
                characteristicUuid == other.characteristicUuid &&
                value.contentEquals(other.value) &&
                requestId == other.requestId
    }

    override fun hashCode(): Int {
        var result = serviceUuid.hashCode()
        result = 31 * result + characteristicUuid.hashCode()
        result = 31 * result + value.contentHashCode()
        result = 31 * result + requestId
        return result
    }
}
