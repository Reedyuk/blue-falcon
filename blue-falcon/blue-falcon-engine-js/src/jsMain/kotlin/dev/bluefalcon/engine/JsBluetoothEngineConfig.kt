package dev.bluefalcon.engine

import dev.bluefalcon.ApplicationContext
import dev.bluefalcon.BlueFalconDelegate
import dev.bluefalcon.Logger

class JsBluetoothEngineConfig(
    bluetoothCallbackDelegate: BlueFalconDelegate?,
    logger: Logger?
) : BluetoothEngineConfig(bluetoothCallbackDelegate, logger = logger) {
}