package dev.bluefalcon.engine

import dev.bluefalcon.BlueFalconDelegate
import dev.bluefalcon.Logger

class DarwinBluetoothEngineConfig(
    val autoDiscoverAllServicesAndCharacteristics: Boolean = true,
    bluetoothCallbackDelegate: BlueFalconDelegate?,
    logger: Logger?
) : BluetoothEngineConfig(bluetoothCallbackDelegate, logger = logger) {
}