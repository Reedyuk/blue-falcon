package dev.bluefalcon

import dev.bluefalcon.engine.BluetoothActionResult
import kotlinx.coroutines.flow.MutableStateFlow

expect class BluetoothPeripheral(device: NativeBluetoothDevice) {
    val name: String?
    val uuid: String
    var rssi: Float?
    var mtuSize: Int?
    val services: Map<Uuid, BluetoothService>
    internal val _servicesFlow: MutableStateFlow<List<BluetoothService>>

    val characteristics: Map<Uuid, BluetoothCharacteristic>
}

expect class NativeBluetoothDevice

// Bluetooth Peripheral replacement
data class BluetoothDevice(
    val uuid: String,
    val name: String? = null,
    var rssi: Float? = null,
    val mtuSize: Int? = null,
    val services: List<BTService> = emptyList(),
)

data class BTService(
    val uuid: Uuid,
    val name: String?,
    val characteristics: List<BTCharacteristic> = emptyList()
)

data class BTCharacteristic(
    val uuid: Uuid,
    val name: String?,
//    val properties: List<BTCharacteristicProperty>,
//    val permissions: List<BTCharacteristicPermission>,
    val value: ByteArray? = null,
//    val descriptor: List<BTDescriptor> = emptyList(),
)
