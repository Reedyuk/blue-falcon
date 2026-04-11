package dev.bluefalcon.core

/**
 * Keys for retrieving advertisement data from discovered peripherals
 */
enum class AdvertisementDataRetrievalKeys {
    LocalName,
    ManufacturerData,
    ServiceUUIDsKey,
    IsConnectable
}

/**
 * Represents an L2CAP socket for BLE communication
 */
interface BluetoothSocket {
    /**
     * Close the socket
     */
    fun close()
}
