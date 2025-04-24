package dev.bluefalcon.engine

import dev.bluefalcon.ApplicationContext
import dev.bluefalcon.BlueFalconDelegate

class AndroidBluetoothEngineConfig(
    val context: ApplicationContext,
    val autoDiscoverAllServicesAndCharacteristics: Boolean = true,
    bluetoothCallbackDelegate: BlueFalconDelegate?,
) : BluetoothEngineConfig(bluetoothCallbackDelegate) {
}