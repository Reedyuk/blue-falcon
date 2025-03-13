package dev.bluefalcon.engine

import dev.bluefalcon.BlueFalconDelegate

class DarwinBluetoothEngineConfig(
    val autoDiscoverAllServicesAndCharacteristics: Boolean = true,
    bluetoothCallbackDelegate: BlueFalconDelegate,
) : BluetoothEngineConfig(bluetoothCallbackDelegate) {
}