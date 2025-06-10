package dev.bluefalcon.engine

import dev.bluefalcon.ApplicationContext
import dev.bluefalcon.BlueFalconDelegate
import dev.bluefalcon.Logger

class AndroidBluetoothEngineConfig(
    val context: ApplicationContext,
    val autoDiscoverAllServicesAndCharacteristics: Boolean = true,
    bluetoothCallbackDelegate: BlueFalconDelegate?,
    logger: Logger? = null
) : BluetoothEngineConfig(bluetoothCallbackDelegate, logger = logger) {
}