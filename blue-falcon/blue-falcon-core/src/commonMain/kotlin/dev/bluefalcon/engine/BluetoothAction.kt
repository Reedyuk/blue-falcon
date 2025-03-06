package dev.bluefalcon.engine

import dev.bluefalcon.ServiceFilter
import dev.bluefalcon.Uuid

sealed class BluetoothAction {
    data class Scan(val filters: ServiceFilter? = null) : BluetoothAction()
    data object StopScan : BluetoothAction()

    data class Connect(val device: String) : BluetoothAction()
    data class Disconnect(val device: String) : BluetoothAction()

    data class DiscoverServices(val device: String, val serviceUUIDs: List<Uuid> = emptyList()) : BluetoothAction()
    data class DiscoverCharacteristics(
        val device: String,
        val service: Uuid,
        val characteristicUUIDs: List<Uuid> = emptyList()
    ) : BluetoothAction()

    data class ReadCharacteristic(val device: String, val characteristic: Uuid) : BluetoothAction()
    data class WriteCharacteristic(
        val device: String,
        val characteristic: Uuid,
        val value: ByteArray,
        val writeType: WriteType
    ) : BluetoothAction()
}

enum class WriteType {
    writeTypeDefault,
    writeTypeNoResponse
}
